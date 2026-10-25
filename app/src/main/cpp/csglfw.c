/*
 * CS Launcher Pro — original implementation of the GLFW 3.4 C API for Android.
 * LWJGL 3 binds every GLFW entry point by name, so the complete public API is exported. Functions that have no
 * meaning on Android (gamma ramps, window icons, window positions, joysticks — controllers are handled by the
 * launcher's own input engine) behave exactly like GLFW does on a platform without that capability.
 *
 * Rendering paths (selected by the launcher through environment variables before the JVM starts):
 *   CS_CONTEXT=EGL_GLES2|EGL_GLES3  -> EGL window surface on the ANativeWindow (GL4ES / ANGLE translate desktop GL)
 *   CS_CONTEXT=OSMESA               -> Mesa OSMesa off-screen context (Zink), blitted to the window on swap
 *   CS_GL_LIB / CS_EGL_LIB / CS_OSMESA_LIB -> absolute paths of the libraries used for symbol lookup
 */
#include "cs_common.h"
#include <EGL/egl.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <time.h>
#include <math.h>

/* ----------------------------------------------------------------- constants (values from the GLFW 3.4 API) */
#define GLFW_TRUE 1
#define GLFW_FALSE 0
#define GLFW_RELEASE 0
#define GLFW_PRESS 1
#define GLFW_NOT_INITIALIZED 0x00010001
#define GLFW_NO_CURRENT_CONTEXT 0x00010002
#define GLFW_INVALID_ENUM 0x00010003
#define GLFW_API_UNAVAILABLE 0x00010006
#define GLFW_PLATFORM_ERROR 0x00010008
#define GLFW_FEATURE_UNAVAILABLE 0x0001000C
#define GLFW_FOCUSED 0x00020001
#define GLFW_ICONIFIED 0x00020002
#define GLFW_RESIZABLE 0x00020003
#define GLFW_VISIBLE 0x00020004
#define GLFW_DECORATED 0x00020005
#define GLFW_MAXIMIZED 0x00020008
#define GLFW_HOVERED 0x0002000B
#define GLFW_TRANSPARENT_FRAMEBUFFER 0x0002000A
#define GLFW_CLIENT_API 0x00022001
#define GLFW_CONTEXT_VERSION_MAJOR 0x00022002
#define GLFW_CONTEXT_VERSION_MINOR 0x00022003
#define GLFW_OPENGL_PROFILE 0x00022008
#define GLFW_OPENGL_CORE_PROFILE 0x00032001
#define GLFW_OPENGL_API 0x00030001
#define GLFW_CURSOR 0x00033001
#define GLFW_STICKY_KEYS 0x00033002
#define GLFW_STICKY_MOUSE_BUTTONS 0x00033003
#define GLFW_LOCK_KEY_MODS 0x00033004
#define GLFW_RAW_MOUSE_MOTION 0x00033005
#define GLFW_CURSOR_NORMAL 0x00034001
#define GLFW_CURSOR_HIDDEN 0x00034002
#define GLFW_CURSOR_DISABLED 0x00034003
#define GLFW_PLATFORM_NULL 0x00060005
#define GLFW_KEY_LAST 348
#define GLFW_MOUSE_BUTTON_LAST 7

typedef void (*GLFWerrorfun)(int, const char *);
typedef void (*GLFWwindowposfun)(void *, int, int);
typedef void (*GLFWwindowsizefun)(void *, int, int);
typedef void (*GLFWwindowclosefun)(void *);
typedef void (*GLFWwindowrefreshfun)(void *);
typedef void (*GLFWwindowfocusfun)(void *, int);
typedef void (*GLFWwindowiconifyfun)(void *, int);
typedef void (*GLFWwindowmaximizefun)(void *, int);
typedef void (*GLFWframebuffersizefun)(void *, int, int);
typedef void (*GLFWwindowcontentscalefun)(void *, float, float);
typedef void (*GLFWkeyfun)(void *, int, int, int, int);
typedef void (*GLFWcharfun)(void *, unsigned int);
typedef void (*GLFWcharmodsfun)(void *, unsigned int, int);
typedef void (*GLFWmousebuttonfun)(void *, int, int, int);
typedef void (*GLFWcursorposfun)(void *, double, double);
typedef void (*GLFWcursorenterfun)(void *, int);
typedef void (*GLFWscrollfun)(void *, double, double);
typedef void (*GLFWdropfun)(void *, int, const char **);
typedef void (*GLFWmonitorfun)(void *, int);
typedef void (*GLFWjoystickfun)(int, int);
typedef struct { int width, height, redBits, greenBits, blueBits, refreshRate; } GLFWvidmode;
typedef struct { unsigned short *red, *green, *blue; unsigned int size; } GLFWgammaramp;
typedef struct { int width, height; unsigned char *pixels; } GLFWimage;
typedef struct { unsigned char buttons[15]; float axes[6]; } GLFWgamepadstate;

typedef struct CSWindow {
    int width, height, fbWidth, fbHeight;
    int shouldClose, cursorMode, stickyKeys, stickyMouse, lockMods, rawMotion;
    double cursorX, cursorY;
    char keys[GLFW_KEY_LAST + 1];
    char buttons[GLFW_MOUSE_BUTTON_LAST + 1];
    void *userPointer;
    char title[256];
    GLFWwindowposfun posCb; GLFWwindowsizefun sizeCb; GLFWwindowclosefun closeCb; GLFWwindowrefreshfun refreshCb;
    GLFWwindowfocusfun focusCb; GLFWwindowiconifyfun iconifyCb; GLFWwindowmaximizefun maximizeCb;
    GLFWframebuffersizefun fbSizeCb; GLFWwindowcontentscalefun scaleCb;
    GLFWkeyfun keyCb; GLFWcharfun charCb; GLFWcharmodsfun charModsCb; GLFWmousebuttonfun mouseCb;
    GLFWcursorposfun cursorPosCb; GLFWcursorenterfun cursorEnterCb; GLFWscrollfun scrollCb; GLFWdropfun dropCb;
    /* rendering */
    EGLSurface surface; EGLContext context;
    void *osmesaCtx; unsigned char *osmesaBuf; int osmesaW, osmesaH;
    int swapInterval;
} CSWindow;

/* ----------------------------------------------------------------- global state */
static int g_initialized = 0;
static GLFWerrorfun g_errorCb = NULL;
static int g_lastError = 0; static const char *g_lastErrorDesc = NULL;
static CSWindow *g_window = NULL;          /* Android has one game window */
static __thread CSWindow *t_current = NULL;
static double g_timeOffset = 0;
static int g_hintClientApi = GLFW_OPENGL_API, g_hintMajor = 2, g_hintMinor = 1;
static GLFWvidmode g_mode = { 1280, 720, 8, 8, 8, 60 };
static int g_monitorToken = 1; static void *g_monitorUser = NULL;
static char g_versionString[] = "3.4.0 CS-Android EGL OSMesa";

/* Android window handed over by the activity; guarded by g_winMu. */
static pthread_mutex_t g_winMu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_winCv = PTHREAD_COND_INITIALIZER;
static ANativeWindow *g_nativeWindow = NULL;
static int g_surfaceGeneration = 0, g_surfaceAckGeneration = 0;
static int g_surfaceW = 1280, g_surfaceH = 720, g_renderW = 0, g_renderH = 0;

/* Rendering backend */
enum { CTX_EGL_GLES2, CTX_EGL_GLES3, CTX_OSMESA, CTX_EGL_DESKTOP };
static int g_ctxKind = CTX_EGL_GLES2;
static void *g_glLib = NULL, *g_eglLib = NULL, *g_osmesaLib = NULL;
static EGLDisplay g_dpy = EGL_NO_DISPLAY; static EGLConfig g_cfg = NULL;
/* EGL entry points (resolved from the selected EGL library, e.g. ANGLE, or the system libEGL). */
static EGLDisplay (*p_eglGetDisplay)(EGLNativeDisplayType);
static EGLBoolean (*p_eglInitialize)(EGLDisplay, EGLint *, EGLint *);
static EGLBoolean (*p_eglChooseConfig)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
static EGLBoolean (*p_eglGetConfigAttrib)(EGLDisplay, EGLConfig, EGLint, EGLint *);
static EGLSurface (*p_eglCreateWindowSurface)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint *);
static EGLContext (*p_eglCreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
static EGLBoolean (*p_eglMakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
static EGLBoolean (*p_eglSwapBuffers)(EGLDisplay, EGLSurface);
static EGLBoolean (*p_eglSwapInterval)(EGLDisplay, EGLint);
static EGLBoolean (*p_eglDestroySurface)(EGLDisplay, EGLSurface);
static EGLBoolean (*p_eglDestroyContext)(EGLDisplay, EGLContext);
static EGLBoolean (*p_eglBindAPI)(EGLenum);
static EGLint (*p_eglGetError)(void);
static void *(*p_eglGetProcAddress)(const char *);
/* OSMesa */
typedef void *OSMesaContext;
static OSMesaContext (*p_OSMesaCreateContextExt)(unsigned int, int, int, int, OSMesaContext);
static OSMesaContext (*p_OSMesaCreateContextAttribs)(const int *, OSMesaContext);
static unsigned char (*p_OSMesaMakeCurrent)(OSMesaContext, void *, unsigned int, int, int);
static void (*p_OSMesaPixelStore)(int, int);
static void *(*p_OSMesaGetProcAddress)(const char *);
static void (*p_OSMesaDestroyContext)(OSMesaContext);
static void (*p_glFinish)(void);
#define OSMESA_RGBA 0x1908
#define OSMESA_Y_UP 0x11
#define GL_UNSIGNED_BYTE 0x1401
#define OSMESA_FORMAT 0x22
#define OSMESA_DEPTH_BITS 0x30
#define OSMESA_STENCIL_BITS 0x31
#define OSMESA_PROFILE 0x33
#define OSMESA_CORE_PROFILE 0x34
#define OSMESA_CONTEXT_MAJOR_VERSION 0x36
#define OSMESA_CONTEXT_MINOR_VERSION 0x37

static void glfw_error(int code, const char *desc) {
    g_lastError = code; g_lastErrorDesc = desc;
    LOGW("GLFW error 0x%x: %s", code, desc);
    if (g_errorCb) g_errorCb(code, desc);
}

static double now_s(void) { struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return ts.tv_sec + ts.tv_nsec / 1e9; }

/* ----------------------------------------------------------------- JNI: window/surface + input (called by ART) */
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env; if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "pro/cslauncher/app/game/NativeBridge");
    if (cls) cs_host_set_vm(vm, cls); else (*env)->ExceptionClear(env);
    return JNI_VERSION_1_6;
}

CS_EXPORT JNIEXPORT void JNICALL Java_pro_cslauncher_app_game_NativeBridge_setSurface(JNIEnv *env, jclass c, jobject surface, jint w, jint h, jint renderW, jint renderH) {
    pthread_mutex_lock(&g_winMu);
    if (g_nativeWindow) { ANativeWindow_release(g_nativeWindow); g_nativeWindow = NULL; }
    if (surface) {
        g_nativeWindow = ANativeWindow_fromSurface(env, surface);
        g_surfaceW = w; g_surfaceH = h; g_renderW = renderW > 0 ? renderW : w; g_renderH = renderH > 0 ? renderH : h;
    }
    g_surfaceGeneration++;
    pthread_cond_broadcast(&g_winCv);
    pthread_mutex_unlock(&g_winMu);
    cs_event e = { CS_EV_FRAMEBUFFER_SIZE, g_renderW, g_renderH, 0, 0, 0, 0 }; if (surface) cs_queue_push(&e);
}

/* Blocks the UI thread (bounded) until the game thread stopped using the old surface. */
CS_EXPORT JNIEXPORT jboolean JNICALL Java_pro_cslauncher_app_game_NativeBridge_releaseSurface(JNIEnv *env, jclass c, jint timeoutMs) {
    pthread_mutex_lock(&g_winMu);
    if (g_nativeWindow) { ANativeWindow_release(g_nativeWindow); g_nativeWindow = NULL; }
    int gen = ++g_surfaceGeneration;
    pthread_cond_broadcast(&g_winCv);
    cs_queue_wake();
    struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += timeoutMs / 1000; ts.tv_nsec += (timeoutMs % 1000) * 1000000L; if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }
    int ok = 1;
    while (g_window && (g_window->surface != EGL_NO_SURFACE) && g_surfaceAckGeneration < gen) {
        if (pthread_cond_timedwait(&g_winCv, &g_winMu, &ts) != 0) { ok = 0; break; }
    }
    pthread_mutex_unlock(&g_winMu);
    return ok ? JNI_TRUE : JNI_FALSE;
}

CS_EXPORT JNIEXPORT void JNICALL Java_pro_cslauncher_app_game_NativeBridge_sendKey(JNIEnv *e, jclass c, jint key, jint scancode, jint action, jint mods) { cs_event ev = { CS_EV_KEY, key, scancode, action, mods, 0, 0 }; cs_queue_push(&ev); }
CS_EXPORT JNIEXPORT void JNICALL Java_pro_cslauncher_app_game_NativeBridge_sendChar(JNIEnv *e, jclass c, jint cp, jint mods) { cs_event ev = { CS_EV_CHAR, cp, mods, 0, 0, 0, 0 }; cs_queue_push(&ev); }
CS_EXPORT JNIEXPORT void JNICALL Java_pro_cslauncher_app_game_NativeBridge_sendMouseButton(JNIEnv *e, jclass c, jint b, jint action, jint mods) { cs_event ev = { CS_EV_MOUSE_BUTTON, b, action, mods, 0, 0, 0 }; cs_queue_push(&ev); }
CS_EXPORT JNIEXPORT void JNICALL Java_pro_cslauncher_app_game_NativeBridge_sendCursorPos(JNIEnv *e, jclass c, jdouble x, jdouble y) { cs_event ev = { CS_EV_CURSOR_POS, 0, 0, 0, 0, x, y }; cs_queue_push(&ev); }
CS_EXPORT JNIEXPORT void JNICALL Java_pro_cslauncher_app_game_NativeBridge_sendCursorDelta(JNIEnv *e, jclass c, jdouble dx, jdouble dy) { cs_event ev = { CS_EV_CURSOR_POS, 1, 0, 0, 0, dx, dy }; cs_queue_push(&ev); }
CS_EXPORT JNIEXPORT void JNICALL Java_pro_cslauncher_app_game_NativeBridge_sendScroll(JNIEnv *e, jclass c, jdouble dx, jdouble dy) { cs_event ev = { CS_EV_SCROLL, 0, 0, 0, 0, dx, dy }; cs_queue_push(&ev); }
CS_EXPORT JNIEXPORT void JNICALL Java_pro_cslauncher_app_game_NativeBridge_sendFocus(JNIEnv *e, jclass c, jboolean f) { cs_event ev = { CS_EV_FOCUS, f ? 1 : 0, 0, 0, 0, 0, 0 }; cs_queue_push(&ev); }
CS_EXPORT JNIEXPORT void JNICALL Java_pro_cslauncher_app_game_NativeBridge_requestClose(JNIEnv *e, jclass c) { cs_event ev = { CS_EV_CLOSE, 0, 0, 0, 0, 0, 0 }; cs_queue_push(&ev); }

/* ----------------------------------------------------------------- backend loading */
static void *open_lib(const char *envName, const char *fallback) {
    const char *p = getenv(envName);
    void *h = NULL;
    if (p && *p) { h = dlopen(p, RTLD_NOW | RTLD_GLOBAL); if (!h) LOGE("dlopen %s failed: %s", p, dlerror()); }
    if (!h && fallback) h = dlopen(fallback, RTLD_NOW | RTLD_GLOBAL);
    return h;
}

static int load_backend(void) {
    const char *k = getenv("CS_CONTEXT");
    g_ctxKind = (k && strcmp(k, "EGL_GLES3") == 0) ? CTX_EGL_GLES3 : (k && strcmp(k, "OSMESA") == 0) ? CTX_OSMESA
              : (k && strcmp(k, "EGL_DESKTOP") == 0) ? CTX_EGL_DESKTOP : CTX_EGL_GLES2;
    g_glLib = open_lib("CS_GL_LIB", NULL);
    if (g_ctxKind == CTX_OSMESA) {
        g_osmesaLib = open_lib("CS_OSMESA_LIB", "libOSMesa.so");
        if (!g_osmesaLib) { glfw_error(GLFW_API_UNAVAILABLE, "OSMesa library could not be loaded"); return 0; }
        p_OSMesaCreateContextExt = dlsym(g_osmesaLib, "OSMesaCreateContextExt");
        p_OSMesaCreateContextAttribs = dlsym(g_osmesaLib, "OSMesaCreateContextAttribs");
        p_OSMesaMakeCurrent = dlsym(g_osmesaLib, "OSMesaMakeCurrent");
        p_OSMesaPixelStore = dlsym(g_osmesaLib, "OSMesaPixelStore");
        p_OSMesaGetProcAddress = dlsym(g_osmesaLib, "OSMesaGetProcAddress");
        p_OSMesaDestroyContext = dlsym(g_osmesaLib, "OSMesaDestroyContext");
        if (!p_OSMesaMakeCurrent || (!p_OSMesaCreateContextExt && !p_OSMesaCreateContextAttribs) || !p_OSMesaGetProcAddress) {
            glfw_error(GLFW_API_UNAVAILABLE, "OSMesa library lacks required entry points"); return 0;
        }
        return 1;
    }
    g_eglLib = open_lib("CS_EGL_LIB", "libEGL.so");
    if (!g_eglLib) { glfw_error(GLFW_API_UNAVAILABLE, "EGL library could not be loaded"); return 0; }
#define LOAD(n) p_##n = dlsym(g_eglLib, #n); if (!p_##n) { glfw_error(GLFW_API_UNAVAILABLE, "EGL lacks " #n); return 0; }
    LOAD(eglGetDisplay) LOAD(eglInitialize) LOAD(eglChooseConfig) LOAD(eglGetConfigAttrib) LOAD(eglCreateWindowSurface) LOAD(eglCreateContext)
    LOAD(eglMakeCurrent) LOAD(eglSwapBuffers) LOAD(eglSwapInterval) LOAD(eglDestroySurface) LOAD(eglDestroyContext) LOAD(eglBindAPI)
    LOAD(eglGetError) LOAD(eglGetProcAddress)
#undef LOAD
    g_dpy = p_eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint maj, min;
    if (g_dpy == EGL_NO_DISPLAY || !p_eglInitialize(g_dpy, &maj, &min)) { glfw_error(GLFW_PLATFORM_ERROR, "eglInitialize failed"); return 0; }
    /* EGL_DESKTOP: the EGL library is a desktop-GL front end over GLES (MobileGlues) that accepts EGL_OPENGL_API. */
    p_eglBindAPI(g_ctxKind == CTX_EGL_DESKTOP ? EGL_OPENGL_API : EGL_OPENGL_ES_API);
    EGLint renderable = g_ctxKind == CTX_EGL_DESKTOP ? EGL_OPENGL_BIT : g_ctxKind == CTX_EGL_GLES3 ? 0x40 /* EGL_OPENGL_ES3_BIT */ : EGL_OPENGL_ES2_BIT;
    const EGLint attrs[] = { EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, renderable, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
                             EGL_ALPHA_SIZE, 8, EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8, EGL_NONE };
    EGLint n = 0;
    if (!p_eglChooseConfig(g_dpy, attrs, &g_cfg, 1, &n) || n < 1) {
        const EGLint relaxed[] = { EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, renderable, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_DEPTH_SIZE, 16, EGL_NONE };
        if (!p_eglChooseConfig(g_dpy, relaxed, &g_cfg, 1, &n) || n < 1) { glfw_error(GLFW_PLATFORM_ERROR, "No suitable EGL config"); return 0; }
    }
    LOGI("EGL %d.%d initialised, context kind %d", maj, min, g_ctxKind);
    return 1;
}

/* Waits until the activity provides a window (game started while paused, or surface being recreated). */
static ANativeWindow *wait_window(void) {
    pthread_mutex_lock(&g_winMu);
    while (!g_nativeWindow) pthread_cond_wait(&g_winCv, &g_winMu);
    ANativeWindow *w = g_nativeWindow; ANativeWindow_acquire(w);
    pthread_mutex_unlock(&g_winMu);
    return w;
}

static int create_surface(CSWindow *win) {
    ANativeWindow *nw = wait_window();
    int gen; pthread_mutex_lock(&g_winMu); gen = g_surfaceGeneration; int rw = g_renderW, rh = g_renderH; pthread_mutex_unlock(&g_winMu);
    if (g_ctxKind == CTX_OSMESA) {
        ANativeWindow_setBuffersGeometry(nw, rw, rh, WINDOW_FORMAT_RGBA_8888);
    } else {
        EGLint fmt = 0; p_eglGetConfigAttrib(g_dpy, g_cfg, EGL_NATIVE_VISUAL_ID, &fmt);
        ANativeWindow_setBuffersGeometry(nw, rw, rh, fmt);   /* render-scale: hardware scaler upsamples */
        win->surface = p_eglCreateWindowSurface(g_dpy, g_cfg, nw, NULL);
        if (win->surface == EGL_NO_SURFACE) { ANativeWindow_release(nw); glfw_error(GLFW_PLATFORM_ERROR, "eglCreateWindowSurface failed"); return 0; }
    }
    ANativeWindow_release(nw);
    win->fbWidth = rw; win->fbHeight = rh; win->width = rw; win->height = rh;
    pthread_mutex_lock(&g_winMu); g_surfaceAckGeneration = gen; pthread_cond_broadcast(&g_winCv); pthread_mutex_unlock(&g_winMu);
    return 1;
}

static void destroy_surface(CSWindow *win) {
    if (g_ctxKind != CTX_OSMESA && win->surface != EGL_NO_SURFACE) {
        p_eglMakeCurrent(g_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        p_eglDestroySurface(g_dpy, win->surface); win->surface = EGL_NO_SURFACE;
    }
    pthread_mutex_lock(&g_winMu); g_surfaceAckGeneration = g_surfaceGeneration; pthread_cond_broadcast(&g_winCv); pthread_mutex_unlock(&g_winMu);
}

static int make_current_internal(CSWindow *win) {
    if (g_ctxKind == CTX_OSMESA) {
        if (!win->osmesaCtx) {
            if (p_OSMesaCreateContextAttribs) {
                const int a[] = { OSMESA_FORMAT, OSMESA_RGBA, OSMESA_DEPTH_BITS, 24, OSMESA_STENCIL_BITS, 8, OSMESA_PROFILE, OSMESA_CORE_PROFILE,
                                  OSMESA_CONTEXT_MAJOR_VERSION, g_hintMajor >= 3 ? g_hintMajor : 3, OSMESA_CONTEXT_MINOR_VERSION, g_hintMajor >= 3 ? g_hintMinor : 2, 0 };
                win->osmesaCtx = p_OSMesaCreateContextAttribs(a, NULL);
            }
            if (!win->osmesaCtx && p_OSMesaCreateContextExt) win->osmesaCtx = p_OSMesaCreateContextExt(OSMESA_RGBA, 24, 8, 0, NULL);
            if (!win->osmesaCtx) { glfw_error(GLFW_PLATFORM_ERROR, "OSMesaCreateContext failed"); return 0; }
            p_glFinish = (void (*)(void))p_OSMesaGetProcAddress("glFinish");
        }
        if (!win->osmesaBuf || win->osmesaW != win->fbWidth || win->osmesaH != win->fbHeight) {
            free(win->osmesaBuf);
            win->osmesaBuf = calloc((size_t)win->fbWidth * win->fbHeight, 4);
            win->osmesaW = win->fbWidth; win->osmesaH = win->fbHeight;
        }
        if (!p_OSMesaMakeCurrent(win->osmesaCtx, win->osmesaBuf, GL_UNSIGNED_BYTE, win->fbWidth, win->fbHeight)) { glfw_error(GLFW_PLATFORM_ERROR, "OSMesaMakeCurrent failed"); return 0; }
        if (p_OSMesaPixelStore) p_OSMesaPixelStore(OSMESA_Y_UP, 0);   /* top-down rows match ANativeWindow */
        return 1;
    }
    if (win->context == EGL_NO_CONTEXT) {
        if (g_ctxKind == CTX_EGL_DESKTOP) {
            /* Honour the game's GLFW hints (Minecraft 1.17+ asks for 3.2 core); older versions ask for 2.1 compatibility. */
            int maj = g_hintMajor > 0 ? g_hintMajor : 3, min = g_hintMajor > 0 ? g_hintMinor : 2;
            const EGLint ca[] = { 0x3098 /* EGL_CONTEXT_MAJOR_VERSION */, maj, 0x30FB /* EGL_CONTEXT_MINOR_VERSION */, min,
                                  0x30FD /* EGL_CONTEXT_OPENGL_PROFILE_MASK */, maj >= 3 && (maj > 3 || min >= 2) ? 0x1 /* CORE */ : 0x2 /* COMPAT */, EGL_NONE };
            win->context = p_eglCreateContext(g_dpy, g_cfg, EGL_NO_CONTEXT, ca);
            if (win->context == EGL_NO_CONTEXT) {   /* front ends that ignore profile attributes */
                const EGLint basic[] = { 0x3098, maj, 0x30FB, min, EGL_NONE };
                win->context = p_eglCreateContext(g_dpy, g_cfg, EGL_NO_CONTEXT, basic);
            }
        } else {
            const EGLint ca[] = { EGL_CONTEXT_CLIENT_VERSION, g_ctxKind == CTX_EGL_GLES3 ? 3 : 2, EGL_NONE };
            win->context = p_eglCreateContext(g_dpy, g_cfg, EGL_NO_CONTEXT, ca);
        }
        if (win->context == EGL_NO_CONTEXT) { glfw_error(GLFW_PLATFORM_ERROR, "eglCreateContext failed"); return 0; }
    }
    if (!p_eglMakeCurrent(g_dpy, win->surface, win->surface, win->context)) { glfw_error(GLFW_PLATFORM_ERROR, "eglMakeCurrent failed"); return 0; }
    p_eglSwapInterval(g_dpy, win->swapInterval);
    return 1;
}

/* Called on the game thread when the activity replaced or removed the window. */
static void handle_surface_change(CSWindow *win) {
    int gen; pthread_mutex_lock(&g_winMu); gen = g_surfaceGeneration; pthread_mutex_unlock(&g_winMu);
    if (gen == g_surfaceAckGeneration) return;
    LOGI("Surface changed (generation %d); recreating", gen);
    destroy_surface(win);
    int oldW = win->fbWidth, oldH = win->fbHeight;
    if (!create_surface(win)) return;
    if (t_current == win) make_current_internal(win);
    if ((oldW != win->fbWidth || oldH != win->fbHeight)) {
        if (win->fbSizeCb) win->fbSizeCb(win, win->fbWidth, win->fbHeight);
        if (win->sizeCb) win->sizeCb(win, win->width, win->height);
    }
}

/* ----------------------------------------------------------------- init / version / errors */
CS_EXPORT int glfwInit(void) {
    if (g_initialized) return GLFW_TRUE;
    if (!load_backend()) return GLFW_FALSE;
    g_timeOffset = now_s(); g_initialized = 1;
    return GLFW_TRUE;
}
CS_EXPORT void glfwTerminate(void) {
    if (g_window) { CSWindow *w = g_window; g_window = NULL; destroy_surface(w); free(w->osmesaBuf); free(w); }
    g_initialized = 0;
}
CS_EXPORT void glfwInitHint(int hint, int value) {}
CS_EXPORT void glfwInitAllocator(const void *allocator) {}
CS_EXPORT void glfwInitVulkanLoader(void *loader) {}
CS_EXPORT void glfwGetVersion(int *major, int *minor, int *rev) { if (major) *major = 3; if (minor) *minor = 4; if (rev) *rev = 0; }
CS_EXPORT const char *glfwGetVersionString(void) { return g_versionString; }
CS_EXPORT int glfwGetError(const char **desc) { int e = g_lastError; if (desc) *desc = g_lastErrorDesc; g_lastError = 0; g_lastErrorDesc = NULL; return e; }
CS_EXPORT GLFWerrorfun glfwSetErrorCallback(GLFWerrorfun cb) { GLFWerrorfun o = g_errorCb; g_errorCb = cb; return o; }
CS_EXPORT int glfwGetPlatform(void) { return GLFW_PLATFORM_NULL; }
CS_EXPORT int glfwPlatformSupported(int platform) { return platform == GLFW_PLATFORM_NULL; }

/* ----------------------------------------------------------------- monitors: the device display */
static void *g_monitors[1] = { &g_monitorToken };
CS_EXPORT void **glfwGetMonitors(int *count) { if (count) *count = 1; return g_monitors; }
CS_EXPORT void *glfwGetPrimaryMonitor(void) { return &g_monitorToken; }
CS_EXPORT void glfwGetMonitorPos(void *m, int *x, int *y) { if (x) *x = 0; if (y) *y = 0; }
CS_EXPORT void glfwGetMonitorWorkarea(void *m, int *x, int *y, int *w, int *h) { if (x) *x = 0; if (y) *y = 0; if (w) *w = g_surfaceW; if (h) *h = g_surfaceH; }
CS_EXPORT void glfwGetMonitorPhysicalSize(void *m, int *w, int *h) { if (w) *w = 0; if (h) *h = 0; }
CS_EXPORT void glfwGetMonitorContentScale(void *m, float *x, float *y) { if (x) *x = 1.f; if (y) *y = 1.f; }
CS_EXPORT const char *glfwGetMonitorName(void *m) { return "Android display"; }
CS_EXPORT void glfwSetMonitorUserPointer(void *m, void *p) { g_monitorUser = p; }
CS_EXPORT void *glfwGetMonitorUserPointer(void *m) { return g_monitorUser; }
CS_EXPORT GLFWmonitorfun glfwSetMonitorCallback(GLFWmonitorfun cb) { return NULL; }
CS_EXPORT const GLFWvidmode *glfwGetVideoModes(void *m, int *count) { g_mode.width = g_renderW ? g_renderW : g_surfaceW; g_mode.height = g_renderH ? g_renderH : g_surfaceH; if (count) *count = 1; return &g_mode; }
CS_EXPORT const GLFWvidmode *glfwGetVideoMode(void *m) { return glfwGetVideoModes(m, NULL); }
CS_EXPORT void glfwSetGamma(void *m, float g) { glfw_error(GLFW_FEATURE_UNAVAILABLE, "Gamma is not supported on Android"); }
CS_EXPORT const GLFWgammaramp *glfwGetGammaRamp(void *m) { glfw_error(GLFW_FEATURE_UNAVAILABLE, "Gamma is not supported on Android"); return NULL; }
CS_EXPORT void glfwSetGammaRamp(void *m, const GLFWgammaramp *r) { glfw_error(GLFW_FEATURE_UNAVAILABLE, "Gamma is not supported on Android"); }

/* ----------------------------------------------------------------- windows */
CS_EXPORT void glfwDefaultWindowHints(void) { g_hintClientApi = GLFW_OPENGL_API; g_hintMajor = 2; g_hintMinor = 1; }
CS_EXPORT void glfwWindowHint(int hint, int value) {
    if (hint == GLFW_CLIENT_API) g_hintClientApi = value;
    else if (hint == GLFW_CONTEXT_VERSION_MAJOR) g_hintMajor = value;
    else if (hint == GLFW_CONTEXT_VERSION_MINOR) g_hintMinor = value;
}
CS_EXPORT void glfwWindowHintString(int hint, const char *value) {}

CS_EXPORT void *glfwCreateWindow(int width, int height, const char *title, void *monitor, void *share) {
    if (!g_initialized) { glfw_error(GLFW_NOT_INITIALIZED, "GLFW is not initialized"); return NULL; }
    if (g_window) { glfw_error(GLFW_PLATFORM_ERROR, "Only one window is supported on Android"); return NULL; }
    CSWindow *w = calloc(1, sizeof(CSWindow));
    w->cursorMode = GLFW_CURSOR_NORMAL; w->surface = EGL_NO_SURFACE; w->context = EGL_NO_CONTEXT; w->swapInterval = 1;
    snprintf(w->title, sizeof w->title, "%s", title ? title : "");
    if (!create_surface(w)) { free(w); return NULL; }
    w->cursorX = w->width / 2.0; w->cursorY = w->height / 2.0;
    g_window = w;
    cs_host_window_ready(w->fbWidth, w->fbHeight);
    LOGI("Window created %dx%d (requested %dx%d)", w->fbWidth, w->fbHeight, width, height);
    return w;
}
CS_EXPORT void glfwDestroyWindow(void *wp) {
    CSWindow *w = wp; if (!w) return;
    if (t_current == w) { if (g_ctxKind == CTX_OSMESA) {} else p_eglMakeCurrent(g_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); t_current = NULL; }
    destroy_surface(w);
    if (g_ctxKind == CTX_OSMESA) { if (w->osmesaCtx && p_OSMesaDestroyContext) p_OSMesaDestroyContext(w->osmesaCtx); free(w->osmesaBuf); }
    else if (w->context != EGL_NO_CONTEXT) p_eglDestroyContext(g_dpy, w->context);
    if (g_window == w) g_window = NULL;
    free(w);
}
CS_EXPORT int glfwWindowShouldClose(void *w) { return w ? ((CSWindow *)w)->shouldClose : GLFW_TRUE; }
CS_EXPORT void glfwSetWindowShouldClose(void *w, int v) { if (w) ((CSWindow *)w)->shouldClose = v; }
CS_EXPORT const char *glfwGetWindowTitle(void *w) { return w ? ((CSWindow *)w)->title : NULL; }
CS_EXPORT void glfwSetWindowTitle(void *w, const char *t) { if (w) snprintf(((CSWindow *)w)->title, 256, "%s", t ? t : ""); }
CS_EXPORT void glfwSetWindowIcon(void *w, int n, const GLFWimage *imgs) {}
CS_EXPORT void glfwGetWindowPos(void *w, int *x, int *y) { if (x) *x = 0; if (y) *y = 0; }
CS_EXPORT void glfwSetWindowPos(void *w, int x, int y) {}
CS_EXPORT void glfwGetWindowSize(void *wp, int *wd, int *ht) { CSWindow *w = wp; if (wd) *wd = w ? w->width : 0; if (ht) *ht = w ? w->height : 0; }
CS_EXPORT void glfwSetWindowSizeLimits(void *w, int a, int b, int c, int d) {}
CS_EXPORT void glfwSetWindowAspectRatio(void *w, int n, int d) {}
CS_EXPORT void glfwSetWindowSize(void *w, int wd, int ht) { /* The window always fills the display; the game is told its real size. */ }
CS_EXPORT void glfwGetFramebufferSize(void *wp, int *wd, int *ht) { CSWindow *w = wp; if (wd) *wd = w ? w->fbWidth : 0; if (ht) *ht = w ? w->fbHeight : 0; }
CS_EXPORT void glfwGetWindowFrameSize(void *w, int *l, int *t, int *r, int *b) { if (l) *l = 0; if (t) *t = 0; if (r) *r = 0; if (b) *b = 0; }
CS_EXPORT void glfwGetWindowContentScale(void *w, float *x, float *y) { if (x) *x = 1.f; if (y) *y = 1.f; }
CS_EXPORT float glfwGetWindowOpacity(void *w) { return 1.f; }
CS_EXPORT void glfwSetWindowOpacity(void *w, float o) {}
CS_EXPORT void glfwIconifyWindow(void *w) {}
CS_EXPORT void glfwRestoreWindow(void *w) {}
CS_EXPORT void glfwMaximizeWindow(void *w) {}
CS_EXPORT void glfwShowWindow(void *w) {}
CS_EXPORT void glfwHideWindow(void *w) {}
CS_EXPORT void glfwFocusWindow(void *w) {}
CS_EXPORT void glfwRequestWindowAttention(void *w) {}
CS_EXPORT void *glfwGetWindowMonitor(void *w) { return NULL; }
CS_EXPORT void glfwSetWindowMonitor(void *w, void *m, int x, int y, int wd, int ht, int rr) { /* fullscreen toggles have no effect: always fullscreen */ }
CS_EXPORT int glfwGetWindowAttrib(void *w, int a) {
    switch (a) {
        case GLFW_FOCUSED: case GLFW_VISIBLE: case GLFW_HOVERED: return GLFW_TRUE;
        case GLFW_ICONIFIED: case GLFW_RESIZABLE: case GLFW_DECORATED: case GLFW_MAXIMIZED: case GLFW_TRANSPARENT_FRAMEBUFFER: return GLFW_FALSE;
        case GLFW_CLIENT_API: return g_hintClientApi;
        case GLFW_CONTEXT_VERSION_MAJOR: return g_hintMajor;
        case GLFW_CONTEXT_VERSION_MINOR: return g_hintMinor;
        default: return 0;
    }
}
CS_EXPORT void glfwSetWindowAttrib(void *w, int a, int v) {}
CS_EXPORT void glfwSetWindowUserPointer(void *w, void *p) { if (w) ((CSWindow *)w)->userPointer = p; }
CS_EXPORT void *glfwGetWindowUserPointer(void *w) { return w ? ((CSWindow *)w)->userPointer : NULL; }
#define SETCB(name, type, field) CS_EXPORT type name(void *wp, type cb) { CSWindow *w = wp; if (!w) return NULL; type o = w->field; w->field = cb; return o; }
SETCB(glfwSetWindowPosCallback, GLFWwindowposfun, posCb)
SETCB(glfwSetWindowSizeCallback, GLFWwindowsizefun, sizeCb)
SETCB(glfwSetWindowCloseCallback, GLFWwindowclosefun, closeCb)
SETCB(glfwSetWindowRefreshCallback, GLFWwindowrefreshfun, refreshCb)
SETCB(glfwSetWindowFocusCallback, GLFWwindowfocusfun, focusCb)
SETCB(glfwSetWindowIconifyCallback, GLFWwindowiconifyfun, iconifyCb)
SETCB(glfwSetWindowMaximizeCallback, GLFWwindowmaximizefun, maximizeCb)
SETCB(glfwSetFramebufferSizeCallback, GLFWframebuffersizefun, fbSizeCb)
SETCB(glfwSetWindowContentScaleCallback, GLFWwindowcontentscalefun, scaleCb)
SETCB(glfwSetKeyCallback, GLFWkeyfun, keyCb)
SETCB(glfwSetCharCallback, GLFWcharfun, charCb)
SETCB(glfwSetCharModsCallback, GLFWcharmodsfun, charModsCb)
SETCB(glfwSetMouseButtonCallback, GLFWmousebuttonfun, mouseCb)
SETCB(glfwSetCursorPosCallback, GLFWcursorposfun, cursorPosCb)
SETCB(glfwSetCursorEnterCallback, GLFWcursorenterfun, cursorEnterCb)
SETCB(glfwSetScrollCallback, GLFWscrollfun, scrollCb)
SETCB(glfwSetDropCallback, GLFWdropfun, dropCb)
#undef SETCB

/* ----------------------------------------------------------------- events */
static void dispatch(CSWindow *w, const cs_event *e) {
    switch (e->type) {
        case CS_EV_KEY:
            if (e->i1 >= 0 && e->i1 <= GLFW_KEY_LAST) w->keys[e->i1] = (char)(e->i3 != GLFW_RELEASE);
            if (w->keyCb) w->keyCb(w, e->i1, e->i2, e->i3, e->i4);
            break;
        case CS_EV_CHAR:
            if (w->charCb) w->charCb(w, (unsigned int)e->i1);
            if (w->charModsCb) w->charModsCb(w, (unsigned int)e->i1, e->i2);
            break;
        case CS_EV_MOUSE_BUTTON:
            if (e->i1 >= 0 && e->i1 <= GLFW_MOUSE_BUTTON_LAST) w->buttons[e->i1] = (char)(e->i2 == GLFW_PRESS);
            if (w->mouseCb) w->mouseCb(w, e->i1, e->i2, e->i3);
            break;
        case CS_EV_CURSOR_POS:
            if (e->i1 == 1) { w->cursorX += e->d1; w->cursorY += e->d2; }  /* relative (camera) */
            else { w->cursorX = e->d1; w->cursorY = e->d2; }
            if (w->cursorMode != GLFW_CURSOR_DISABLED) {    /* a visible cursor stays inside the window */
                if (w->cursorX < 0) w->cursorX = 0; if (w->cursorY < 0) w->cursorY = 0;
                if (w->cursorX > w->width - 1) w->cursorX = w->width - 1; if (w->cursorY > w->height - 1) w->cursorY = w->height - 1;
            }
            if (w->cursorPosCb) w->cursorPosCb(w, w->cursorX, w->cursorY);
            break;
        case CS_EV_SCROLL: if (w->scrollCb) w->scrollCb(w, e->d1, e->d2); break;
        case CS_EV_FOCUS: if (w->focusCb) w->focusCb(w, e->i1); break;
        case CS_EV_CLOSE: w->shouldClose = GLFW_TRUE; if (w->closeCb) w->closeCb(w); break;
        case CS_EV_FRAMEBUFFER_SIZE: break;   /* applied by the surface change handler on swap */
    }
}
CS_EXPORT void glfwPollEvents(void) {
    CSWindow *w = g_window; if (!w) return;
    cs_event e;
    while (cs_queue_pop(&e)) dispatch(w, &e);
}
CS_EXPORT void glfwWaitEvents(void) { cs_queue_wait(-1); glfwPollEvents(); }
CS_EXPORT void glfwWaitEventsTimeout(double t) { cs_queue_wait(t); glfwPollEvents(); }
CS_EXPORT void glfwPostEmptyEvent(void) { cs_queue_wake(); }

CS_EXPORT int glfwGetInputMode(void *wp, int mode) {
    CSWindow *w = wp; if (!w) return 0;
    switch (mode) { case GLFW_CURSOR: return w->cursorMode; case GLFW_STICKY_KEYS: return w->stickyKeys; case GLFW_STICKY_MOUSE_BUTTONS: return w->stickyMouse;
                    case GLFW_LOCK_KEY_MODS: return w->lockMods; case GLFW_RAW_MOUSE_MOTION: return w->rawMotion; default: glfw_error(GLFW_INVALID_ENUM, "Invalid input mode"); return 0; }
}
CS_EXPORT void glfwSetInputMode(void *wp, int mode, int value) {
    CSWindow *w = wp; if (!w) return;
    if (mode == GLFW_CURSOR) {
        int wasGrabbed = w->cursorMode == GLFW_CURSOR_DISABLED, grabbed = value == GLFW_CURSOR_DISABLED;
        w->cursorMode = value;
        if (wasGrabbed != grabbed) cs_host_cursor_mode(grabbed);   /* touch engine switches camera/menu handling */
    } else if (mode == GLFW_STICKY_KEYS) w->stickyKeys = value;
    else if (mode == GLFW_STICKY_MOUSE_BUTTONS) w->stickyMouse = value;
    else if (mode == GLFW_LOCK_KEY_MODS) w->lockMods = value;
    else if (mode == GLFW_RAW_MOUSE_MOTION) w->rawMotion = value;
}
CS_EXPORT int glfwRawMouseMotionSupported(void) { return GLFW_TRUE; }
CS_EXPORT const char *glfwGetKeyName(int key, int scancode) {
    static char buf[2];
    if (key >= 39 && key <= 96) { buf[0] = (char)(key >= 65 && key <= 90 ? key + 32 : key); buf[1] = 0; return buf; }
    return NULL;
}
CS_EXPORT int glfwGetKeyScancode(int key) { return key; }
CS_EXPORT int glfwGetKey(void *wp, int key) { CSWindow *w = wp; return (w && key >= 0 && key <= GLFW_KEY_LAST && w->keys[key]) ? GLFW_PRESS : GLFW_RELEASE; }
CS_EXPORT int glfwGetMouseButton(void *wp, int b) { CSWindow *w = wp; return (w && b >= 0 && b <= GLFW_MOUSE_BUTTON_LAST && w->buttons[b]) ? GLFW_PRESS : GLFW_RELEASE; }
CS_EXPORT void glfwGetCursorPos(void *wp, double *x, double *y) { CSWindow *w = wp; if (x) *x = w ? w->cursorX : 0; if (y) *y = w ? w->cursorY : 0; }
CS_EXPORT void glfwSetCursorPos(void *wp, double x, double y) {
    CSWindow *w = wp; if (!w) return;
    w->cursorX = x; w->cursorY = y;
    if (w->cursorMode != GLFW_CURSOR_DISABLED) cs_host_cursor_pos(x, y);  /* keep the launcher's dynamic cursor in sync */
}
static int g_cursorToken = 1;
CS_EXPORT void *glfwCreateCursor(const GLFWimage *img, int xh, int yh) { return &g_cursorToken; }
CS_EXPORT void *glfwCreateStandardCursor(int shape) { return &g_cursorToken; }
CS_EXPORT void glfwDestroyCursor(void *c) {}
CS_EXPORT void glfwSetCursor(void *w, void *c) {}

/* Controllers are translated by the launcher's input engine into keyboard/mouse events, so GLFW reports none. */
CS_EXPORT int glfwJoystickPresent(int jid) { return GLFW_FALSE; }
CS_EXPORT const float *glfwGetJoystickAxes(int jid, int *count) { if (count) *count = 0; return NULL; }
CS_EXPORT const unsigned char *glfwGetJoystickButtons(int jid, int *count) { if (count) *count = 0; return NULL; }
CS_EXPORT const unsigned char *glfwGetJoystickHats(int jid, int *count) { if (count) *count = 0; return NULL; }
CS_EXPORT const char *glfwGetJoystickName(int jid) { return NULL; }
CS_EXPORT const char *glfwGetJoystickGUID(int jid) { return NULL; }
CS_EXPORT void glfwSetJoystickUserPointer(int jid, void *p) {}
CS_EXPORT void *glfwGetJoystickUserPointer(int jid) { return NULL; }
CS_EXPORT int glfwJoystickIsGamepad(int jid) { return GLFW_FALSE; }
CS_EXPORT GLFWjoystickfun glfwSetJoystickCallback(GLFWjoystickfun cb) { return NULL; }
CS_EXPORT int glfwUpdateGamepadMappings(const char *s) { return GLFW_TRUE; }
CS_EXPORT const char *glfwGetGamepadName(int jid) { return NULL; }
CS_EXPORT int glfwGetGamepadState(int jid, GLFWgamepadstate *s) { return GLFW_FALSE; }

/* ----------------------------------------------------------------- clipboard / time */
static char *g_clip = NULL;
CS_EXPORT void glfwSetClipboardString(void *w, const char *s) { cs_host_set_clipboard(s); }
CS_EXPORT const char *glfwGetClipboardString(void *w) { free(g_clip); g_clip = cs_host_get_clipboard(); return g_clip; }
CS_EXPORT double glfwGetTime(void) { return now_s() - g_timeOffset; }
CS_EXPORT void glfwSetTime(double t) { g_timeOffset = now_s() - t; }
CS_EXPORT uint64_t glfwGetTimerValue(void) { struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec; }
CS_EXPORT uint64_t glfwGetTimerFrequency(void) { return 1000000000ULL; }

/* ----------------------------------------------------------------- context */
CS_EXPORT void glfwMakeContextCurrent(void *wp) {
    CSWindow *w = wp;
    if (!w) {
        if (g_ctxKind != CTX_OSMESA && g_dpy != EGL_NO_DISPLAY) p_eglMakeCurrent(g_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        else if (g_ctxKind == CTX_OSMESA && p_OSMesaMakeCurrent) p_OSMesaMakeCurrent(NULL, NULL, 0, 0, 0);
        t_current = NULL; return;
    }
    handle_surface_change(w);
    if (make_current_internal(w)) t_current = w;
}
CS_EXPORT void *glfwGetCurrentContext(void) { return t_current; }

static void blit_osmesa(CSWindow *w) {
    if (p_glFinish) p_glFinish();
    ANativeWindow *nw; pthread_mutex_lock(&g_winMu); nw = g_nativeWindow; if (nw) ANativeWindow_acquire(nw); pthread_mutex_unlock(&g_winMu);
    if (!nw) return;
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(nw, &buf, NULL) == 0) {
        int rows = buf.height < w->osmesaH ? buf.height : w->osmesaH, cols = buf.width < w->osmesaW ? buf.width : w->osmesaW;
        for (int y = 0; y < rows; y++) memcpy((uint8_t *)buf.bits + (size_t)y * buf.stride * 4, w->osmesaBuf + (size_t)y * w->osmesaW * 4, (size_t)cols * 4);
        ANativeWindow_unlockAndPost(nw);
    }
    ANativeWindow_release(nw);
}

CS_EXPORT void glfwSwapBuffers(void *wp) {
    CSWindow *w = wp; if (!w) return;
    if (g_ctxKind == CTX_OSMESA) blit_osmesa(w);
    else if (w->surface != EGL_NO_SURFACE && !p_eglSwapBuffers(g_dpy, w->surface)) LOGW("eglSwapBuffers failed: 0x%x", p_eglGetError());
    handle_surface_change(w);   /* pause/resume/rotation: recreate the surface on the game thread */
}
CS_EXPORT void glfwSwapInterval(int i) { if (t_current) { t_current->swapInterval = i; if (g_ctxKind != CTX_OSMESA) p_eglSwapInterval(g_dpy, i); } }
CS_EXPORT int glfwExtensionSupported(const char *ext) { return GLFW_FALSE; }
CS_EXPORT void *glfwGetProcAddress(const char *name) {
    void *p = NULL;
    if (g_ctxKind == CTX_OSMESA) return p_OSMesaGetProcAddress ? p_OSMesaGetProcAddress(name) : NULL;
    if (g_glLib) {   /* GL4ES exports desktop GL entry points and its own resolver */
        static void *(*gl4esProc)(const char *) = NULL; static int looked = 0;
        if (!looked) { gl4esProc = dlsym(g_glLib, "gl4es_GetProcAddress"); looked = 1; }
        p = gl4esProc ? gl4esProc(name) : NULL;
        if (!p) {   /* MobileGlues and GL4ES (Android.mk build) export the GLX resolver */
            static void *(*glxProc)(const char *) = NULL; static int lookedGlx = 0;
            if (!lookedGlx) { glxProc = dlsym(g_glLib, "glXGetProcAddress"); if (!glxProc) glxProc = dlsym(g_glLib, "glXGetProcAddressARB"); lookedGlx = 1; }
            if (glxProc) p = glxProc(name);
        }
        if (!p) p = dlsym(g_glLib, name);
    }
    if (!p && p_eglGetProcAddress) p = p_eglGetProcAddress(name);
    return p;
}

/* ----------------------------------------------------------------- Vulkan (not exposed through GLFW on Android) */
CS_EXPORT int glfwVulkanSupported(void) { return GLFW_FALSE; }
CS_EXPORT const char **glfwGetRequiredInstanceExtensions(uint32_t *count) { if (count) *count = 0; return NULL; }
CS_EXPORT void *glfwGetInstanceProcAddress(void *instance, const char *name) { return NULL; }
CS_EXPORT int glfwGetPhysicalDevicePresentationSupport(void *i, void *d, uint32_t q) { return GLFW_FALSE; }
CS_EXPORT int glfwCreateWindowSurface(void *i, void *w, const void *a, void *s) { glfw_error(GLFW_API_UNAVAILABLE, "Vulkan surfaces are not available"); return -7; /* VK_ERROR_EXTENSION_NOT_PRESENT */ }

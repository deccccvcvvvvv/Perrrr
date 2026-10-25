/* Host-test stand-ins for what libcsglfw.so normally gets from csglfw.c (window handoff, EGL backend) and
 * host_callbacks.c (calls into the Android activity). Every call is recorded in `mock` for the assertions. */
#include "cs_common.h"
#include "cs_egl.h"
#include "mock.h"
#include <dlfcn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct mock_state mock;
pthread_mutex_t g_winMu = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t g_winCv = PTHREAD_COND_INITIALIZER;
ANativeWindow *g_nativeWindow = (ANativeWindow *)0x1234;
int g_surfaceGeneration = 1, g_surfaceAckGeneration = 0;
int g_surfaceW = 2400, g_surfaceH = 1080, g_renderW = 1600, g_renderH = 720;
int g_ctxKind = CTX_EGL_DESKTOP;
void *g_glLib, *g_eglLib;
EGLDisplay g_dpy = (EGLDisplay)0x1;
EGLConfig g_cfg = (EGLConfig)0x2;

int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    if (!getenv("CS_TEST_VERBOSE") && prio < ANDROID_LOG_WARN) return 0;
    va_list ap; va_start(ap, fmt); fprintf(stderr, "  [%s] ", tag); vfprintf(stderr, fmt, ap); fputc('\n', stderr); va_end(ap); return 0;
}
int32_t ANativeWindow_getWidth(ANativeWindow *w) { return g_surfaceW; }
int32_t ANativeWindow_getHeight(ANativeWindow *w) { return g_surfaceH; }
int32_t ANativeWindow_setBuffersGeometry(ANativeWindow *w, int32_t a, int32_t b, int32_t f) { mock.geomW = a; mock.geomH = b; return 0; }
void ANativeWindow_release(ANativeWindow *w) {}
void ANativeWindow_acquire(ANativeWindow *w) {}

static EGLBoolean m_getConfigAttrib(EGLDisplay d, EGLConfig c, EGLint a, EGLint *v) { *v = 1; return EGL_TRUE; }
static EGLSurface m_createWindowSurface(EGLDisplay d, EGLConfig c, EGLNativeWindowType w, const EGLint *a) { mock.surfacesCreated++; mock.liveSurfaces++; return (EGLSurface)(intptr_t)(0x100 + mock.surfacesCreated); }
static EGLBoolean m_destroySurface(EGLDisplay d, EGLSurface s) { mock.liveSurfaces--; return EGL_TRUE; }
static EGLContext m_createContext(EGLDisplay d, EGLConfig c, EGLContext share, const EGLint *a) {
    mock.contextsCreated++; mock.lastShare = share; mock.ctxMajor = mock.ctxMinor = mock.ctxProfile = -1;
    for (int i = 0; a && a[i] != EGL_NONE; i += 2) {
        if (a[i] == 0x3098) mock.ctxMajor = a[i + 1]; if (a[i] == 0x30FB) mock.ctxMinor = a[i + 1]; if (a[i] == 0x30FD) mock.ctxProfile = a[i + 1];
    }
    return (EGLContext)(intptr_t)(0x200 + mock.contextsCreated);
}
static EGLBoolean m_destroyContext(EGLDisplay d, EGLContext c) { return EGL_TRUE; }
static EGLBoolean m_makeCurrent(EGLDisplay d, EGLSurface a, EGLSurface b, EGLContext c) { mock.curSurface = a; mock.curContext = c; return EGL_TRUE; }
static EGLBoolean m_swapBuffers(EGLDisplay d, EGLSurface s) { mock.swaps++; return EGL_TRUE; }
static EGLBoolean m_swapInterval(EGLDisplay d, EGLint i) { mock.swapInterval = i; return EGL_TRUE; }
static EGLint m_getError(void) { return 0x3000; }
static void *m_getProcAddress(const char *n) { return NULL; }

EGLDisplay (*p_eglGetDisplay)(EGLNativeDisplayType);
EGLBoolean (*p_eglInitialize)(EGLDisplay, EGLint *, EGLint *);
EGLBoolean (*p_eglChooseConfig)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
EGLBoolean (*p_eglGetConfigAttrib)(EGLDisplay, EGLConfig, EGLint, EGLint *) = m_getConfigAttrib;
EGLSurface (*p_eglCreateWindowSurface)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint *) = m_createWindowSurface;
EGLContext (*p_eglCreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint *) = m_createContext;
EGLBoolean (*p_eglMakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext) = m_makeCurrent;
EGLBoolean (*p_eglSwapBuffers)(EGLDisplay, EGLSurface) = m_swapBuffers;
EGLBoolean (*p_eglSwapInterval)(EGLDisplay, EGLint) = m_swapInterval;
EGLBoolean (*p_eglDestroySurface)(EGLDisplay, EGLSurface) = m_destroySurface;
EGLBoolean (*p_eglDestroyContext)(EGLDisplay, EGLContext) = m_destroyContext;
EGLBoolean (*p_eglBindAPI)(EGLenum);
EGLint (*p_eglGetError)(void) = m_getError;
void *(*p_eglGetProcAddress)(const char *) = m_getProcAddress;

int cs_load_backend(void) {
    if (g_glLib) return 1;
    const char *p = getenv("CS_GL_LIB");
    g_glLib = p ? dlopen(p, RTLD_NOW | RTLD_GLOBAL) : NULL;
    return g_glLib != NULL;
}
ANativeWindow *cs_wait_window(void) { return g_nativeWindow; }

void cs_host_cursor_mode(int grabbed) { mock.cursorModeCalls++; mock.cursorGrabbed = grabbed; }
void cs_host_cursor_pos(double x, double y) { mock.cursorPosCalls++; }
void cs_host_window_ready(int w, int h) { mock.readyW = w; mock.readyH = h; }
void cs_host_jvm_exit(int code) {}
void cs_host_log_line(const char *l) {}
void cs_host_set_clipboard(const char *s) { snprintf(mock.clipboard, sizeof mock.clipboard, "%s", s); }
char *cs_host_get_clipboard(void) { return strdup(mock.clipboard); }
int cs_host_open_url(const char *u) { return u && strncmp(u, "https://", 8) == 0; }

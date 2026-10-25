/*
 * Clean in-game recording: every presented game frame is copied (glBlitFramebuffer) from the game's own EGL window
 * surface into a second EGL surface made from the video encoder's input Surface, right before eglSwapBuffers. The touch
 * controls, the game menu and the recording indicator are Android views drawn by the system compositor on top of the
 * game surface, so they never reach the encoder: the video looks like a desktop capture.
 *
 * Threading: the Java side (RecorderService, same :game process) only swaps the target window under g_recMu and waits
 * briefly for the game thread to adopt it; all EGL/GL work happens on the game's render thread inside cs_record_frame().
 * If anything in the chain is unsupported (GLES2 context, OSMesa, blit error) the status turns to -1 and the service
 * falls back to MediaProjection capture.
 */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <string.h>
#include <time.h>
#include "cs_common.h"
#include "cs_egl.h"


typedef unsigned int GLenum_; typedef int GLint_; typedef unsigned int GLuint_; typedef unsigned char GLboolean_; typedef unsigned int GLbitfield_;
#define GL_READ_FRAMEBUFFER_ 0x8CA8
#define GL_DRAW_FRAMEBUFFER_ 0x8CA9
#define GL_READ_FRAMEBUFFER_BINDING_ 0x8CAA
#define GL_DRAW_FRAMEBUFFER_BINDING_ 0x8CA6
#define GL_READ_BUFFER_ 0x0C02
#define GL_BACK_ 0x0405
#define GL_SCISSOR_TEST_ 0x0C11
#define GL_COLOR_BUFFER_BIT_ 0x4000
#define GL_LINEAR_ 0x2601
#define GL_VERSION_ 0x1F02
#define GL_NO_ERROR_ 0

static pthread_mutex_t g_recMu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_recCv = PTHREAD_COND_INITIALIZER;
static ANativeWindow *g_recWin = NULL;        /* adopted target (owned reference) */
static ANativeWindow *g_recPending = NULL;    /* requested target, adopted by the game thread (owned reference) */
static volatile int g_recGen = 0, g_recAck = 0;
static EGLSurface g_recSurf = EGL_NO_SURFACE;
static volatile int g_recStatus = 0;          /* 0 waiting for a frame, 1 frames delivered, -1 unsupported/failed */
static long long g_recIntervalNs = 33333333LL, g_recLastNs = 0;
static int g_recFirst = 1;

static EGLContext (*p_getCurCtx)(void);
static EGLSurface (*p_getCurSurf)(EGLint);
static EGLBoolean (*p_querySurf)(EGLDisplay, EGLSurface, EGLint, EGLint *);
static void (*gl_bindFb)(GLenum_, GLuint_);
static void (*gl_blit)(GLint_, GLint_, GLint_, GLint_, GLint_, GLint_, GLint_, GLint_, GLbitfield_, GLenum_);
static void (*gl_getIntegerv)(GLenum_, GLint_ *);
static void (*gl_readBuffer)(GLenum_);
static GLboolean_ (*gl_isEnabled)(GLenum_);
static void (*gl_enable)(GLenum_);
static void (*gl_disable)(GLenum_);
static GLenum_ (*gl_getError)(void);
static const unsigned char *(*gl_getString)(GLenum_);
static int g_procsLoaded = 0;

static long long now_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (long long)t.tv_sec * 1000000000LL + t.tv_nsec; }

static int load_procs(void) {
    if (g_procsLoaded) return g_procsLoaded > 0;
    g_procsLoaded = -1;
    if (!g_eglLib || !p_eglGetProcAddress) return 0;
    p_getCurCtx = dlsym(g_eglLib, "eglGetCurrentContext");
    p_getCurSurf = dlsym(g_eglLib, "eglGetCurrentSurface");
    p_querySurf = dlsym(g_eglLib, "eglQuerySurface");
#define GLP(var, name) var = (void *)p_eglGetProcAddress(name)
    GLP(gl_bindFb, "glBindFramebuffer"); GLP(gl_blit, "glBlitFramebuffer"); GLP(gl_getIntegerv, "glGetIntegerv"); GLP(gl_readBuffer, "glReadBuffer");
    GLP(gl_isEnabled, "glIsEnabled"); GLP(gl_enable, "glEnable"); GLP(gl_disable, "glDisable"); GLP(gl_getError, "glGetError"); GLP(gl_getString, "glGetString");
#undef GLP
    if (!p_getCurCtx || !p_getCurSurf || !p_querySurf || !gl_bindFb || !gl_blit || !gl_getIntegerv || !gl_readBuffer || !gl_isEnabled || !gl_enable
        || !gl_disable || !gl_getError || !gl_getString) { LOGW("clean recording unavailable: missing EGL/GL entry points"); return 0; }
    g_procsLoaded = 1;
    return 1;
}

/* Drops the current target surface/window. Caller holds g_recMu; the surface must not be current anywhere. */
static void drop_target(void) {
    if (g_recSurf != EGL_NO_SURFACE && g_dpy != EGL_NO_DISPLAY && p_eglDestroySurface) p_eglDestroySurface(g_dpy, g_recSurf);
    g_recSurf = EGL_NO_SURFACE;
    if (g_recWin) ANativeWindow_release(g_recWin);
    g_recWin = NULL;
}

static void adopt_pending(void) {
    drop_target();
    g_recWin = g_recPending; g_recPending = NULL;
    g_recStatus = 0; g_recFirst = 1; g_recLastNs = 0;
    g_recAck = g_recGen;
    pthread_cond_broadcast(&g_recCv);
}

static void fail(const char *why, EGLint err) {
    LOGW("clean recording disabled: %s (0x%x)", why, err);
    g_recStatus = -1;
}

/* Called on the game's render thread right before eglSwapBuffers of the game surface. */
void cs_record_frame(void) {
    if (g_recGen == g_recAck && g_recWin == NULL) return;          /* fast path: not recording */
    pthread_mutex_lock(&g_recMu);
    if (g_recGen != g_recAck) adopt_pending();
    if (!g_recWin || g_recStatus < 0) { pthread_mutex_unlock(&g_recMu); return; }
    long long now = now_ns();
    if (g_recLastNs && now - g_recLastNs < g_recIntervalNs - 2000000LL) { pthread_mutex_unlock(&g_recMu); return; }   /* encoder frame-rate cap */
    if (g_ctxKind == CTX_OSMESA) { fail("software renderer has no EGL surface", 0); pthread_mutex_unlock(&g_recMu); return; }
    if (!load_procs()) { g_recStatus = -1; pthread_mutex_unlock(&g_recMu); return; }

    EGLContext ctx = p_getCurCtx();
    EGLSurface draw = p_getCurSurf(EGL_DRAW), read = p_getCurSurf(EGL_READ);
    if (ctx == EGL_NO_CONTEXT || draw == EGL_NO_SURFACE) { pthread_mutex_unlock(&g_recMu); return; }
    if (g_recFirst) {
        const char *ver = (const char *)gl_getString(GL_VERSION_);
        if (ver && strstr(ver, "OpenGL ES 2")) { fail("GLES 2 context cannot blit between surfaces", 0); pthread_mutex_unlock(&g_recMu); return; }
    }
    if (g_recSurf == EGL_NO_SURFACE) {
        g_recSurf = p_eglCreateWindowSurface(g_dpy, g_cfg, (EGLNativeWindowType)g_recWin, NULL);
        if (g_recSurf == EGL_NO_SURFACE) { fail("eglCreateWindowSurface for the encoder failed", p_eglGetError()); pthread_mutex_unlock(&g_recMu); return; }
    }
    EGLint gw = 0, gh = 0;
    p_querySurf(g_dpy, draw, EGL_WIDTH, &gw); p_querySurf(g_dpy, draw, EGL_HEIGHT, &gh);
    int rw = ANativeWindow_getWidth(g_recWin), rh = ANativeWindow_getHeight(g_recWin);
    if (gw <= 0 || gh <= 0 || rw <= 0 || rh <= 0) { pthread_mutex_unlock(&g_recMu); return; }

    GLint_ drawFb = 0, readFb = 0, readBuf = GL_BACK_;
    gl_getIntegerv(GL_DRAW_FRAMEBUFFER_BINDING_, &drawFb); gl_getIntegerv(GL_READ_FRAMEBUFFER_BINDING_, &readFb); gl_getIntegerv(GL_READ_BUFFER_, &readBuf);
    GLboolean_ scissor = gl_isEnabled(GL_SCISSOR_TEST_);
    while (gl_getError() != GL_NO_ERROR_) {}

    /* Draw into the encoder surface, read from the game's back buffer. */
    if (!p_eglMakeCurrent(g_dpy, g_recSurf, draw, ctx)) {
        fail("eglMakeCurrent(encoder, game) failed", p_eglGetError());
        pthread_mutex_unlock(&g_recMu); return;
    }
    if (g_recFirst) p_eglSwapInterval(g_dpy, 0);   /* never block the game on the encoder */
    gl_bindFb(GL_READ_FRAMEBUFFER_, 0); gl_bindFb(GL_DRAW_FRAMEBUFFER_, 0);
    gl_readBuffer(GL_BACK_);
    if (scissor) gl_disable(GL_SCISSOR_TEST_);
    gl_blit(0, 0, gw, gh, 0, 0, rw, rh, GL_COLOR_BUFFER_BIT_, GL_LINEAR_);
    GLenum_ err = gl_getError();
    EGLBoolean swapped = err == GL_NO_ERROR_ ? p_eglSwapBuffers(g_dpy, g_recSurf) : EGL_FALSE;
    EGLint swapErr = swapped ? EGL_SUCCESS : p_eglGetError();

    /* Back to the game's own surfaces and GL state, exactly as before. */
    if (!p_eglMakeCurrent(g_dpy, draw, read, ctx)) LOGW("restoring the game surface failed: 0x%x", p_eglGetError());
    gl_bindFb(GL_READ_FRAMEBUFFER_, (GLuint_)readFb);
    gl_readBuffer((GLenum_)readBuf);
    gl_bindFb(GL_DRAW_FRAMEBUFFER_, (GLuint_)drawFb);
    if (scissor) gl_enable(GL_SCISSOR_TEST_);
    while (gl_getError() != GL_NO_ERROR_) {}

    if (err != GL_NO_ERROR_) fail("glBlitFramebuffer failed", (EGLint)err);
    else if (!swapped) {
        /* The encoder may have been stopped between frames; only a persistent failure disables the feature. */
        if (g_recStatus == 0) fail("eglSwapBuffers to the encoder failed", swapErr);
    } else {
        if (g_recStatus == 0) LOGI("clean recording active: %dx%d -> %dx%d", gw, gh, rw, rh);
        g_recStatus = 1;
    }
    g_recFirst = 0; g_recLastNs = now;
    pthread_mutex_unlock(&g_recMu);
}

/* Java: sets (or clears with null) the encoder Surface. Waits up to 400 ms for the game thread to adopt it; if the game is
 * not rendering (paused, loading) the change is applied here, which is safe because the surface is never current then. */
CS_EXPORT JNIEXPORT void JNICALL Java_pro_cslauncher_app_game_NativeBridge_setRecordTarget(JNIEnv *env, jclass c, jobject surface, jint fps) {
    ANativeWindow *w = surface ? ANativeWindow_fromSurface(env, surface) : NULL;   /* acquired */
    pthread_mutex_lock(&g_recMu);
    if (g_recPending) ANativeWindow_release(g_recPending);
    g_recPending = w;
    g_recIntervalNs = 1000000000LL / (fps > 0 && fps <= 240 ? fps : 30);
    int gen = ++g_recGen;
    struct timespec until; clock_gettime(CLOCK_REALTIME, &until);
    until.tv_nsec += 400 * 1000000L; if (until.tv_nsec >= 1000000000L) { until.tv_sec++; until.tv_nsec -= 1000000000L; }
    while (g_recAck != gen) if (pthread_cond_timedwait(&g_recCv, &g_recMu, &until) == ETIMEDOUT) break;
    if (g_recAck != gen) adopt_pending();
    pthread_mutex_unlock(&g_recMu);
}

/* 0 = no frame delivered yet, 1 = frames are reaching the encoder, -1 = not supported here (use screen capture). */
CS_EXPORT JNIEXPORT jint JNICALL Java_pro_cslauncher_app_game_NativeBridge_recordStatus(JNIEnv *env, jclass c) { return g_recStatus; }

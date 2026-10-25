#pragma once
/*
 * State shared (inside libcsglfw.so, hidden visibility) between the GLFW 3.4 implementation (csglfw.c, Minecraft up to
 * 26.2) and the SDL3 implementation (cssdl3.c, Minecraft 26.3+). Both drive the same Android surface handed over by
 * GameActivity and the same EGL backend selected by the launcher (CS_CONTEXT / CS_GL_LIB / CS_EGL_LIB).
 */
#include <EGL/egl.h>
#include <android/native_window.h>
#include <pthread.h>

enum { CTX_EGL_GLES2, CTX_EGL_GLES3, CTX_OSMESA, CTX_EGL_DESKTOP };

/* Android window handoff (csglfw.c). Guarded by g_winMu; g_winCv is signalled on every change. */
extern pthread_mutex_t g_winMu;
extern pthread_cond_t g_winCv;
extern ANativeWindow *g_nativeWindow;
extern int g_surfaceGeneration, g_surfaceAckGeneration;
extern int g_surfaceW, g_surfaceH, g_renderW, g_renderH;

/* EGL backend (csglfw.c) */
extern int g_ctxKind;
extern void *g_glLib, *g_eglLib;
extern EGLDisplay g_dpy;
extern EGLConfig g_cfg;
extern EGLDisplay (*p_eglGetDisplay)(EGLNativeDisplayType);
extern EGLBoolean (*p_eglInitialize)(EGLDisplay, EGLint *, EGLint *);
extern EGLBoolean (*p_eglChooseConfig)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
extern EGLBoolean (*p_eglGetConfigAttrib)(EGLDisplay, EGLConfig, EGLint, EGLint *);
extern EGLSurface (*p_eglCreateWindowSurface)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint *);
extern EGLContext (*p_eglCreateContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
extern EGLBoolean (*p_eglMakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
extern EGLBoolean (*p_eglSwapBuffers)(EGLDisplay, EGLSurface);
extern EGLBoolean (*p_eglSwapInterval)(EGLDisplay, EGLint);
extern EGLBoolean (*p_eglDestroySurface)(EGLDisplay, EGLSurface);
extern EGLBoolean (*p_eglDestroyContext)(EGLDisplay, EGLContext);
extern EGLBoolean (*p_eglBindAPI)(EGLenum);
extern EGLint (*p_eglGetError)(void);
extern void *(*p_eglGetProcAddress)(const char *);

int cs_load_backend(void);              /* idempotent; 1 on success */
ANativeWindow *cs_wait_window(void);    /* blocks until the activity provides a window; returns an acquired reference */

/* cssdl3.c: whether the SDL3 bridge currently renders into an EGL surface of the Android window
 * (releaseSurface waits for it to let go, exactly as for the GLFW window). */
int cs_sdl_holds_surface(void);

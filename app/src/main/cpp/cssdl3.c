/*
 * CS Launcher Pro — original implementation of the SDL3 C API subset Minecraft 26.3+ uses, for Android.
 *
 * Minecraft 26.3 replaced GLFW with SDL3 (through LWJGL 3.4.3's org.lwjgl.sdl bindings). Upstream SDL3 cannot be used
 * inside the launcher: its Android port expects to own the Activity (SDLActivity) and binds to the app's ART VM, while
 * the game runs in a separate OpenJDK VM inside this process, and LWJGL's SDL build has no Android video driver.
 * This file implements the API on the launcher's own window/EGL/input plumbing (shared with csglfw.c through
 * cs_egl.h). LWJGL resolves every function of an SDL binding class at class initialisation, so the rest of the public
 * API is exported by cssdl3_stubs.c (generated) and fails cleanly with SDL_GetError() if ever called.
 *
 * Only SDL's public headers (zlib licence, third_party/SDL3) are used, for exact type and struct layouts.
 *
 * Design notes
 *  - Every SDL window renders into ONE shared EGL window surface on the Android surface. Minecraft creates a hidden
 *    "utility" window to own its GL context and a short-lived test window before the real game window; they never
 *    present, so sharing the surface avoids pbuffers/surfaceless contexts that some drivers lack.
 *  - SDL_GL_GetProcAddress resolves exactly like LWJGL's GL function provider (glXGetProcAddress, ARB, egl, OSMesa,
 *    then dlsym on the same library): Minecraft refuses the OpenGL backend if the two disagree on glGetError.
 *  - Input arrives through the same queue as for GLFW (GLFW key codes from the touch controls / keyboards) and is
 *    translated to SDL scancodes, keycodes and SDL3 event structs here.
 */
#include "cs_common.h"
#include "cs_egl.h"
#include <SDL3/SDL.h>
#include <SDL3/SDL_vulkan.h>
#include <dlfcn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define SDL_EXPORT __attribute__((visibility("default")))

/* ================================================================================================ errors */
static __thread char t_error[512];
SDL_EXPORT bool SDL_SetError(const char *fmt, ...) {
    if (!fmt) { t_error[0] = 0; return false; }
    va_list ap; va_start(ap, fmt); vsnprintf(t_error, sizeof t_error, fmt, ap); va_end(ap);
    return false;
}
SDL_EXPORT bool SDL_SetErrorV(const char *fmt, va_list ap) { if (fmt) vsnprintf(t_error, sizeof t_error, fmt, ap); else t_error[0] = 0; return false; }
SDL_EXPORT const char *SDL_GetError(void) { return t_error; }
SDL_EXPORT bool SDL_ClearError(void) { t_error[0] = 0; return true; }
SDL_EXPORT bool SDL_OutOfMemory(void) { return SDL_SetError("Out of memory"); }

/* Called by every generated stub (cssdl3_stubs.c). Logged once per function so unexpected use shows up in logcat. */
CS_EXPORT long long cs_sdl_unsupported(const char *name) {
    static const char *seen[64]; static int nseen = 0;
    int known = 0; for (int i = 0; i < nseen; i++) if (seen[i] == name) { known = 1; break; }
    if (!known) { if (nseen < 64) seen[nseen++] = name; LOGW("SDL3 bridge: %s is not supported on Android", name); }
    SDL_SetError("%s is not supported by the CS Launcher Android SDL3 bridge", name);
    return 0;
}

/* ================================================================================================ memory / time / misc */
/* SDL_malloc/SDL_free/... live in cssdl3_libc.c: SDL_stdinc.h turns several of them into macros for libc names. */

static Uint64 now_ns(void) { struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return (Uint64)ts.tv_sec * 1000000000ULL + (Uint64)ts.tv_nsec; }
static Uint64 g_startNs = 0;
SDL_EXPORT Uint64 SDL_GetTicksNS(void) { if (!g_startNs) g_startNs = now_ns(); return now_ns() - g_startNs; }
SDL_EXPORT Uint64 SDL_GetTicks(void) { return SDL_GetTicksNS() / 1000000ULL; }
SDL_EXPORT Uint64 SDL_GetPerformanceCounter(void) { return now_ns(); }
SDL_EXPORT Uint64 SDL_GetPerformanceFrequency(void) { return 1000000000ULL; }
SDL_EXPORT void SDL_Delay(Uint32 ms) { usleep((useconds_t)ms * 1000); }
SDL_EXPORT void SDL_DelayNS(Uint64 ns) { struct timespec ts = { (time_t)(ns / 1000000000ULL), (long)(ns % 1000000000ULL) }; nanosleep(&ts, NULL); }
SDL_EXPORT void SDL_DelayPrecise(Uint64 ns) { SDL_DelayNS(ns); }

SDL_EXPORT int SDL_GetVersion(void) { return SDL_VERSIONNUM(SDL_MAJOR_VERSION, SDL_MINOR_VERSION, SDL_MICRO_VERSION); }
SDL_EXPORT const char *SDL_GetRevision(void) { return "CS Launcher Pro SDL3 bridge (Android)"; }
SDL_EXPORT const char *SDL_GetPlatform(void) { return "Android"; }

/* Hints and app metadata: stored so SDL_GetHint round-trips; none changes behaviour on Android. */
#define MAX_HINTS 48
static struct { char name[64]; char value[128]; } g_hints[MAX_HINTS]; static int g_nhints = 0;
static bool set_kv(const char *name, const char *value) {
    if (!name) return SDL_SetError("Parameter 'name' is invalid");
    for (int i = 0; i < g_nhints; i++) if (strcmp(g_hints[i].name, name) == 0) { snprintf(g_hints[i].value, sizeof g_hints[i].value, "%s", value ? value : ""); return true; }
    if (g_nhints == MAX_HINTS) return true;
    snprintf(g_hints[g_nhints].name, sizeof g_hints[0].name, "%s", name); snprintf(g_hints[g_nhints].value, sizeof g_hints[0].value, "%s", value ? value : "");
    g_nhints++; return true;
}
SDL_EXPORT bool SDL_SetHint(const char *name, const char *value) { return set_kv(name, value); }
SDL_EXPORT bool SDL_SetHintWithPriority(const char *name, const char *value, SDL_HintPriority p) { return set_kv(name, value); }
SDL_EXPORT const char *SDL_GetHint(const char *name) { for (int i = 0; i < g_nhints && name; i++) if (strcmp(g_hints[i].name, name) == 0) return g_hints[i].value; return NULL; }
SDL_EXPORT bool SDL_GetHintBoolean(const char *name, bool def) { const char *v = SDL_GetHint(name); return v && *v ? !(strcmp(v, "0") == 0 || strcmp(v, "false") == 0) : def; }
SDL_EXPORT bool SDL_ResetHint(const char *name) { return true; }
SDL_EXPORT void SDL_ResetHints(void) { g_nhints = 0; }
SDL_EXPORT bool SDL_SetAppMetadata(const char *n, const char *v, const char *id) { return true; }
SDL_EXPORT bool SDL_SetAppMetadataProperty(const char *name, const char *value) { return set_kv(name, value); }
SDL_EXPORT const char *SDL_GetAppMetadataProperty(const char *name) { return SDL_GetHint(name); }

/* Logging: SDL's own messages go to the callback Minecraft installs (or logcat). */
static SDL_LogOutputFunction g_logFn = NULL; static void *g_logUser = NULL;
SDL_EXPORT void SDL_SetLogOutputFunction(SDL_LogOutputFunction cb, void *u) { g_logFn = cb; g_logUser = u; }
SDL_EXPORT void SDL_GetLogOutputFunction(SDL_LogOutputFunction *cb, void **u) { if (cb) *cb = g_logFn; if (u) *u = g_logUser; }
SDL_EXPORT SDL_LogOutputFunction SDL_GetDefaultLogOutputFunction(void) { return NULL; }
SDL_EXPORT void SDL_SetLogPriorities(SDL_LogPriority p) {}
SDL_EXPORT void SDL_SetLogPriority(int c, SDL_LogPriority p) {}
SDL_EXPORT SDL_LogPriority SDL_GetLogPriority(int c) { return SDL_LOG_PRIORITY_INFO; }
SDL_EXPORT void SDL_ResetLogPriorities(void) {}
static void sdl_log(SDL_LogPriority pri, const char *fmt, ...) {
    char buf[512]; va_list ap; va_start(ap, fmt); vsnprintf(buf, sizeof buf, fmt, ap); va_end(ap);
    if (g_logFn) g_logFn(g_logUser, SDL_LOG_CATEGORY_VIDEO, pri, buf); else LOGI("SDL: %s", buf);
}
SDL_EXPORT void SDL_LogMessageV(int cat, SDL_LogPriority pri, const char *fmt, va_list ap) {
    char buf[1024]; if (fmt) vsnprintf(buf, sizeof buf, fmt, ap); else buf[0] = 0;
    if (g_logFn) g_logFn(g_logUser, cat, pri, buf); else LOGI("SDL: %s", buf);
}
SDL_EXPORT void SDL_LogMessage(int cat, SDL_LogPriority pri, const char *fmt, ...) { va_list ap; va_start(ap, fmt); SDL_LogMessageV(cat, pri, fmt, ap); va_end(ap); }
SDL_EXPORT void SDL_Log(const char *fmt, ...) { va_list ap; va_start(ap, fmt); SDL_LogMessageV(SDL_LOG_CATEGORY_APPLICATION, SDL_LOG_PRIORITY_INFO, fmt, ap); va_end(ap); }

/* ================================================================================================ init */
static SDL_InitFlags g_inited = 0;
SDL_EXPORT bool SDL_Init(SDL_InitFlags flags) { g_inited |= flags; if (!g_startNs) g_startNs = now_ns(); return true; }
SDL_EXPORT bool SDL_InitSubSystem(SDL_InitFlags flags) { return SDL_Init(flags); }
SDL_EXPORT void SDL_QuitSubSystem(SDL_InitFlags flags) { g_inited &= ~flags; }
SDL_EXPORT SDL_InitFlags SDL_WasInit(SDL_InitFlags flags) { return flags ? (g_inited & flags) : g_inited; }
SDL_EXPORT void SDL_Quit(void) { g_inited = 0; }
SDL_EXPORT bool SDL_IsMainThread(void) { return true; }

/* ================================================================================================ windows + surface */
struct SDL_Window {
    SDL_WindowID id; SDL_WindowFlags flags; int w, h; int game; int fullscreen;
    int minW, minH, maxW, maxH; bool relative, grab; char title[256];
};
struct SDL_GLContextState { EGLContext ctx; int major, minor; };
typedef struct SDL_GLContextState SDL_GLContextState;
struct SDL_Cursor { int shape; };

#define MAX_WINDOWS 16
static SDL_Window *g_windows[MAX_WINDOWS];
static SDL_Window *g_gameWindow = NULL;
static SDL_WindowID g_nextWindowId = 1;
static EGLSurface g_surface = EGL_NO_SURFACE;     /* shared by every window (see design notes) */
static int g_surfaceRefs = 0, g_surfW = 0, g_surfH = 0;
static __thread SDL_Window *t_curWindow = NULL;
static __thread SDL_GLContextState *t_curCtx = NULL;
static int g_swapInterval = 1;

int cs_sdl_holds_surface(void) { return g_surface != EGL_NO_SURFACE; }

static void push_window_event(SDL_EventType type, SDL_Window *w, int d1, int d2);

static int create_shared_surface(void) {
    ANativeWindow *nw = cs_wait_window();
    pthread_mutex_lock(&g_winMu); int gen = g_surfaceGeneration, rw = g_renderW, rh = g_renderH; pthread_mutex_unlock(&g_winMu);
    if (rw <= 0 || rh <= 0) { rw = ANativeWindow_getWidth(nw); rh = ANativeWindow_getHeight(nw); }
    EGLint fmt = 0; p_eglGetConfigAttrib(g_dpy, g_cfg, EGL_NATIVE_VISUAL_ID, &fmt);
    ANativeWindow_setBuffersGeometry(nw, rw, rh, fmt);   /* render scale: the hardware scaler upsamples */
    g_surface = p_eglCreateWindowSurface(g_dpy, g_cfg, (EGLNativeWindowType)nw, NULL);
    ANativeWindow_release(nw);
    if (g_surface == EGL_NO_SURFACE) return SDL_SetError("eglCreateWindowSurface failed (0x%x)", p_eglGetError());
    g_surfW = rw; g_surfH = rh;
    pthread_mutex_lock(&g_winMu); g_surfaceAckGeneration = gen; pthread_cond_broadcast(&g_winCv); pthread_mutex_unlock(&g_winMu);
    LOGI("SDL3 bridge: surface %dx%d", rw, rh);
    return 1;
}

static void destroy_shared_surface(void) {
    if (g_surface != EGL_NO_SURFACE) {
        p_eglMakeCurrent(g_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        p_eglDestroySurface(g_dpy, g_surface); g_surface = EGL_NO_SURFACE;
    }
    pthread_mutex_lock(&g_winMu); g_surfaceAckGeneration = g_surfaceGeneration; pthread_cond_broadcast(&g_winCv); pthread_mutex_unlock(&g_winMu);
}

static void bind_current(void) {
    if (t_curCtx && g_surface != EGL_NO_SURFACE) {
        if (!p_eglMakeCurrent(g_dpy, g_surface, g_surface, t_curCtx->ctx)) LOGW("eglMakeCurrent failed: 0x%x", p_eglGetError());
        p_eglSwapInterval(g_dpy, g_swapInterval);
    }
}

/* Pause/resume/rotation: the activity replaced or removed its surface. Runs on the game thread. */
static void handle_surface_change(void) {
    if (g_surface == EGL_NO_SURFACE) return;
    pthread_mutex_lock(&g_winMu); int gen = g_surfaceGeneration; pthread_mutex_unlock(&g_winMu);
    if (gen == g_surfaceAckGeneration) return;
    LOGI("SDL3 bridge: surface changed (generation %d)", gen);
    int oldW = g_surfW, oldH = g_surfH;
    destroy_shared_surface();
    if (!create_shared_surface()) { LOGE("%s", t_error); return; }
    bind_current();
    if (g_gameWindow && (oldW != g_surfW || oldH != g_surfH)) {
        g_gameWindow->w = g_surfW; g_gameWindow->h = g_surfH;
        push_window_event(SDL_EVENT_WINDOW_RESIZED, g_gameWindow, g_surfW, g_surfH);
        push_window_event(SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED, g_gameWindow, g_surfW, g_surfH);
    }
}

static SDL_Window *window_by_id(SDL_WindowID id) { for (int i = 0; i < MAX_WINDOWS; i++) if (g_windows[i] && g_windows[i]->id == id) return g_windows[i]; return NULL; }

SDL_EXPORT SDL_Window *SDL_CreateWindow(const char *title, int w, int h, SDL_WindowFlags flags) {
    if (flags & SDL_WINDOW_VULKAN) { SDL_SetError("Vulkan windows are not available on Android in CS Launcher; use OpenGL"); return NULL; }
    if (flags & SDL_WINDOW_METAL) { SDL_SetError("Metal is not available"); return NULL; }
    int slot = -1; for (int i = 0; i < MAX_WINDOWS; i++) if (!g_windows[i]) { slot = i; break; }
    if (slot < 0) { SDL_SetError("Too many windows"); return NULL; }
    if (!cs_load_backend()) { SDL_SetError("The renderer's EGL backend could not be initialised (see launcher log)"); return NULL; }
    if (g_ctxKind == CTX_OSMESA) { SDL_SetError("The selected renderer (OSMesa) is not supported for Minecraft 26.3+; choose MobileGlues"); return NULL; }
    if (g_surface == EGL_NO_SURFACE && !create_shared_surface()) return NULL;
    SDL_Window *win = calloc(1, sizeof *win);
    win->id = g_nextWindowId++; win->flags = flags;
    snprintf(win->title, sizeof win->title, "%s", title ? title : "");
    /* Hidden utility windows (Minecraft's GL context owner / test windows) keep their requested size; the game window is
     * the Android surface. */
    win->game = !(flags & SDL_WINDOW_UTILITY) && !g_gameWindow;
    win->w = win->game ? g_surfW : (w > 0 ? w : 1); win->h = win->game ? g_surfH : (h > 0 ? h : 1);
    g_windows[slot] = win; g_surfaceRefs++;
    if (win->game) {
        g_gameWindow = win;
        win->flags = (flags & ~(SDL_WindowFlags)SDL_WINDOW_HIDDEN) | SDL_WINDOW_INPUT_FOCUS | SDL_WINDOW_MOUSE_FOCUS;
        cs_host_window_ready(win->w, win->h);
        push_window_event(SDL_EVENT_WINDOW_SHOWN, win, 0, 0);
        push_window_event(SDL_EVENT_WINDOW_RESIZED, win, win->w, win->h);
        push_window_event(SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED, win, win->w, win->h);
        push_window_event(SDL_EVENT_WINDOW_FOCUS_GAINED, win, 0, 0);
    }
    LOGI("SDL3 bridge: window %u '%s' %dx%d flags 0x%llx%s", win->id, win->title, win->w, win->h, (unsigned long long)flags, win->game ? " (game)" : "");
    return win;
}
SDL_EXPORT SDL_Window *SDL_CreateWindowWithProperties(SDL_PropertiesID props) { SDL_SetError("SDL_CreateWindowWithProperties is not supported; use SDL_CreateWindow"); return NULL; }

SDL_EXPORT void SDL_DestroyWindow(SDL_Window *win) {
    if (!win) return;
    for (int i = 0; i < MAX_WINDOWS; i++) if (g_windows[i] == win) g_windows[i] = NULL;
    if (t_curWindow == win) t_curWindow = NULL;
    if (g_gameWindow == win) g_gameWindow = NULL;
    if (--g_surfaceRefs <= 0) { g_surfaceRefs = 0; destroy_shared_surface(); }
    free(win);
}

SDL_EXPORT SDL_WindowID SDL_GetWindowID(SDL_Window *w) { return w ? w->id : 0; }
SDL_EXPORT SDL_Window *SDL_GetWindowFromID(SDL_WindowID id) { return window_by_id(id); }
SDL_EXPORT SDL_WindowFlags SDL_GetWindowFlags(SDL_Window *w) {
    if (!w) return 0;
    SDL_WindowFlags f = w->flags | (w->fullscreen ? SDL_WINDOW_FULLSCREEN : 0);
    if (w->relative) f |= SDL_WINDOW_MOUSE_RELATIVE_MODE;
    if (w->grab) f |= SDL_WINDOW_MOUSE_GRABBED;
    return f;
}
SDL_EXPORT bool SDL_SetWindowTitle(SDL_Window *w, const char *t) { if (w) snprintf(w->title, sizeof w->title, "%s", t ? t : ""); return true; }
SDL_EXPORT const char *SDL_GetWindowTitle(SDL_Window *w) { return w ? w->title : ""; }
SDL_EXPORT bool SDL_SetWindowIcon(SDL_Window *w, SDL_Surface *icon) { return true; }
SDL_EXPORT bool SDL_SetWindowPosition(SDL_Window *w, int x, int y) { return true; }
SDL_EXPORT bool SDL_GetWindowPosition(SDL_Window *w, int *x, int *y) { if (x) *x = 0; if (y) *y = 0; return true; }
/* The game window always covers the display; requests to resize it are accepted and the real size is reported. */
SDL_EXPORT bool SDL_SetWindowSize(SDL_Window *w, int wd, int ht) { if (w && !w->game) { w->w = wd; w->h = ht; } return true; }
SDL_EXPORT bool SDL_GetWindowSize(SDL_Window *w, int *wd, int *ht) { if (wd) *wd = w ? w->w : 0; if (ht) *ht = w ? w->h : 0; return w != NULL || SDL_SetError("Invalid window"); }
SDL_EXPORT bool SDL_GetWindowSizeInPixels(SDL_Window *w, int *wd, int *ht) { return SDL_GetWindowSize(w, wd, ht); }
SDL_EXPORT bool SDL_GetWindowSafeArea(SDL_Window *w, SDL_Rect *r) { if (r) { r->x = 0; r->y = 0; r->w = w ? w->w : 0; r->h = w ? w->h : 0; } return true; }
SDL_EXPORT bool SDL_GetWindowBordersSize(SDL_Window *w, int *t, int *l, int *b, int *r) { if (t) *t = 0; if (l) *l = 0; if (b) *b = 0; if (r) *r = 0; return true; }
SDL_EXPORT float SDL_GetWindowPixelDensity(SDL_Window *w) { return 1.0f; }
SDL_EXPORT float SDL_GetWindowDisplayScale(SDL_Window *w) { return 1.0f; }
SDL_EXPORT bool SDL_SetWindowMinimumSize(SDL_Window *w, int a, int b) { if (w) { w->minW = a; w->minH = b; } return true; }
SDL_EXPORT bool SDL_GetWindowMinimumSize(SDL_Window *w, int *a, int *b) { if (a) *a = w ? w->minW : 0; if (b) *b = w ? w->minH : 0; return true; }
SDL_EXPORT bool SDL_SetWindowMaximumSize(SDL_Window *w, int a, int b) { if (w) { w->maxW = a; w->maxH = b; } return true; }
SDL_EXPORT bool SDL_GetWindowMaximumSize(SDL_Window *w, int *a, int *b) { if (a) *a = w ? w->maxW : 0; if (b) *b = w ? w->maxH : 0; return true; }
SDL_EXPORT bool SDL_SetWindowBordered(SDL_Window *w, bool b) { return true; }
SDL_EXPORT bool SDL_SetWindowResizable(SDL_Window *w, bool b) { return true; }
SDL_EXPORT bool SDL_SetWindowAlwaysOnTop(SDL_Window *w, bool b) { return true; }
SDL_EXPORT bool SDL_ShowWindow(SDL_Window *w) { if (w) w->flags &= ~(SDL_WindowFlags)SDL_WINDOW_HIDDEN; return true; }
SDL_EXPORT bool SDL_HideWindow(SDL_Window *w) { return true; }
SDL_EXPORT bool SDL_RaiseWindow(SDL_Window *w) { return true; }
SDL_EXPORT bool SDL_MaximizeWindow(SDL_Window *w) { return true; }
SDL_EXPORT bool SDL_MinimizeWindow(SDL_Window *w) { return true; }
SDL_EXPORT bool SDL_RestoreWindow(SDL_Window *w) { return true; }
SDL_EXPORT bool SDL_SyncWindow(SDL_Window *w) { return true; }
SDL_EXPORT bool SDL_FlashWindow(SDL_Window *w, SDL_FlashOperation op) { return true; }
SDL_EXPORT bool SDL_SetWindowOpacity(SDL_Window *w, float o) { return true; }
SDL_EXPORT float SDL_GetWindowOpacity(SDL_Window *w) { return 1.0f; }
/* Android is always full screen; Minecraft's own fullscreen setting is remembered so its UI stays consistent. */
SDL_EXPORT bool SDL_SetWindowFullscreen(SDL_Window *w, bool fs) {
    if (!w) return SDL_SetError("Invalid window");
    if (w->fullscreen != fs) { w->fullscreen = fs; push_window_event(fs ? SDL_EVENT_WINDOW_ENTER_FULLSCREEN : SDL_EVENT_WINDOW_LEAVE_FULLSCREEN, w, 0, 0); }
    return true;
}
SDL_EXPORT bool SDL_SetWindowFullscreenMode(SDL_Window *w, const SDL_DisplayMode *m) { return true; }
SDL_EXPORT const SDL_DisplayMode *SDL_GetWindowFullscreenMode(SDL_Window *w) { return NULL; }   /* borderless desktop */
SDL_EXPORT bool SDL_SetWindowMouseGrab(SDL_Window *w, bool g) { if (w) w->grab = g; return true; }
SDL_EXPORT bool SDL_GetWindowMouseGrab(SDL_Window *w) { return w && w->grab; }
SDL_EXPORT bool SDL_SetWindowKeyboardGrab(SDL_Window *w, bool g) { return true; }
SDL_EXPORT SDL_Window *SDL_GetKeyboardFocus(void) { return g_gameWindow; }
SDL_EXPORT SDL_Window *SDL_GetMouseFocus(void) { return g_gameWindow; }
SDL_EXPORT SDL_Window *SDL_GetGrabbedWindow(void) { return g_gameWindow && g_gameWindow->grab ? g_gameWindow : NULL; }
SDL_EXPORT bool SDL_ScreenSaverEnabled(void) { return false; }
SDL_EXPORT bool SDL_EnableScreenSaver(void) { return true; }
SDL_EXPORT bool SDL_DisableScreenSaver(void) { return true; }
SDL_EXPORT SDL_Window **SDL_GetWindows(int *count) {
    int n = 0; for (int i = 0; i < MAX_WINDOWS; i++) if (g_windows[i]) n++;
    SDL_Window **a = calloc((size_t)n + 1, sizeof *a); n = 0;
    for (int i = 0; i < MAX_WINDOWS; i++) if (g_windows[i]) a[n++] = g_windows[i];
    if (count) *count = n; return a;
}

/* ================================================================================================ displays */
static SDL_DisplayMode g_mode;
static const SDL_DisplayMode *current_mode(void) {
    pthread_mutex_lock(&g_winMu); int w = g_renderW ? g_renderW : g_surfaceW, h = g_renderH ? g_renderH : g_surfaceH; pthread_mutex_unlock(&g_winMu);
    memset(&g_mode, 0, sizeof g_mode);
    g_mode.displayID = 1; g_mode.format = SDL_PIXELFORMAT_XBGR8888; g_mode.w = w; g_mode.h = h;
    g_mode.pixel_density = 1.0f; g_mode.refresh_rate = 60.0f; g_mode.refresh_rate_numerator = 60; g_mode.refresh_rate_denominator = 1;
    return &g_mode;
}
SDL_EXPORT const char *SDL_GetCurrentVideoDriver(void) { return "cs-android"; }
SDL_EXPORT int SDL_GetNumVideoDrivers(void) { return 1; }
SDL_EXPORT const char *SDL_GetVideoDriver(int i) { return i == 0 ? "cs-android" : NULL; }
SDL_EXPORT SDL_SystemTheme SDL_GetSystemTheme(void) { return SDL_SYSTEM_THEME_DARK; }
SDL_EXPORT SDL_DisplayID *SDL_GetDisplays(int *count) {
    SDL_DisplayID *a = calloc(2, sizeof *a); a[0] = 1; if (count) *count = 1; return a;
}
SDL_EXPORT SDL_DisplayID SDL_GetPrimaryDisplay(void) { return 1; }
SDL_EXPORT SDL_DisplayID SDL_GetDisplayForWindow(SDL_Window *w) { return 1; }
SDL_EXPORT SDL_DisplayID SDL_GetDisplayForPoint(const SDL_Point *p) { return 1; }
SDL_EXPORT SDL_DisplayID SDL_GetDisplayForRect(const SDL_Rect *r) { return 1; }
SDL_EXPORT const char *SDL_GetDisplayName(SDL_DisplayID id) { return id == 1 ? "Android display" : NULL; }
SDL_EXPORT bool SDL_GetDisplayBounds(SDL_DisplayID id, SDL_Rect *r) {
    if (id != 1) return SDL_SetError("Invalid display");
    const SDL_DisplayMode *m = current_mode(); if (r) { r->x = 0; r->y = 0; r->w = m->w; r->h = m->h; } return true;
}
SDL_EXPORT bool SDL_GetDisplayUsableBounds(SDL_DisplayID id, SDL_Rect *r) { return SDL_GetDisplayBounds(id, r); }
SDL_EXPORT float SDL_GetDisplayContentScale(SDL_DisplayID id) { return 1.0f; }
SDL_EXPORT SDL_DisplayOrientation SDL_GetNaturalDisplayOrientation(SDL_DisplayID id) { return SDL_ORIENTATION_LANDSCAPE; }
SDL_EXPORT SDL_DisplayOrientation SDL_GetCurrentDisplayOrientation(SDL_DisplayID id) { return SDL_ORIENTATION_LANDSCAPE; }
SDL_EXPORT const SDL_DisplayMode *SDL_GetDesktopDisplayMode(SDL_DisplayID id) { if (id != 1) { SDL_SetError("Invalid display"); return NULL; } return current_mode(); }
SDL_EXPORT const SDL_DisplayMode *SDL_GetCurrentDisplayMode(SDL_DisplayID id) { return SDL_GetDesktopDisplayMode(id); }
/* One allocation: NULL-terminated pointer array followed by the modes (freed with a single SDL_free, as in SDL). */
SDL_EXPORT SDL_DisplayMode **SDL_GetFullscreenDisplayModes(SDL_DisplayID id, int *count) {
    if (id != 1) { if (count) *count = 0; SDL_SetError("Invalid display"); return NULL; }
    SDL_DisplayMode **a = malloc(2 * sizeof(SDL_DisplayMode *) + sizeof(SDL_DisplayMode));
    SDL_DisplayMode *m = (SDL_DisplayMode *)(a + 2);
    *m = *current_mode(); a[0] = m; a[1] = NULL; if (count) *count = 1; return a;
}
SDL_EXPORT bool SDL_GetClosestFullscreenDisplayMode(SDL_DisplayID id, int w, int h, float rate, bool hd, SDL_DisplayMode *closest) {
    if (id != 1) return SDL_SetError("Invalid display");
    if (closest) *closest = *current_mode(); return true;
}

/* ================================================================================================ OpenGL */
static int g_attr[SDL_GL_EGL_PLATFORM + 1];
static void reset_attrs(void) {
    memset(g_attr, 0, sizeof g_attr);
    g_attr[SDL_GL_RED_SIZE] = 8; g_attr[SDL_GL_GREEN_SIZE] = 8; g_attr[SDL_GL_BLUE_SIZE] = 8; g_attr[SDL_GL_ALPHA_SIZE] = 8;
    g_attr[SDL_GL_DEPTH_SIZE] = 24; g_attr[SDL_GL_STENCIL_SIZE] = 8; g_attr[SDL_GL_DOUBLEBUFFER] = 1; g_attr[SDL_GL_ACCELERATED_VISUAL] = 1;
    g_attr[SDL_GL_CONTEXT_MAJOR_VERSION] = 2; g_attr[SDL_GL_CONTEXT_MINOR_VERSION] = 1;
}
static int g_attrInit = 0;
SDL_EXPORT void SDL_GL_ResetAttributes(void) { reset_attrs(); g_attrInit = 1; }
SDL_EXPORT bool SDL_GL_SetAttribute(SDL_GLAttr a, int v) {
    if (!g_attrInit) SDL_GL_ResetAttributes();
    if ((int)a < 0 || a > SDL_GL_EGL_PLATFORM) return SDL_SetError("Unknown OpenGL attribute %d", (int)a);
    g_attr[a] = v; return true;
}

static void *g_sdlGlLib = NULL;
typedef void *(*cs_gpa_fn)(const char *);
static cs_gpa_fn g_gpa = NULL; static void *g_gpaLib = NULL;

/* Same resolution order as LWJGL 3.4's org.lwjgl.opengl.GL function provider (Linux), on the same library handle. */
SDL_EXPORT SDL_FunctionPointer SDL_GL_GetProcAddress(const char *name) {
    if (!name) return NULL;
    void *lib = g_sdlGlLib ? g_sdlGlLib : g_glLib;
    if (!lib) return (SDL_FunctionPointer)(p_eglGetProcAddress ? p_eglGetProcAddress(name) : NULL);
    if (g_gpaLib != lib) {
        g_gpaLib = lib;
        g_gpa = (cs_gpa_fn)dlsym(lib, "glXGetProcAddress");
        if (!g_gpa) g_gpa = (cs_gpa_fn)dlsym(lib, "glXGetProcAddressARB");
        if (!g_gpa) g_gpa = (cs_gpa_fn)dlsym(lib, "eglGetProcAddress");
        if (!g_gpa) g_gpa = (cs_gpa_fn)dlsym(lib, "OSMesaGetProcAddress");
    }
    void *p = g_gpa ? g_gpa(name) : NULL;
    if (!p) p = dlsym(lib, name);
    return (SDL_FunctionPointer)p;
}
SDL_EXPORT SDL_FunctionPointer SDL_EGL_GetProcAddress(const char *name) { return (SDL_FunctionPointer)(p_eglGetProcAddress ? p_eglGetProcAddress(name) : NULL); }

SDL_EXPORT bool SDL_GL_LoadLibrary(const char *path) {
    if (!cs_load_backend()) return SDL_SetError("The renderer's EGL backend could not be initialised (see launcher log)");
    if (g_ctxKind == CTX_OSMESA) return SDL_SetError("The selected renderer (OSMesa) is not supported for Minecraft 26.3+; choose MobileGlues");
    if (path && *path) {
        void *h = dlopen(path, RTLD_NOW | RTLD_GLOBAL);   /* same path as LWJGL's org.lwjgl.opengl.libname -> same handle */
        if (!h) return SDL_SetError("Failed to load %s: %s", path, dlerror());
        if (g_sdlGlLib && g_sdlGlLib != h) dlclose(g_sdlGlLib);
        g_sdlGlLib = h;
    }
    return true;
}
SDL_EXPORT void SDL_GL_UnloadLibrary(void) { /* the library stays mapped: LWJGL keeps using it */ }

static int parse_gl_version(const char *v, int *maj, int *min) {
    if (!v) return 0;
    const char *p = v; while (*p && (*p < '0' || *p > '9')) p++;   /* skips "OpenGL ES " */
    return sscanf(p, "%d.%d", maj, min) == 2;
}

SDL_EXPORT bool SDL_GL_GetAttribute(SDL_GLAttr a, int *v) {
    if (!g_attrInit) SDL_GL_ResetAttributes();
    if (!v) return SDL_SetError("Parameter 'value' is invalid");
    if ((int)a < 0 || a > SDL_GL_EGL_PLATFORM) { *v = 0; return SDL_SetError("Unknown OpenGL attribute %d", (int)a); }
    if ((a == SDL_GL_CONTEXT_MAJOR_VERSION || a == SDL_GL_CONTEXT_MINOR_VERSION) && t_curCtx) {
        *v = a == SDL_GL_CONTEXT_MAJOR_VERSION ? t_curCtx->major : t_curCtx->minor; return true;
    }
    *v = g_attr[a]; return true;
}

SDL_EXPORT SDL_GLContext SDL_GL_CreateContext(SDL_Window *win) {
    if (!win) { SDL_SetError("Invalid window"); return NULL; }
    if (!cs_load_backend()) { SDL_SetError("The renderer's EGL backend could not be initialised"); return NULL; }
    if (!g_attrInit) SDL_GL_ResetAttributes();
    handle_surface_change();
    EGLContext share = (g_attr[SDL_GL_SHARE_WITH_CURRENT_CONTEXT] && t_curCtx) ? t_curCtx->ctx : EGL_NO_CONTEXT;
    EGLContext ctx = EGL_NO_CONTEXT;
    if (g_ctxKind == CTX_EGL_DESKTOP) {
        int maj = g_attr[SDL_GL_CONTEXT_MAJOR_VERSION], min = g_attr[SDL_GL_CONTEXT_MINOR_VERSION];
        int core = (g_attr[SDL_GL_CONTEXT_PROFILE_MASK] & SDL_GL_CONTEXT_PROFILE_CORE) != 0;
        int fwd = (g_attr[SDL_GL_CONTEXT_FLAGS] & SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG) != 0;
        const EGLint full[] = { 0x3098, maj, 0x30FB, min, 0x30FD, core ? 0x1 : 0x2, 0x31B1 /* FORWARD_COMPATIBLE */, fwd ? EGL_TRUE : EGL_FALSE, EGL_NONE };
        const EGLint prof[] = { 0x3098, maj, 0x30FB, min, 0x30FD, core ? 0x1 : 0x2, EGL_NONE };
        const EGLint basic[] = { 0x3098, maj, 0x30FB, min, EGL_NONE };
        ctx = p_eglCreateContext(g_dpy, g_cfg, share, full);
        if (ctx == EGL_NO_CONTEXT) ctx = p_eglCreateContext(g_dpy, g_cfg, share, prof);
        if (ctx == EGL_NO_CONTEXT) ctx = p_eglCreateContext(g_dpy, g_cfg, share, basic);
    } else {
        const EGLint ca[] = { EGL_CONTEXT_CLIENT_VERSION, g_ctxKind == CTX_EGL_GLES3 ? 3 : 2, EGL_NONE };
        ctx = p_eglCreateContext(g_dpy, g_cfg, share, ca);
    }
    if (ctx == EGL_NO_CONTEXT) { SDL_SetError("eglCreateContext failed (0x%x)", p_eglGetError()); return NULL; }
    SDL_GLContextState *c = calloc(1, sizeof *c); c->ctx = ctx;
    c->major = g_attr[SDL_GL_CONTEXT_MAJOR_VERSION]; c->minor = g_attr[SDL_GL_CONTEXT_MINOR_VERSION];
    if (!p_eglMakeCurrent(g_dpy, g_surface, g_surface, ctx)) {
        p_eglDestroyContext(g_dpy, ctx); free(c); SDL_SetError("eglMakeCurrent failed (0x%x)", p_eglGetError()); return NULL;
    }
    t_curCtx = c; t_curWindow = win;
    /* Report the version the driver really provides (Minecraft refuses anything below 3.3). */
    const unsigned char *(*getString)(unsigned int) = (const unsigned char *(*)(unsigned int))SDL_GL_GetProcAddress("glGetString");
    const char *ver = getString ? (const char *)getString(0x1F02 /* GL_VERSION */) : NULL;
    int maj, min; if (parse_gl_version(ver, &maj, &min)) { c->major = maj; c->minor = min; }
    sdl_log(SDL_LOG_PRIORITY_INFO, "OpenGL context: %s (renderer %s)", ver ? ver : "?", getString ? (const char *)getString(0x1F01) : "?");
    return c;
}

SDL_EXPORT bool SDL_GL_MakeCurrent(SDL_Window *win, SDL_GLContext ctx) {
    if (!ctx) { p_eglMakeCurrent(g_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); t_curCtx = NULL; t_curWindow = NULL; return true; }
    handle_surface_change();
    if (!p_eglMakeCurrent(g_dpy, g_surface, g_surface, ctx->ctx)) return SDL_SetError("eglMakeCurrent failed (0x%x)", p_eglGetError());
    t_curCtx = ctx; t_curWindow = win;
    p_eglSwapInterval(g_dpy, g_swapInterval);
    return true;
}
SDL_EXPORT SDL_Window *SDL_GL_GetCurrentWindow(void) { return t_curWindow; }
SDL_EXPORT SDL_GLContext SDL_GL_GetCurrentContext(void) { return t_curCtx; }
SDL_EXPORT bool SDL_GL_DestroyContext(SDL_GLContext ctx) {
    if (!ctx) return SDL_SetError("Invalid context");
    if (t_curCtx == ctx) { p_eglMakeCurrent(g_dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); t_curCtx = NULL; t_curWindow = NULL; }
    p_eglDestroyContext(g_dpy, ctx->ctx); free(ctx); return true;
}
SDL_EXPORT bool SDL_GL_SetSwapInterval(int i) { g_swapInterval = i < 0 ? 1 : i; if (t_curCtx) p_eglSwapInterval(g_dpy, g_swapInterval); return true; }
SDL_EXPORT bool SDL_GL_GetSwapInterval(int *i) { if (i) *i = g_swapInterval; return true; }
SDL_EXPORT bool SDL_GL_SwapWindow(SDL_Window *win) {
    if (g_surface != EGL_NO_SURFACE && !p_eglSwapBuffers(g_dpy, g_surface)) LOGW("eglSwapBuffers failed: 0x%x", p_eglGetError());
    handle_surface_change();
    return true;
}
SDL_EXPORT bool SDL_GL_ExtensionSupported(const char *ext) {
    if (!ext || !t_curCtx) return false;
    const unsigned char *(*getStringi)(unsigned int, unsigned int) = (const unsigned char *(*)(unsigned int, unsigned int))SDL_GL_GetProcAddress("glGetStringi");
    void (*getIntegerv)(unsigned int, int *) = (void (*)(unsigned int, int *))SDL_GL_GetProcAddress("glGetIntegerv");
    if (!getStringi || !getIntegerv) return false;
    int n = 0; getIntegerv(0x821D /* GL_NUM_EXTENSIONS */, &n);
    for (int i = 0; i < n; i++) { const char *e = (const char *)getStringi(0x1F03, (unsigned)i); if (e && strcmp(e, ext) == 0) return true; }
    return false;
}

/* ================================================================================================ Vulkan (not offered) */
SDL_EXPORT bool SDL_Vulkan_LoadLibrary(const char *path) { return SDL_SetError("Vulkan is not available through the CS Launcher Android bridge; Minecraft uses OpenGL"); }
SDL_EXPORT void SDL_Vulkan_UnloadLibrary(void) {}
SDL_EXPORT SDL_FunctionPointer SDL_Vulkan_GetVkGetInstanceProcAddr(void) { SDL_SetError("Vulkan is not available"); return NULL; }
SDL_EXPORT char const * const *SDL_Vulkan_GetInstanceExtensions(Uint32 *count) { if (count) *count = 0; SDL_SetError("Vulkan is not available"); return NULL; }
SDL_EXPORT bool SDL_Vulkan_CreateSurface(SDL_Window *w, VkInstance i, const struct VkAllocationCallbacks *a, VkSurfaceKHR *s) { return SDL_SetError("Vulkan is not available"); }
SDL_EXPORT void SDL_Vulkan_DestroySurface(VkInstance i, VkSurfaceKHR s, const struct VkAllocationCallbacks *a) {}
SDL_EXPORT bool SDL_Vulkan_GetPresentationSupport(VkInstance i, VkPhysicalDevice d, Uint32 q) { return false; }

/* ================================================================================================ keyboard */
static bool g_keyState[SDL_SCANCODE_COUNT];
static SDL_Keymod g_lockMods = 0;
static bool g_textInput = false;

/* GLFW key code (what the launcher's controls and keyboard mapping produce) -> SDL scancode (USB HID usage). */
static SDL_Scancode glfw_to_scancode(int k) {
    if (k >= 65 && k <= 90) return (SDL_Scancode)(SDL_SCANCODE_A + (k - 65));
    if (k >= 49 && k <= 57) return (SDL_Scancode)(SDL_SCANCODE_1 + (k - 49));
    if (k >= 290 && k <= 301) return (SDL_Scancode)(SDL_SCANCODE_F1 + (k - 290));
    if (k >= 302 && k <= 313) return (SDL_Scancode)(SDL_SCANCODE_F13 + (k - 302));
    if (k >= 321 && k <= 329) return (SDL_Scancode)(SDL_SCANCODE_KP_1 + (k - 321));
    switch (k) {
        case 32: return SDL_SCANCODE_SPACE; case 39: return SDL_SCANCODE_APOSTROPHE; case 44: return SDL_SCANCODE_COMMA;
        case 45: return SDL_SCANCODE_MINUS; case 46: return SDL_SCANCODE_PERIOD; case 47: return SDL_SCANCODE_SLASH;
        case 48: return SDL_SCANCODE_0; case 59: return SDL_SCANCODE_SEMICOLON; case 61: return SDL_SCANCODE_EQUALS;
        case 91: return SDL_SCANCODE_LEFTBRACKET; case 92: return SDL_SCANCODE_BACKSLASH; case 93: return SDL_SCANCODE_RIGHTBRACKET;
        case 96: return SDL_SCANCODE_GRAVE; case 161: return SDL_SCANCODE_NONUSBACKSLASH;
        case 256: return SDL_SCANCODE_ESCAPE; case 257: return SDL_SCANCODE_RETURN; case 258: return SDL_SCANCODE_TAB;
        case 259: return SDL_SCANCODE_BACKSPACE; case 260: return SDL_SCANCODE_INSERT; case 261: return SDL_SCANCODE_DELETE;
        case 262: return SDL_SCANCODE_RIGHT; case 263: return SDL_SCANCODE_LEFT; case 264: return SDL_SCANCODE_DOWN; case 265: return SDL_SCANCODE_UP;
        case 266: return SDL_SCANCODE_PAGEUP; case 267: return SDL_SCANCODE_PAGEDOWN; case 268: return SDL_SCANCODE_HOME; case 269: return SDL_SCANCODE_END;
        case 280: return SDL_SCANCODE_CAPSLOCK; case 281: return SDL_SCANCODE_SCROLLLOCK; case 282: return SDL_SCANCODE_NUMLOCKCLEAR;
        case 283: return SDL_SCANCODE_PRINTSCREEN; case 284: return SDL_SCANCODE_PAUSE;
        case 320: return SDL_SCANCODE_KP_0; case 330: return SDL_SCANCODE_KP_PERIOD; case 331: return SDL_SCANCODE_KP_DIVIDE;
        case 332: return SDL_SCANCODE_KP_MULTIPLY; case 333: return SDL_SCANCODE_KP_MINUS; case 334: return SDL_SCANCODE_KP_PLUS;
        case 335: return SDL_SCANCODE_KP_ENTER; case 336: return SDL_SCANCODE_KP_EQUALS;
        case 340: return SDL_SCANCODE_LSHIFT; case 341: return SDL_SCANCODE_LCTRL; case 342: return SDL_SCANCODE_LALT; case 343: return SDL_SCANCODE_LGUI;
        case 344: return SDL_SCANCODE_RSHIFT; case 345: return SDL_SCANCODE_RCTRL; case 346: return SDL_SCANCODE_RALT; case 347: return SDL_SCANCODE_RGUI;
        case 348: return SDL_SCANCODE_APPLICATION;
        default: return SDL_SCANCODE_UNKNOWN;
    }
}

/* US layout keycodes (Android delivers layout-specific text separately as committed characters). */
SDL_EXPORT SDL_Keycode SDL_GetKeyFromScancode(SDL_Scancode sc, SDL_Keymod mod, bool keyEvent) {
    if (sc >= SDL_SCANCODE_A && sc <= SDL_SCANCODE_Z) return (SDL_Keycode)('a' + (sc - SDL_SCANCODE_A));
    if (sc >= SDL_SCANCODE_1 && sc <= SDL_SCANCODE_9) return (SDL_Keycode)('1' + (sc - SDL_SCANCODE_1));
    switch (sc) {
        case SDL_SCANCODE_0: return SDLK_0; case SDL_SCANCODE_RETURN: return SDLK_RETURN; case SDL_SCANCODE_ESCAPE: return SDLK_ESCAPE;
        case SDL_SCANCODE_BACKSPACE: return SDLK_BACKSPACE; case SDL_SCANCODE_TAB: return SDLK_TAB; case SDL_SCANCODE_SPACE: return SDLK_SPACE;
        case SDL_SCANCODE_MINUS: return SDLK_MINUS; case SDL_SCANCODE_EQUALS: return SDLK_EQUALS; case SDL_SCANCODE_LEFTBRACKET: return SDLK_LEFTBRACKET;
        case SDL_SCANCODE_RIGHTBRACKET: return SDLK_RIGHTBRACKET; case SDL_SCANCODE_BACKSLASH: return SDLK_BACKSLASH; case SDL_SCANCODE_SEMICOLON: return SDLK_SEMICOLON;
        case SDL_SCANCODE_APOSTROPHE: return SDLK_APOSTROPHE; case SDL_SCANCODE_GRAVE: return SDLK_GRAVE; case SDL_SCANCODE_COMMA: return SDLK_COMMA;
        case SDL_SCANCODE_PERIOD: return SDLK_PERIOD; case SDL_SCANCODE_SLASH: return SDLK_SLASH; case SDL_SCANCODE_DELETE: return SDLK_DELETE;
        case SDL_SCANCODE_UNKNOWN: return SDLK_UNKNOWN;
        default: return SDL_SCANCODE_TO_KEYCODE(sc);
    }
}
SDL_EXPORT SDL_Scancode SDL_GetScancodeFromKey(SDL_Keycode key, SDL_Keymod *mod) {
    if (mod) *mod = SDL_KMOD_NONE;
    if (key & SDLK_SCANCODE_MASK) return (SDL_Scancode)(key & ~SDLK_SCANCODE_MASK);
    for (int sc = 0; sc < SDL_SCANCODE_COUNT; sc++) if (SDL_GetKeyFromScancode((SDL_Scancode)sc, 0, false) == key) return (SDL_Scancode)sc;
    return SDL_SCANCODE_UNKNOWN;
}
SDL_EXPORT const char *SDL_GetKeyName(SDL_Keycode key) {
    static __thread char buf[8];
    switch (key) {
        case SDLK_RETURN: return "Return"; case SDLK_ESCAPE: return "Escape"; case SDLK_BACKSPACE: return "Backspace";
        case SDLK_TAB: return "Tab"; case SDLK_SPACE: return "Space"; case SDLK_DELETE: return "Delete";
        default: break;
    }
    if (key >= 33 && key < 127) { buf[0] = (char)(key >= 'a' && key <= 'z' ? key - 32 : key); buf[1] = 0; return buf; }
    return "";
}
SDL_EXPORT const char *SDL_GetScancodeName(SDL_Scancode sc) { return SDL_GetKeyName(SDL_GetKeyFromScancode(sc, 0, false)); }
SDL_EXPORT const bool *SDL_GetKeyboardState(int *numkeys) { if (numkeys) *numkeys = SDL_SCANCODE_COUNT; return g_keyState; }
static SDL_Keymod held_mods(void) {
    SDL_Keymod m = g_lockMods;
    if (g_keyState[SDL_SCANCODE_LSHIFT]) m |= SDL_KMOD_LSHIFT; if (g_keyState[SDL_SCANCODE_RSHIFT]) m |= SDL_KMOD_RSHIFT;
    if (g_keyState[SDL_SCANCODE_LCTRL]) m |= SDL_KMOD_LCTRL; if (g_keyState[SDL_SCANCODE_RCTRL]) m |= SDL_KMOD_RCTRL;
    if (g_keyState[SDL_SCANCODE_LALT]) m |= SDL_KMOD_LALT; if (g_keyState[SDL_SCANCODE_RALT]) m |= SDL_KMOD_RALT;
    if (g_keyState[SDL_SCANCODE_LGUI]) m |= SDL_KMOD_LGUI; if (g_keyState[SDL_SCANCODE_RGUI]) m |= SDL_KMOD_RGUI;
    return m;
}
static SDL_Keymod g_eventMods = 0;   /* modifiers reported with the last key event (GLFW mods from the launcher) */
SDL_EXPORT SDL_Keymod SDL_GetModState(void) { return held_mods() | g_eventMods; }
SDL_EXPORT void SDL_SetModState(SDL_Keymod m) { g_lockMods = m & (SDL_KMOD_CAPS | SDL_KMOD_NUM | SDL_KMOD_SCROLL); }
SDL_EXPORT void SDL_ResetKeyboard(void) { memset(g_keyState, 0, sizeof g_keyState); g_eventMods = 0; }
SDL_EXPORT bool SDL_HasKeyboard(void) { return true; }
SDL_EXPORT bool SDL_StartTextInput(SDL_Window *w) { g_textInput = true; return true; }
SDL_EXPORT bool SDL_StartTextInputWithProperties(SDL_Window *w, SDL_PropertiesID p) { g_textInput = true; return true; }
SDL_EXPORT bool SDL_TextInputActive(SDL_Window *w) { return g_textInput; }
SDL_EXPORT bool SDL_StopTextInput(SDL_Window *w) { g_textInput = false; return true; }
SDL_EXPORT bool SDL_ClearComposition(SDL_Window *w) { return true; }
SDL_EXPORT bool SDL_SetTextInputArea(SDL_Window *w, const SDL_Rect *r, int cursor) { return true; }
SDL_EXPORT bool SDL_HasScreenKeyboardSupport(void) { return false; }   /* the launcher overlay owns the soft keyboard */
SDL_EXPORT bool SDL_ScreenKeyboardShown(SDL_Window *w) { return false; }

/* ================================================================================================ mouse */
static float g_mouseX = 0, g_mouseY = 0;
static SDL_MouseButtonFlags g_buttons = 0;
static struct SDL_Cursor g_cursors[SDL_SYSTEM_CURSOR_COUNT];
static SDL_Cursor *g_cursor = NULL;
SDL_EXPORT SDL_MouseButtonFlags SDL_GetMouseState(float *x, float *y) { if (x) *x = g_mouseX; if (y) *y = g_mouseY; return g_buttons; }
SDL_EXPORT SDL_MouseButtonFlags SDL_GetGlobalMouseState(float *x, float *y) { return SDL_GetMouseState(x, y); }
SDL_EXPORT SDL_MouseButtonFlags SDL_GetRelativeMouseState(float *x, float *y) { if (x) *x = 0; if (y) *y = 0; return g_buttons; }
SDL_EXPORT void SDL_WarpMouseInWindow(SDL_Window *w, float x, float y) {
    g_mouseX = x; g_mouseY = y;
    if (!(w && w->relative)) cs_host_cursor_pos(x, y);   /* keeps the launcher's on-screen cursor in sync */
}
SDL_EXPORT bool SDL_WarpMouseGlobal(float x, float y) { SDL_WarpMouseInWindow(g_gameWindow, x, y); return true; }
SDL_EXPORT bool SDL_SetWindowRelativeMouseMode(SDL_Window *w, bool enabled) {
    if (!w) return SDL_SetError("Invalid window");
    if (w->relative != enabled) { w->relative = enabled; if (w == g_gameWindow) cs_host_cursor_mode(enabled); }   /* touch engine: camera vs. menu pointer */
    return true;
}
SDL_EXPORT bool SDL_GetWindowRelativeMouseMode(SDL_Window *w) { return w && w->relative; }
SDL_EXPORT bool SDL_CaptureMouse(bool e) { return true; }
SDL_EXPORT bool SDL_HasMouse(void) { return true; }
SDL_EXPORT SDL_Cursor *SDL_CreateSystemCursor(SDL_SystemCursor id) {
    if ((int)id < 0 || id >= SDL_SYSTEM_CURSOR_COUNT) { SDL_SetError("Invalid cursor"); return NULL; }
    g_cursors[id].shape = id; return &g_cursors[id];
}
SDL_EXPORT SDL_Cursor *SDL_CreateCursor(const Uint8 *d, const Uint8 *m, int w, int h, int hx, int hy) { return &g_cursors[0]; }
SDL_EXPORT SDL_Cursor *SDL_CreateColorCursor(SDL_Surface *s, int hx, int hy) { return &g_cursors[0]; }
SDL_EXPORT SDL_Cursor *SDL_GetDefaultCursor(void) { return &g_cursors[SDL_SYSTEM_CURSOR_DEFAULT]; }
SDL_EXPORT SDL_Cursor *SDL_GetCursor(void) { return g_cursor ? g_cursor : &g_cursors[0]; }
SDL_EXPORT bool SDL_SetCursor(SDL_Cursor *c) { g_cursor = c; return true; }
SDL_EXPORT void SDL_DestroyCursor(SDL_Cursor *c) {}
SDL_EXPORT bool SDL_ShowCursor(void) { return true; }
SDL_EXPORT bool SDL_HideCursor(void) { return true; }
SDL_EXPORT bool SDL_CursorVisible(void) { return true; }

/* ================================================================================================ events */
#define EVQ 512
static SDL_Event g_evq[EVQ]; static int g_evHead = 0, g_evCount = 0;
static char g_textRing[32][16]; static int g_textIdx = 0;   /* SDL_TextInputEvent.text must outlive the poll */

static void push_event(const SDL_Event *e) {
    if (g_evCount == EVQ) { g_evHead = (g_evHead + 1) % EVQ; g_evCount--; }   /* drop the oldest */
    g_evq[(g_evHead + g_evCount) % EVQ] = *e; g_evCount++;
}
static void push_window_event(SDL_EventType type, SDL_Window *w, int d1, int d2) {
    SDL_Event e; memset(&e, 0, sizeof e);
    e.window.type = type; e.window.timestamp = SDL_GetTicksNS(); e.window.windowID = w ? w->id : 0; e.window.data1 = d1; e.window.data2 = d2;
    push_event(&e);
}
static int encode_utf8(unsigned int cp, char *o) {
    if (cp < 0x80) { o[0] = (char)cp; return 1; }
    if (cp < 0x800) { o[0] = (char)(0xC0 | (cp >> 6)); o[1] = (char)(0x80 | (cp & 0x3F)); return 2; }
    if (cp < 0x10000) { o[0] = (char)(0xE0 | (cp >> 12)); o[1] = (char)(0x80 | ((cp >> 6) & 0x3F)); o[2] = (char)(0x80 | (cp & 0x3F)); return 3; }
    o[0] = (char)(0xF0 | (cp >> 18)); o[1] = (char)(0x80 | ((cp >> 12) & 0x3F)); o[2] = (char)(0x80 | ((cp >> 6) & 0x3F)); o[3] = (char)(0x80 | (cp & 0x3F)); return 4;
}
static SDL_Keymod glfw_mods(int m) {
    SDL_Keymod r = 0;
    if (m & 0x01) r |= SDL_KMOD_LSHIFT; if (m & 0x02) r |= SDL_KMOD_LCTRL; if (m & 0x04) r |= SDL_KMOD_LALT;
    if (m & 0x08) r |= SDL_KMOD_LGUI; if (m & 0x10) r |= SDL_KMOD_CAPS; if (m & 0x20) r |= SDL_KMOD_NUM;
    return r;
}
static Uint8 glfw_button(int b) { switch (b) { case 0: return SDL_BUTTON_LEFT; case 1: return SDL_BUTTON_RIGHT; case 2: return SDL_BUTTON_MIDDLE; default: return (Uint8)(b + 1); } }

/* Moves everything the Android side queued (touch controls, keyboards, mice, focus, close) into SDL events. */
static void translate_input(void) {
    SDL_WindowID wid = g_gameWindow ? g_gameWindow->id : 0;
    int ww = g_gameWindow ? g_gameWindow->w : g_surfW, wh = g_gameWindow ? g_gameWindow->h : g_surfH;
    bool relative = g_gameWindow && g_gameWindow->relative;
    cs_event ce; SDL_Event e;
    while (cs_queue_pop(&ce)) {
        memset(&e, 0, sizeof e);
        Uint64 ts = SDL_GetTicksNS();
        switch (ce.type) {
            case CS_EV_KEY: {
                SDL_Scancode sc = glfw_to_scancode(ce.i1);
                bool down = ce.i3 != 0;
                if (sc != SDL_SCANCODE_UNKNOWN) g_keyState[sc] = down;
                g_eventMods = glfw_mods(ce.i4) & (SDL_KMOD_CAPS | SDL_KMOD_NUM);
                e.key.type = down ? SDL_EVENT_KEY_DOWN : SDL_EVENT_KEY_UP; e.key.timestamp = ts; e.key.windowID = wid; e.key.which = 1;
                e.key.scancode = sc; e.key.key = SDL_GetKeyFromScancode(sc, 0, true); e.key.mod = held_mods() | glfw_mods(ce.i4);
                e.key.raw = (Uint16)ce.i2; e.key.down = down; e.key.repeat = ce.i3 == 2;
                push_event(&e);
                break;
            }
            case CS_EV_CHAR: {
                char *t = g_textRing[g_textIdx]; g_textIdx = (g_textIdx + 1) % 32;
                int n = encode_utf8((unsigned int)ce.i1, t); t[n] = 0;
                e.text.type = SDL_EVENT_TEXT_INPUT; e.text.timestamp = ts; e.text.windowID = wid; e.text.text = t;
                push_event(&e);
                break;
            }
            case CS_EV_MOUSE_BUTTON: {
                Uint8 b = glfw_button(ce.i1); bool down = ce.i2 == 1;
                if (down) g_buttons |= SDL_BUTTON_MASK(b); else g_buttons &= ~SDL_BUTTON_MASK(b);
                e.button.type = down ? SDL_EVENT_MOUSE_BUTTON_DOWN : SDL_EVENT_MOUSE_BUTTON_UP; e.button.timestamp = ts;
                e.button.windowID = wid; e.button.which = 1; e.button.button = b; e.button.down = down; e.button.clicks = 1;
                e.button.x = g_mouseX; e.button.y = g_mouseY;
                push_event(&e);
                break;
            }
            case CS_EV_CURSOR_POS: {
                float dx, dy;
                if (ce.i1 == 1) { dx = (float)ce.d1; dy = (float)ce.d2; }                  /* relative (camera drag / mouse) */
                else { dx = (float)ce.d1 - g_mouseX; dy = (float)ce.d2 - g_mouseY; }      /* absolute pointer */
                if (relative) { /* SDL keeps the reported position fixed in relative mode; Minecraft reads xrel/yrel */ }
                else {
                    g_mouseX = ce.i1 == 1 ? g_mouseX + dx : (float)ce.d1; g_mouseY = ce.i1 == 1 ? g_mouseY + dy : (float)ce.d2;
                    if (g_mouseX < 0) g_mouseX = 0; if (g_mouseY < 0) g_mouseY = 0;
                    if (g_mouseX > ww - 1) g_mouseX = (float)(ww - 1); if (g_mouseY > wh - 1) g_mouseY = (float)(wh - 1);
                }
                e.motion.type = SDL_EVENT_MOUSE_MOTION; e.motion.timestamp = ts; e.motion.windowID = wid; e.motion.which = 1;
                e.motion.state = g_buttons; e.motion.x = g_mouseX; e.motion.y = g_mouseY; e.motion.xrel = dx; e.motion.yrel = dy;
                push_event(&e);
                break;
            }
            case CS_EV_SCROLL:
                e.wheel.type = SDL_EVENT_MOUSE_WHEEL; e.wheel.timestamp = ts; e.wheel.windowID = wid; e.wheel.which = 1;
                e.wheel.x = (float)ce.d1; e.wheel.y = (float)ce.d2; e.wheel.direction = SDL_MOUSEWHEEL_NORMAL;
                e.wheel.mouse_x = g_mouseX; e.wheel.mouse_y = g_mouseY;
                e.wheel.integer_x = (Sint32)ce.d1; e.wheel.integer_y = (Sint32)ce.d2;
                push_event(&e);
                break;
            case CS_EV_FOCUS:
                if (!ce.i1) { memset(g_keyState, 0, sizeof g_keyState); g_buttons = 0; }   /* no stuck keys after switching apps */
                push_window_event(ce.i1 ? SDL_EVENT_WINDOW_FOCUS_GAINED : SDL_EVENT_WINDOW_FOCUS_LOST, g_gameWindow, 0, 0);
                break;
            case CS_EV_CLOSE:
                push_window_event(SDL_EVENT_WINDOW_CLOSE_REQUESTED, g_gameWindow, 0, 0);
                break;
            default: break;   /* framebuffer size changes are applied on the next swap (handle_surface_change) */
        }
    }
}

SDL_EXPORT void SDL_PumpEvents(void) { translate_input(); }
SDL_EXPORT bool SDL_PollEvent(SDL_Event *ev) {
    translate_input();
    if (!g_evCount) return false;
    if (ev) { *ev = g_evq[g_evHead]; g_evHead = (g_evHead + 1) % EVQ; g_evCount--; }
    return true;
}
SDL_EXPORT bool SDL_WaitEventTimeout(SDL_Event *ev, Sint32 ms) {
    if (SDL_PollEvent(ev)) return true;
    cs_queue_wait(ms < 0 ? -1.0 : ms / 1000.0);
    return SDL_PollEvent(ev);
}
SDL_EXPORT bool SDL_WaitEvent(SDL_Event *ev) { return SDL_WaitEventTimeout(ev, -1); }
SDL_EXPORT bool SDL_PushEvent(SDL_Event *ev) { if (!ev) return false; push_event(ev); cs_queue_wake(); return true; }
SDL_EXPORT bool SDL_HasEvent(Uint32 type) { for (int i = 0; i < g_evCount; i++) if (g_evq[(g_evHead + i) % EVQ].type == type) return true; return false; }
SDL_EXPORT bool SDL_HasEvents(Uint32 lo, Uint32 hi) { for (int i = 0; i < g_evCount; i++) { Uint32 t = g_evq[(g_evHead + i) % EVQ].type; if (t >= lo && t <= hi) return true; } return false; }
SDL_EXPORT void SDL_FlushEvents(Uint32 lo, Uint32 hi) {
    SDL_Event keep[EVQ]; int n = 0;
    for (int i = 0; i < g_evCount; i++) { SDL_Event *e = &g_evq[(g_evHead + i) % EVQ]; if (e->type < lo || e->type > hi) keep[n++] = *e; }
    memcpy(g_evq, keep, sizeof(SDL_Event) * (size_t)n); g_evHead = 0; g_evCount = n;
}
SDL_EXPORT void SDL_FlushEvent(Uint32 type) { SDL_FlushEvents(type, type); }
static bool g_eventEnabled = true;
SDL_EXPORT void SDL_SetEventEnabled(Uint32 type, bool e) { (void)type; g_eventEnabled = e; }
SDL_EXPORT bool SDL_EventEnabled(Uint32 type) { return true; }
SDL_EXPORT Uint32 SDL_RegisterEvents(int n) { static Uint32 next = SDL_EVENT_USER; Uint32 r = next; next += (Uint32)(n > 0 ? n : 0); return r; }
/* Minecraft calls this from tasks that run after the event buffer was freed; with a single game window the answer
 * does not depend on the (possibly stale) contents. */
SDL_EXPORT SDL_Window *SDL_GetWindowFromEvent(const SDL_Event *ev) { return g_gameWindow; }

/* ================================================================================================ clipboard / URLs / message boxes */
SDL_EXPORT bool SDL_SetClipboardText(const char *text) { cs_host_set_clipboard(text ? text : ""); return true; }
SDL_EXPORT char *SDL_GetClipboardText(void) { char *c = cs_host_get_clipboard(); return c ? c : strdup(""); }
SDL_EXPORT bool SDL_HasClipboardText(void) { char *c = cs_host_get_clipboard(); bool r = c && *c; free(c); return r; }
SDL_EXPORT bool SDL_SetPrimarySelectionText(const char *t) { return true; }
SDL_EXPORT char *SDL_GetPrimarySelectionText(void) { return strdup(""); }
SDL_EXPORT bool SDL_HasPrimarySelectionText(void) { return false; }
SDL_EXPORT bool SDL_OpenURL(const char *url) { return cs_host_open_url(url) ? true : SDL_SetError("Only http(s) links can be opened"); }

/* No modal native dialogs can be shown from the game thread; the message goes to the game log (shown by the launcher's
 * crash/log screens) and the default button is reported, as if the user pressed Enter. */
SDL_EXPORT bool SDL_ShowSimpleMessageBox(SDL_MessageBoxFlags flags, const char *title, const char *message, SDL_Window *w) {
    fprintf(stderr, "[SDL message box] %s: %s\n", title ? title : "", message ? message : ""); fflush(stderr);
    LOGW("Message box: %s: %s", title ? title : "", message ? message : "");
    return true;
}
SDL_EXPORT bool SDL_ShowMessageBox(const SDL_MessageBoxData *d, int *buttonid) {
    if (!d) return SDL_SetError("Invalid message box");
    SDL_ShowSimpleMessageBox(d->flags, d->title, d->message, d->window);
    int chosen = d->numbuttons > 0 ? d->buttons[0].buttonID : -1;
    for (int i = 0; i < d->numbuttons; i++) if (d->buttons[i].flags & SDL_MESSAGEBOX_BUTTON_RETURNKEY_DEFAULT) { chosen = d->buttons[i].buttonID; break; }
    if (buttonid) *buttonid = chosen;
    return true;
}

/* ================================================================================================ surfaces (window icons, cursors) */
static SDL_PixelFormatDetails g_fmtDetails;
SDL_EXPORT const SDL_PixelFormatDetails *SDL_GetPixelFormatDetails(SDL_PixelFormat f) {
    memset(&g_fmtDetails, 0, sizeof g_fmtDetails);
    g_fmtDetails.format = f; g_fmtDetails.bits_per_pixel = SDL_BITSPERPIXEL(f); g_fmtDetails.bytes_per_pixel = SDL_BYTESPERPIXEL(f);
    Uint32 r = 0, g = 0, b = 0, a = 0;
    switch (f) {   /* byte-order independent packed 32-bit formats (Android is little-endian) */
        case SDL_PIXELFORMAT_RGBA8888: r = 0xFF000000; g = 0x00FF0000; b = 0x0000FF00; a = 0x000000FF; break;
        case SDL_PIXELFORMAT_ARGB8888: a = 0xFF000000; r = 0x00FF0000; g = 0x0000FF00; b = 0x000000FF; break;
        case SDL_PIXELFORMAT_ABGR8888: a = 0xFF000000; b = 0x00FF0000; g = 0x0000FF00; r = 0x000000FF; break;
        case SDL_PIXELFORMAT_BGRA8888: b = 0xFF000000; g = 0x00FF0000; r = 0x0000FF00; a = 0x000000FF; break;
        case SDL_PIXELFORMAT_XRGB8888: r = 0x00FF0000; g = 0x0000FF00; b = 0x000000FF; break;
        case SDL_PIXELFORMAT_XBGR8888: b = 0x00FF0000; g = 0x0000FF00; r = 0x000000FF; break;
        default: break;
    }
    g_fmtDetails.Rmask = r; g_fmtDetails.Gmask = g; g_fmtDetails.Bmask = b; g_fmtDetails.Amask = a;
#define SHIFT_BITS(m, s, n) do { Uint32 _m = (m); Uint8 _s = 0, _n = 0; if (_m) { while (!(_m & 1)) { _m >>= 1; _s++; } while (_m & 1) { _m >>= 1; _n++; } } s = _s; n = _n; } while (0)
    SHIFT_BITS(r, g_fmtDetails.Rshift, g_fmtDetails.Rbits); SHIFT_BITS(g, g_fmtDetails.Gshift, g_fmtDetails.Gbits);
    SHIFT_BITS(b, g_fmtDetails.Bshift, g_fmtDetails.Bbits); SHIFT_BITS(a, g_fmtDetails.Ashift, g_fmtDetails.Abits);
#undef SHIFT_BITS
    return &g_fmtDetails;
}
SDL_EXPORT SDL_Surface *SDL_CreateSurfaceFrom(int w, int h, SDL_PixelFormat f, void *pixels, int pitch) {
    if (w < 0 || h < 0) { SDL_SetError("Invalid surface size"); return NULL; }
    SDL_Surface *s = calloc(1, sizeof *s);
    s->flags = SDL_SURFACE_PREALLOCATED; s->format = f; s->w = w; s->h = h; s->pitch = pitch; s->pixels = pixels; s->refcount = 1;
    return s;
}
SDL_EXPORT SDL_Surface *SDL_CreateSurface(int w, int h, SDL_PixelFormat f) {
    int pitch = w * SDL_BYTESPERPIXEL(f);
    SDL_Surface *s = SDL_CreateSurfaceFrom(w, h, f, calloc((size_t)(pitch ? pitch : 1), (size_t)(h ? h : 1)), pitch);
    if (s) s->flags = 0;
    return s;
}
SDL_EXPORT void SDL_DestroySurface(SDL_Surface *s) {
    if (!s || --s->refcount > 0) return;
    if (!(s->flags & SDL_SURFACE_PREALLOCATED)) free(s->pixels);
    free(s);
}
SDL_EXPORT bool SDL_AddSurfaceAlternateImage(SDL_Surface *s, SDL_Surface *img) { return s != NULL && img != NULL; }
SDL_EXPORT bool SDL_SurfaceHasAlternateImages(SDL_Surface *s) { return false; }
SDL_EXPORT void SDL_RemoveSurfaceAlternateImages(SDL_Surface *s) {}

/* ================================================================================================ properties (minimal) */
SDL_EXPORT SDL_PropertiesID SDL_GetGlobalProperties(void) { return 1; }
SDL_EXPORT SDL_PropertiesID SDL_GetWindowProperties(SDL_Window *w) { return w ? 2 : 0; }
SDL_EXPORT SDL_PropertiesID SDL_GetDisplayProperties(SDL_DisplayID id) { return id == 1 ? 3 : 0; }
SDL_EXPORT bool SDL_GetBooleanProperty(SDL_PropertiesID p, const char *n, bool d) { return d; }
SDL_EXPORT Sint64 SDL_GetNumberProperty(SDL_PropertiesID p, const char *n, Sint64 d) { return d; }
SDL_EXPORT float SDL_GetFloatProperty(SDL_PropertiesID p, const char *n, float d) { return d; }
SDL_EXPORT const char *SDL_GetStringProperty(SDL_PropertiesID p, const char *n, const char *d) { return d; }
SDL_EXPORT void *SDL_GetPointerProperty(SDL_PropertiesID p, const char *n, void *d) { return d; }

/* ================================================================================================ gamepads / joysticks */
/* Controllers are mapped by the launcher's own input engine to keyboard/mouse events (as with GLFW), so SDL reports none. */
SDL_EXPORT bool SDL_HasGamepad(void) { return false; }
SDL_EXPORT SDL_JoystickID *SDL_GetGamepads(int *count) { if (count) *count = 0; return calloc(1, sizeof(SDL_JoystickID)); }
SDL_EXPORT bool SDL_HasJoystick(void) { return false; }
SDL_EXPORT SDL_JoystickID *SDL_GetJoysticks(int *count) { if (count) *count = 0; return calloc(1, sizeof(SDL_JoystickID)); }
SDL_EXPORT bool SDL_IsGamepad(SDL_JoystickID id) { return false; }

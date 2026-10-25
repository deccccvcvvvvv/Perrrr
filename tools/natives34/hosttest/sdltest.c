/*
 * Host test for the SDL3 bridge (app/src/main/cpp/cssdl3.c) against mock Android/EGL plumbing (mock.c).
 * Replays the sequence Minecraft 26.3 performs (decompiled client: Window, GlBackend, MonitorManager, input handlers)
 * and checks the results. Run by tools/natives34/hosttest/run.sh.
 */
#include <SDL3/SDL.h>
#include <SDL3/SDL_vulkan.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include "cs_common.h"
#include "cs_egl.h"
#include "mock.h"

static int fails = 0, checks = 0;
#define CHECK(c, ...) do { checks++; if (!(c)) { fails++; printf("FAIL %s:%d: %s -- ", __FILE__, __LINE__, #c); printf(__VA_ARGS__); printf(" (SDL_GetError: %s)\n", SDL_GetError()); } } while (0)

static int next_event(SDL_Event *e, Uint32 type) {   /* polls until an event of `type` (others are skipped) */
    while (SDL_PollEvent(e)) if (e->type == type) return 1;
    return 0;
}
static void push(int type, int i1, int i2, int i3, int i4, double d1, double d2) {
    cs_event e = { type, i1, i2, i3, i4, d1, d2 }; cs_queue_push(&e);
}

int main(int argc, char **argv) {
    SDL_Event e;
    CHECK(SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS), "init");
    CHECK(strcmp(SDL_GetPlatform(), "Android") == 0, "platform %s", SDL_GetPlatform());

    /* --- GL setup exactly like Minecraft's GlBackend: load library, 3.3 core forward-compatible attributes */
    CHECK(SDL_GL_LoadLibrary(getenv("CS_GL_LIB")), "load GL library");
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3); SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 3);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_CORE);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_FLAGS, SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG);
    SDL_GL_SetAttribute(SDL_GL_SHARE_WITH_CURRENT_CONTEXT, 1);

    /* hidden utility window that owns the context */
    SDL_Window *util = SDL_CreateWindow("", 320, 480, (SDL_WindowFlags)0x80020008ULL);
    CHECK(util != NULL, "utility window");
    int w = 0, h = 0; SDL_GetWindowSize(util, &w, &h);
    CHECK(w == 320 && h == 480, "utility window keeps requested size, got %dx%d", w, h);
    CHECK(mock.surfacesCreated == 1 && mock.geomW == 1600 && mock.geomH == 720, "one surface at render size, %d %dx%d", mock.surfacesCreated, mock.geomW, mock.geomH);
    CHECK(g_surfaceAckGeneration == 1, "surface generation acknowledged");
    CHECK(cs_sdl_holds_surface(), "bridge reports it holds the surface");

    SDL_GLContext ctx = SDL_GL_CreateContext(util);
    CHECK(ctx != NULL, "context");
    CHECK(mock.ctxMajor == 3 && mock.ctxMinor == 3 && mock.ctxProfile == 1, "requested 3.3 core, got %d.%d profile %d", mock.ctxMajor, mock.ctxMinor, mock.ctxProfile);
    CHECK(SDL_GL_MakeCurrent(util, ctx), "make current");
    int maj = 0, min = 0; SDL_GL_GetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, &maj); SDL_GL_GetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, &min);
    CHECK(maj == 4 && min == 6, "attributes report the driver's real version 4.6, got %d.%d", maj, min);

    /* GetProcAddress must agree with LWJGL's GL provider: glXGetProcAddress first, dlsym fallback */
    void *lib = dlopen(getenv("CS_GL_LIB"), RTLD_NOW | RTLD_NOLOAD);
    void *(*gpa)(const char *) = (void *(*)(const char *))dlsym(lib, "glXGetProcAddress");
    CHECK((void *)SDL_GL_GetProcAddress("glGetError") == gpa("glGetError"), "glGetError via glXGetProcAddress");
    CHECK((void *)SDL_GL_GetProcAddress("glGetError") != dlsym(lib, "glGetError"), "not the plain dlsym symbol");
    CHECK((void *)SDL_GL_GetProcAddress("glOnlyViaDlsym") == dlsym(lib, "glOnlyViaDlsym"), "dlsym fallback");
    CHECK(SDL_GL_GetProcAddress("glDoesNotExist") == NULL, "unknown function is NULL");

    /* test window created and destroyed again */
    SDL_Window *test = SDL_CreateWindow("", 1, 1, (SDL_WindowFlags)0x80020008ULL);
    CHECK(test != NULL, "test window");
    SDL_DestroyWindow(test);
    CHECK(mock.liveSurfaces == 1 && cs_sdl_holds_surface(), "surface still alive while utility window exists");

    /* --- the game window */
    SDL_Window *win = SDL_CreateWindow("Minecraft 26.3", 854, 480, (SDL_WindowFlags)0x2020ULL);
    CHECK(win != NULL, "game window");
    SDL_GetWindowSize(win, &w, &h); CHECK(w == 1600 && h == 720, "game window is the render surface, got %dx%d", w, h);
    CHECK(mock.readyW == 1600 && mock.readyH == 720, "activity told the window is ready");
    CHECK(mock.surfacesCreated == 1, "still one shared surface");
    CHECK(next_event(&e, SDL_EVENT_WINDOW_RESIZED) && e.window.data1 == 1600 && e.window.data2 == 720 && e.window.windowID == SDL_GetWindowID(win), "resize event at creation");
    SDL_GL_MakeCurrent(win, ctx);
    SDL_GL_SetSwapInterval(0); CHECK(mock.swapInterval == 0, "vsync off");

    /* --- displays (MonitorManager / Monitor) */
    int n = 0; SDL_DisplayID *ids = SDL_GetDisplays(&n);
    CHECK(ids && n == 1 && ids[0] == 1 && ids[1] == 0, "one display");
    SDL_free(ids);
    SDL_DisplayMode **modes = SDL_GetFullscreenDisplayModes(1, &n);
    CHECK(modes && n == 1 && modes[0]->w == 1600 && modes[0]->h == 720 && modes[0]->refresh_rate == 60.0f && modes[1] == NULL, "one fullscreen mode");
    SDL_free(modes);
    SDL_Rect r; CHECK(SDL_GetDisplayBounds(1, &r) && r.w == 1600, "bounds");
    CHECK(SDL_GetDesktopDisplayMode(1) && SDL_GetDesktopDisplayMode(1)->h == 720, "desktop mode");
    CHECK(SDL_GetDisplayForWindow(win) == 1, "display for window");

    /* --- keyboard: W pressed (GLFW 87) -> SDL scancode 26, keycode 'w'; shift modifier; repeat; release */
    push(CS_EV_KEY, 340, 50, 1, 0x1, 0, 0);
    push(CS_EV_KEY, 87, 17, 1, 0x1, 0, 0);
    CHECK(next_event(&e, SDL_EVENT_KEY_DOWN) && e.key.scancode == SDL_SCANCODE_LSHIFT, "shift down");
    CHECK(next_event(&e, SDL_EVENT_KEY_DOWN) && e.key.scancode == SDL_SCANCODE_W && e.key.key == SDLK_W && (e.key.mod & SDL_KMOD_LSHIFT) && e.key.down && !e.key.repeat, "W down with shift (sc %d key %u mod %x)", e.key.scancode, e.key.key, e.key.mod);
    CHECK(SDL_GetKeyboardState(NULL)[SDL_SCANCODE_W], "W held in keyboard state");
    push(CS_EV_KEY, 87, 17, 2, 0, 0, 0);
    CHECK(next_event(&e, SDL_EVENT_KEY_DOWN) && e.key.repeat, "repeat");
    push(CS_EV_KEY, 87, 17, 0, 0, 0, 0);
    CHECK(next_event(&e, SDL_EVENT_KEY_UP) && !SDL_GetKeyboardState(NULL)[SDL_SCANCODE_W], "W up");
    push(CS_EV_KEY, 256, 9, 1, 0, 0, 0); CHECK(next_event(&e, SDL_EVENT_KEY_DOWN) && e.key.scancode == SDL_SCANCODE_ESCAPE && e.key.key == SDLK_ESCAPE, "escape");
    push(CS_EV_KEY, 292, 0, 1, 0, 0, 0); CHECK(next_event(&e, SDL_EVENT_KEY_DOWN) && e.key.scancode == SDL_SCANCODE_F3, "F3");
    push(CS_EV_KEY, 257, 0, 1, 0, 0, 0); CHECK(next_event(&e, SDL_EVENT_KEY_DOWN) && e.key.scancode == SDL_SCANCODE_RETURN, "enter");
    CHECK(strcmp(SDL_GetKeyName(SDLK_Q), "Q") == 0, "key name %s", SDL_GetKeyName(SDLK_Q));

    /* --- text: committed characters arrive as UTF-8 text input */
    push(CS_EV_CHAR, 0xE9, 0, 0, 0, 0, 0);       /* é */
    push(CS_EV_CHAR, 0x1F600, 0, 0, 0, 0, 0);    /* emoji (4-byte UTF-8) */
    CHECK(next_event(&e, SDL_EVENT_TEXT_INPUT) && strcmp(e.text.text, "\xC3\xA9") == 0, "text é");
    CHECK(next_event(&e, SDL_EVENT_TEXT_INPUT) && strcmp(e.text.text, "\xF0\x9F\x98\x80") == 0, "text emoji");

    /* --- mouse: menu pointer (absolute), right button, wheel */
    push(CS_EV_CURSOR_POS, 0, 0, 0, 0, 100.0, 50.0);
    CHECK(next_event(&e, SDL_EVENT_MOUSE_MOTION) && e.motion.x == 100.0f && e.motion.y == 50.0f, "absolute motion");
    push(CS_EV_MOUSE_BUTTON, 1, 1, 0, 0, 0, 0);
    CHECK(next_event(&e, SDL_EVENT_MOUSE_BUTTON_DOWN) && e.button.button == SDL_BUTTON_RIGHT && e.button.x == 100.0f, "right button (GLFW 1 -> SDL 3)");
    CHECK(SDL_GetMouseState(NULL, NULL) & SDL_BUTTON_RMASK, "button state");
    push(CS_EV_MOUSE_BUTTON, 1, 0, 0, 0, 0, 0); CHECK(next_event(&e, SDL_EVENT_MOUSE_BUTTON_UP), "button up");
    push(CS_EV_SCROLL, 0, 0, 0, 0, 0.0, -1.0);
    CHECK(next_event(&e, SDL_EVENT_MOUSE_WHEEL) && e.wheel.y == -1.0f && e.wheel.integer_y == -1, "wheel");
    push(CS_EV_CURSOR_POS, 0, 0, 0, 0, 99999.0, -5.0);
    CHECK(next_event(&e, SDL_EVENT_MOUSE_MOTION) && e.motion.x == 1599.0f && e.motion.y == 0.0f, "pointer clamped to the window (%g,%g)", e.motion.x, e.motion.y);

    /* --- camera: relative mode (grab) tells the activity; drags arrive as xrel/yrel */
    SDL_WarpMouseInWindow(win, 800, 360);
    CHECK(SDL_SetWindowRelativeMouseMode(win, true) && mock.cursorGrabbed == 1, "grab forwarded to the activity");
    CHECK(SDL_GetWindowFlags(win) & SDL_WINDOW_MOUSE_RELATIVE_MODE, "relative flag");
    push(CS_EV_CURSOR_POS, 1, 0, 0, 0, 12.5, -3.0);
    CHECK(next_event(&e, SDL_EVENT_MOUSE_MOTION) && e.motion.xrel == 12.5f && e.motion.yrel == -3.0f, "relative motion");
    SDL_SetWindowRelativeMouseMode(win, false); CHECK(mock.cursorGrabbed == 0, "ungrab forwarded");

    /* --- FlushEvents(768, 4871) drops input but keeps window events */
    push(CS_EV_KEY, 65, 0, 1, 0, 0, 0); push(CS_EV_FOCUS, 0, 0, 0, 0, 0, 0); SDL_PumpEvents();
    SDL_FlushEvents(768, 4871);
    CHECK(!SDL_HasEvent(SDL_EVENT_KEY_DOWN) && SDL_HasEvent(SDL_EVENT_WINDOW_FOCUS_LOST), "flush range");
    CHECK(next_event(&e, SDL_EVENT_WINDOW_FOCUS_LOST) && !SDL_GetKeyboardState(NULL)[SDL_SCANCODE_A], "focus loss clears held keys");
    push(CS_EV_CLOSE, 0, 0, 0, 0, 0, 0);
    CHECK(next_event(&e, SDL_EVENT_WINDOW_CLOSE_REQUESTED) && e.window.windowID == SDL_GetWindowID(win), "close request");

    /* --- rotation/resume: the activity hands over a new surface; next swap rebuilds it and reports the new size */
    pthread_mutex_lock(&g_winMu); g_surfaceGeneration = 2; g_renderW = 1200; g_renderH = 540; pthread_mutex_unlock(&g_winMu);
    CHECK(SDL_GL_SwapWindow(win), "swap");
    CHECK(mock.swaps == 1 && mock.surfacesCreated == 2 && mock.liveSurfaces == 1 && g_surfaceAckGeneration == 2, "surface recreated (%d created, %d live)", mock.surfacesCreated, mock.liveSurfaces);
    CHECK(mock.curContext != NULL && mock.curSurface == (EGLSurface)(intptr_t)0x102, "context rebound to the new surface");
    SDL_GetWindowSizeInPixels(win, &w, &h); CHECK(w == 1200 && h == 540, "new size %dx%d", w, h);
    CHECK(next_event(&e, SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED) && e.window.data1 == 1200, "pixel size event");

    /* --- clipboard, URLs, message boxes */
    CHECK(SDL_SetClipboardText("hello"), "set clipboard");
    char *clip = SDL_GetClipboardText(); CHECK(clip && strcmp(clip, "hello") == 0, "clipboard round trip"); SDL_free(clip);
    CHECK(SDL_OpenURL("https://minecraft.net"), "https URL"); CHECK(!SDL_OpenURL("file:///etc/passwd"), "non-web URL refused");
    SDL_MessageBoxButtonData buttons[2] = { { 0, 7, "Quit" }, { SDL_MESSAGEBOX_BUTTON_RETURNKEY_DEFAULT, 9, "Continue" } };
    SDL_MessageBoxData box = { SDL_MESSAGEBOX_WARNING, NULL, "Title", "Body", 2, buttons, NULL };
    int chosen = -1; CHECK(SDL_ShowMessageBox(&box, &chosen) && chosen == 9, "default button chosen, got %d", chosen);

    /* --- window icon surface, cursors, Vulkan refusal, stubs */
    unsigned char px[16 * 16 * 4]; SDL_Surface *s = SDL_CreateSurfaceFrom(16, 16, SDL_PIXELFORMAT_ABGR8888, px, 64);
    CHECK(s && s->w == 16 && s->pixels == px && SDL_SetWindowIcon(win, s), "icon surface"); SDL_DestroySurface(s);
    CHECK(SDL_SetCursor(SDL_CreateSystemCursor(SDL_SYSTEM_CURSOR_POINTER)), "system cursor");
    CHECK(SDL_CreateWindow("vk", 100, 100, SDL_WINDOW_VULKAN) == NULL && strstr(SDL_GetError(), "Vulkan"), "Vulkan window refused");
    CHECK(!SDL_Vulkan_LoadLibrary(NULL), "Vulkan library refused");
    CHECK(SDL_GetNumAudioDrivers() == 0 && strstr(SDL_GetError(), "SDL_GetNumAudioDrivers"), "unsupported function fails cleanly");

    /* --- shutdown: the surface is released once the last window is gone */
    SDL_GL_DestroyContext(ctx);
    SDL_DestroyWindow(win); CHECK(cs_sdl_holds_surface(), "utility window still holds the surface");
    SDL_DestroyWindow(util);
    CHECK(!cs_sdl_holds_surface() && mock.liveSurfaces == 0, "surface released");
    SDL_Quit();

    printf("%d checks, %d failed\n", checks, fails);
    return fails ? 1 : 0;
}

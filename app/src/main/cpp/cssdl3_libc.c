/*
 * CS Launcher Pro — SDL3 bridge: SDL's libc wrappers that Java code may call (Minecraft frees SDL-allocated arrays with
 * SDL_free). Kept apart from cssdl3.c because SDL_stdinc.h redefines several of these names as macros for libc.
 * Memory handed out by the bridge is always allocated with malloc, so SDL_free == free.
 */
#include <stdlib.h>
#include <string.h>
#define E __attribute__((visibility("default")))
E void *SDL_malloc(size_t n) { return malloc(n ? n : 1); }
E void *SDL_calloc(size_t n, size_t s) { return calloc(n ? n : 1, s ? s : 1); }
E void *SDL_realloc(void *p, size_t n) { return realloc(p, n ? n : 1); }
E void SDL_free(void *p) { free(p); }
E char *SDL_strdup(const char *s) { return s ? strdup(s) : NULL; }
E size_t SDL_strlen(const char *s) { return s ? strlen(s) : 0; }
E void *SDL_memset(void *d, int c, size_t n) { return memset(d, c, n); }
E void *SDL_memcpy(void *d, const void *s, size_t n) { return memcpy(d, s, n); }
E void *SDL_memmove(void *d, const void *s, size_t n) { return memmove(d, s, n); }
E int SDL_memcmp(const void *a, const void *b, size_t n) { return memcmp(a, b, n); }
E int SDL_strcmp(const char *a, const char *b) { return strcmp(a, b); }
E int SDL_strncmp(const char *a, const char *b, size_t n) { return strncmp(a, b, n); }
E const char *SDL_getenv(const char *n) { return getenv(n); }
E const char *SDL_getenv_unsafe(const char *n) { return getenv(n); }

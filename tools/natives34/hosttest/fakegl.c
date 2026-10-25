/* A stand-in "GL driver" library for the host test. glXGetProcAddress and plain dlsym deliberately return different
 * addresses for glGetError, so the test can tell which lookup path SDL_GL_GetProcAddress used. */
#include <string.h>
#define E __attribute__((visibility("default")))
static unsigned int gpa_glGetError(void) { return 0; }
static const unsigned char *gpa_glGetString(unsigned int name) {
    return (const unsigned char *)(name == 0x1F02 ? "4.6.0 FakeGL 1.0" : name == 0x1F01 ? "FakeGL renderer" : "FakeGL");
}
E unsigned int glGetError(void) { return 0; }
E void glOnlyViaDlsym(void) {}
E void *glXGetProcAddress(const char *name) {
    if (strcmp(name, "glGetError") == 0) return (void *)gpa_glGetError;
    if (strcmp(name, "glGetString") == 0) return (void *)gpa_glGetString;
    return 0;
}

/* Host-test shim for <android/native_window.h>. */
#pragma once
#include <stdint.h>
typedef struct ANativeWindow ANativeWindow;
int32_t ANativeWindow_getWidth(ANativeWindow *w);
int32_t ANativeWindow_getHeight(ANativeWindow *w);
int32_t ANativeWindow_setBuffersGeometry(ANativeWindow *w, int32_t width, int32_t height, int32_t format);
void ANativeWindow_release(ANativeWindow *w);
void ANativeWindow_acquire(ANativeWindow *w);

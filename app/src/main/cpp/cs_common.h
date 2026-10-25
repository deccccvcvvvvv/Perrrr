#pragma once
#include <jni.h>
#include <android/log.h>
#include <stdint.h>

#define CS_EXPORT __attribute__((visibility("default")))
#define LOG_TAG "CSNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ---- input queue (filled by the Android UI thread, drained by glfwPollEvents on the game thread) ---- */
enum cs_event_type { CS_EV_KEY = 1, CS_EV_CHAR, CS_EV_MOUSE_BUTTON, CS_EV_CURSOR_POS, CS_EV_SCROLL, CS_EV_FRAMEBUFFER_SIZE, CS_EV_FOCUS, CS_EV_CLOSE };
typedef struct { int type; int i1, i2, i3, i4; double d1, d2; } cs_event;
void cs_queue_push(const cs_event *e);
int  cs_queue_pop(cs_event *out);      /* returns 1 if an event was popped */
int  cs_queue_wait(double timeout_s);  /* waits for an event or timeout (<0 = forever); returns 1 if non-empty */
void cs_queue_wake(void);
void cs_queue_clear(void);

/* ---- callbacks into the Android (ART) side ---- */
void cs_host_set_vm(JavaVM *vm, jclass bridgeClass);
void cs_host_cursor_mode(int grabbed);
void cs_host_cursor_pos(double x, double y);
void cs_host_set_clipboard(const char *utf8);
char *cs_host_get_clipboard(void);   /* malloc'ed, caller frees; NULL if empty */
CS_EXPORT void cs_host_log_line(const char *line);   /* used by libcsbridge */
CS_EXPORT void cs_host_jvm_exit(int code);           /* used by libcsbridge */
void cs_host_window_ready(int w, int h);

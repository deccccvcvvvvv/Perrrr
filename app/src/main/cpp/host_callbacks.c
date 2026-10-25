#include "cs_common.h"
#include <stdlib.h>
#include <string.h>

/* Static methods on pro.cslauncher.app.game.NativeBridge, invoked from native threads (game thread, log reader). */
static JavaVM *g_vm = NULL;
static jclass g_cls = NULL;
static jmethodID m_cursorMode, m_cursorPos, m_setClip, m_getClip, m_log, m_exit, m_ready, m_openUrl;

void cs_host_set_vm(JavaVM *vm, jclass cls) {
    g_vm = vm;
    JNIEnv *env; (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6);
    g_cls = (*env)->NewGlobalRef(env, cls);
    m_cursorMode = (*env)->GetStaticMethodID(env, g_cls, "onCursorMode", "(Z)V");
    m_cursorPos  = (*env)->GetStaticMethodID(env, g_cls, "onCursorPos", "(DD)V");
    m_setClip    = (*env)->GetStaticMethodID(env, g_cls, "onSetClipboard", "(Ljava/lang/String;)V");
    m_getClip    = (*env)->GetStaticMethodID(env, g_cls, "onGetClipboard", "()Ljava/lang/String;");
    m_log        = (*env)->GetStaticMethodID(env, g_cls, "onLogLine", "(Ljava/lang/String;)V");
    m_exit       = (*env)->GetStaticMethodID(env, g_cls, "onJvmExit", "(I)V");
    m_ready      = (*env)->GetStaticMethodID(env, g_cls, "onWindowReady", "(II)V");
    m_openUrl    = (*env)->GetStaticMethodID(env, g_cls, "onOpenUrl", "(Ljava/lang/String;)Z");
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
}

/* Attaches the calling thread to ART for the duration of one call if needed. */
static JNIEnv *attach(int *did) {
    *did = 0; if (!g_vm || !g_cls) return NULL;
    JNIEnv *env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        JavaVMAttachArgs a = { JNI_VERSION_1_6, "cs-native", NULL };
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, &a) != JNI_OK) return NULL;
        *did = 1;
    }
    return env;
}
static void detach(JNIEnv *env, int did) {
    if (env && (*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
    if (did) (*g_vm)->DetachCurrentThread(g_vm);
}

void cs_host_cursor_mode(int grabbed) { int d; JNIEnv *e = attach(&d); if (e && m_cursorMode) (*e)->CallStaticVoidMethod(e, g_cls, m_cursorMode, grabbed ? JNI_TRUE : JNI_FALSE); detach(e, d); }
void cs_host_cursor_pos(double x, double y) { int d; JNIEnv *e = attach(&d); if (e && m_cursorPos) (*e)->CallStaticVoidMethod(e, g_cls, m_cursorPos, x, y); detach(e, d); }
void cs_host_window_ready(int w, int h) { int d; JNIEnv *e = attach(&d); if (e && m_ready) (*e)->CallStaticVoidMethod(e, g_cls, m_ready, w, h); detach(e, d); }
void cs_host_jvm_exit(int code) { int d; JNIEnv *e = attach(&d); if (e && m_exit) (*e)->CallStaticVoidMethod(e, g_cls, m_exit, code); detach(e, d); }

void cs_host_set_clipboard(const char *s) {
    int d; JNIEnv *e = attach(&d);
    if (e && m_setClip) { jstring js = (*e)->NewStringUTF(e, s ? s : ""); (*e)->CallStaticVoidMethod(e, g_cls, m_setClip, js); (*e)->DeleteLocalRef(e, js); }
    detach(e, d);
}
char *cs_host_get_clipboard(void) {
    int d; JNIEnv *e = attach(&d); char *out = NULL;
    if (e && m_getClip) {
        jstring js = (jstring)(*e)->CallStaticObjectMethod(e, g_cls, m_getClip);
        if (js) { const char *c = (*e)->GetStringUTFChars(e, js, NULL); out = strdup(c); (*e)->ReleaseStringUTFChars(e, js, c); (*e)->DeleteLocalRef(e, js); }
    }
    detach(e, d); return out;
}
void cs_host_log_line(const char *line) {
    int d; JNIEnv *e = attach(&d);
    if (e && m_log) { jstring js = (*e)->NewStringUTF(e, line); if (js) { (*e)->CallStaticVoidMethod(e, g_cls, m_log, js); (*e)->DeleteLocalRef(e, js); } }
    detach(e, d);
}
int cs_host_open_url(const char *url) {
    int d; JNIEnv *e = attach(&d); int ok = 0;
    if (e && m_openUrl && url) { jstring js = (*e)->NewStringUTF(e, url); if (js) { ok = (*e)->CallStaticBooleanMethod(e, g_cls, m_openUrl, js) == JNI_TRUE; (*e)->DeleteLocalRef(e, js); } }
    detach(e, d); return ok;
}

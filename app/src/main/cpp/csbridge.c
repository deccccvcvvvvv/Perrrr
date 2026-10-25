/*
 * CS Launcher Pro — in-process Java VM launcher.
 * The Android process (":game") hosts a second, full OpenJDK VM loaded from an imported runtime. This file
 *   - redirects stdout/stderr into the session log file and the launcher's log view,
 *   - starts the VM on a thread with a large stack (Minecraft's world generation recurses deeply),
 *   - reports the exit code back to the activity through the exit/abort hooks, instead of killing the process silently.
 */
#include "cs_common.h"
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>

#ifndef JNI_VERSION_1_8
#define JNI_VERSION_1_8 0x00010008 /* the NDK jni.h stops at 1.6; OpenJDK accepts 1.8 */
#endif
typedef jint (*CreateJavaVM_t)(JavaVM **, void **, void *);

/* ---------------------------------------------------------------- stdio redirection */
static int g_logFd = -1;
static pthread_t g_logThread;

/* Log lines may contain arbitrary bytes; JNI NewStringUTF only accepts modified UTF-8 (no 4-byte sequences). */
static void sanitize_utf8(char *s) {
    unsigned char *p = (unsigned char *)s;
    while (*p) {
        int len = (*p < 0x80) ? 1 : ((*p & 0xE0) == 0xC0) ? 2 : ((*p & 0xF0) == 0xE0) ? 3 : 0;
        int ok = len > 0;
        for (int i = 1; ok && i < len; i++) if ((p[i] & 0xC0) != 0x80) ok = 0;
        if (!ok) { *p = '?'; p++; continue; }
        p += len;
    }
}

static void *log_reader(void *arg) {
    int fd = (int)(intptr_t)arg;
    char buf[4096], line[8192]; size_t used = 0;
    for (;;) {
        ssize_t n = read(fd, buf, sizeof buf);
        if (n <= 0) break;
        if (g_logFd >= 0) { ssize_t off = 0; while (off < n) { ssize_t w = write(g_logFd, buf + off, n - off); if (w <= 0) break; off += w; } }
        for (ssize_t i = 0; i < n; i++) {
            char c = buf[i];
            if (c == '\n' || used == sizeof line - 1) {
                line[used] = 0; sanitize_utf8(line);
                __android_log_write(ANDROID_LOG_INFO, "CSGame", line);
                cs_host_log_line(line);
                used = 0;
                if (c == '\n') continue;
            }
            if (c != '\r') line[used++] = c;
        }
    }
    return NULL;
}

CS_EXPORT JNIEXPORT jboolean JNICALL Java_pro_cslauncher_app_game_NativeBridge_redirectStdio(JNIEnv *env, jclass c, jstring logPath) {
    const char *path = (*env)->GetStringUTFChars(env, logPath, NULL);
    g_logFd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    (*env)->ReleaseStringUTFChars(env, logPath, path);
    if (g_logFd < 0) { LOGE("cannot open game log"); return JNI_FALSE; }
    int p[2];
    if (pipe(p) != 0) return JNI_FALSE;
    setvbuf(stdout, NULL, _IOLBF, 0); setvbuf(stderr, NULL, _IONBF, 0);
    dup2(p[1], STDOUT_FILENO); dup2(p[1], STDERR_FILENO); close(p[1]);
    if (pthread_create(&g_logThread, NULL, log_reader, (void *)(intptr_t)p[0]) != 0) return JNI_FALSE;
    pthread_detach(g_logThread);
    return JNI_TRUE;
}

/* ---------------------------------------------------------------- VM launch */
typedef struct {
    char *libjvm; char *mainClass;
    char **options; int nOptions;
    char **args; int nArgs;
} LaunchSpec;

static void JNICALL on_vm_exit(jint code) {
    fflush(stdout); fflush(stderr);
    cs_host_jvm_exit(code);
    /* System.exit() must not return; the ":game" process ends here and the launcher process records the result. */
    _exit(code);
}
static void JNICALL on_vm_abort(void) {
    fflush(stdout); fflush(stderr);
    cs_host_jvm_exit(134);
    _exit(134);
}

static char **copy_array(JNIEnv *env, jobjectArray arr, int *n) {
    *n = arr ? (*env)->GetArrayLength(env, arr) : 0;
    char **out = calloc((size_t)*n + 1, sizeof(char *));
    for (int i = 0; i < *n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        const char *u = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(u);
        (*env)->ReleaseStringUTFChars(env, s, u);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

static int run_vm(LaunchSpec *s) {
    void *h = dlopen(s->libjvm, RTLD_NOW | RTLD_GLOBAL);
    if (!h) { fprintf(stderr, "[CSLauncher] Cannot load libjvm: %s\n", dlerror()); return 201; }
    CreateJavaVM_t create = (CreateJavaVM_t)dlsym(h, "JNI_CreateJavaVM");
    if (!create) { fprintf(stderr, "[CSLauncher] libjvm has no JNI_CreateJavaVM\n"); return 202; }

    JavaVMOption *opts = calloc((size_t)s->nOptions + 2, sizeof(JavaVMOption));
    int n = 0;
    for (int i = 0; i < s->nOptions; i++) opts[n++].optionString = s->options[i];
    opts[n].optionString = "exit"; opts[n++].extraInfo = (void *)on_vm_exit;
    opts[n].optionString = "abort"; opts[n++].extraInfo = (void *)on_vm_abort;
    JavaVMInitArgs init = { .version = JNI_VERSION_1_8, .nOptions = n, .options = opts, .ignoreUnrecognized = JNI_FALSE };

    JavaVM *vm = NULL; JNIEnv *env = NULL;
    jint rc = create(&vm, (void **)&env, &init);
    if (rc != JNI_OK) { fprintf(stderr, "[CSLauncher] JNI_CreateJavaVM failed (%d)\n", rc); free(opts); return 203; }

    int exitCode = 0;
    char *cls = strdup(s->mainClass);
    for (char *p = cls; *p; p++) if (*p == '.') *p = '/';
    jclass main = (*env)->FindClass(env, cls);
    free(cls);
    if (!main) { (*env)->ExceptionDescribe(env); exitCode = 204; goto done; }
    jmethodID mid = (*env)->GetStaticMethodID(env, main, "main", "([Ljava/lang/String;)V");
    if (!mid) { (*env)->ExceptionDescribe(env); exitCode = 205; goto done; }
    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    jobjectArray jargs = (*env)->NewObjectArray(env, s->nArgs, strCls, NULL);
    for (int i = 0; i < s->nArgs; i++) {
        jstring a = (*env)->NewStringUTF(env, s->args[i]);
        (*env)->SetObjectArrayElement(env, jargs, i, a);
        (*env)->DeleteLocalRef(env, a);
    }
    fprintf(stderr, "[CSLauncher] Entering %s.main\n", s->mainClass);
    (*env)->CallStaticVoidMethod(env, main, mid, jargs);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); exitCode = 1; }
done:
    /* Waits for non-daemon threads (the game's render thread) like the java launcher does. */
    (*vm)->DestroyJavaVM(vm);
    free(opts);
    return exitCode;
}

static void *vm_thread(void *arg) {
    LaunchSpec *s = arg;
    int code = run_vm(s);
    fflush(stdout); fflush(stderr);
    cs_host_jvm_exit(code);
    return NULL;
}

/* Returns 0 when the VM thread was started; the result arrives later through NativeBridge.onJvmExit. */
CS_EXPORT JNIEXPORT jint JNICALL Java_pro_cslauncher_app_game_NativeBridge_launchJvm(JNIEnv *env, jclass c, jstring libjvm, jobjectArray options,
                                                                                   jstring mainClass, jobjectArray args, jint stackMb) {
    LaunchSpec *s = calloc(1, sizeof(LaunchSpec));
    const char *u = (*env)->GetStringUTFChars(env, libjvm, NULL); s->libjvm = strdup(u); (*env)->ReleaseStringUTFChars(env, libjvm, u);
    u = (*env)->GetStringUTFChars(env, mainClass, NULL); s->mainClass = strdup(u); (*env)->ReleaseStringUTFChars(env, mainClass, u);
    s->options = copy_array(env, options, &s->nOptions);
    s->args = copy_array(env, args, &s->nArgs);
    pthread_attr_t attr; pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, (size_t)(stackMb > 0 ? stackMb : 8) * 1024 * 1024);
    pthread_t t;
    int rc = pthread_create(&t, &attr, vm_thread, s);
    pthread_attr_destroy(&attr);
    if (rc != 0) return rc;
    pthread_detach(t);
    return 0;
}

/* Sets an environment variable in this process (read by the VM, GL4ES and Mesa before they initialise). */
CS_EXPORT JNIEXPORT void JNICALL Java_pro_cslauncher_app_game_NativeBridge_setEnv(JNIEnv *env, jclass c, jstring k, jstring v) {
    const char *ks = (*env)->GetStringUTFChars(env, k, NULL), *vs = (*env)->GetStringUTFChars(env, v, NULL);
    setenv(ks, vs, 1);
    (*env)->ReleaseStringUTFChars(env, k, ks); (*env)->ReleaseStringUTFChars(env, v, vs);
}

/* The game resolves relative paths (logs/, crash-reports/) against the working directory, like a desktop launcher sets it. */
CS_EXPORT JNIEXPORT jboolean JNICALL Java_pro_cslauncher_app_game_NativeBridge_chdir(JNIEnv *env, jclass c, jstring dir) {
    const char *d = (*env)->GetStringUTFChars(env, dir, NULL);
    int rc = chdir(d);
    (*env)->ReleaseStringUTFChars(env, dir, d);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

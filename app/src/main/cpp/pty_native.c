/*
 * Real PTY backend for the WanXiang terminal.
 *
 * Termux-style forkpty semantics: the app creates a master/slave pair and
 * execs the command with the slave as its controlling terminal, so job
 * control, Ctrl+C, SIGWINCH resize and raw mode behave exactly like a real
 * terminal. The app keeps the master fd and drives it through JNI.
 */
#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>

static char **strings_array(JNIEnv *env, jobjectArray array) {
    if (array == NULL) return NULL;
    int n = (*env)->GetArrayLength(env, array);
    char **result = calloc((size_t)n + 1, sizeof(char *));
    if (result == NULL) return NULL;
    for (int i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, array, i);
        if (s == NULL) continue;
        const char *cs = (*env)->GetStringUTFChars(env, s, NULL);
        result[i] = strdup(cs);
        (*env)->ReleaseStringUTFChars(env, s, cs);
        (*env)->DeleteLocalRef(env, s);
    }
    return result;
}

static void free_strings(char **array) {
    if (array == NULL) return;
    for (int i = 0; array[i] != NULL; i++) free(array[i]);
    free(array);
}

static void throw_io(JNIEnv *env, const char *what) {
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, what);
}

/*
 * argv/envp/cwd/columns/rows -> int[]{masterFd, childPid}
 * The child becomes a session leader with the slave as controlling terminal.
 */
JNIEXPORT jintArray JNICALL
Java_top_wanxiang_app_runtime_pty_NativePty_openAndExec(
    JNIEnv *env, jclass clazz,
    jobjectArray argv, jobjectArray envp, jstring cwd,
    jint columns, jint rows) {
    int master = -1, slave = -1;
    if (openpty(&master, &slave, NULL, NULL, NULL) == -1) {
        throw_io(env, "openpty failed");
        return NULL;
    }
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)(columns > 0 ? columns : 80);
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    ioctl(master, TIOCSWINSZ, &ws);

    char **cargv = strings_array(env, argv);
    char **cenvp = strings_array(env, envp);
    const char *ccwd = cwd != NULL ? (*env)->GetStringUTFChars(env, cwd, NULL) : NULL;

    pid_t pid = fork();
    if (pid < 0) {
        close(master);
        close(slave);
        free_strings(cargv);
        free_strings(cenvp);
        if (ccwd != NULL) (*env)->ReleaseStringUTFChars(env, cwd, ccwd);
        throw_io(env, "fork failed");
        return NULL;
    }

    if (pid == 0) {
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        if (master >= 0) close(master);
        if (ccwd != NULL) chdir(ccwd);
        if (cargv != NULL && cargv[0] != NULL) {
            execve(cargv[0], cargv, cenvp);
        }
        _exit(127);
    }

    close(slave);
    if (ccwd != NULL) (*env)->ReleaseStringUTFChars(env, cwd, ccwd);
    free_strings(cargv);
    free_strings(cenvp);

    jint pair[2] = { (jint)master, (jint)pid };
    jintArray result = (*env)->NewIntArray(env, 2);
    if (result != NULL) (*env)->SetIntArrayRegion(env, result, 0, 2, pair);
    return result;
}

/* Reads up to buffer.length bytes from the master; returns bytes read or -1. */
JNIEXPORT jint JNICALL
Java_top_wanxiang_app_runtime_pty_NativePty_readFd(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer) {
    jsize len = (*env)->GetArrayLength(env, buffer);
    if (len <= 0) return 0;
    jbyte *tmp = (jbyte *)malloc((size_t)len);
    if (tmp == NULL) return -1;
    ssize_t n = read((int)fd, tmp, (size_t)len);
    if (n > 0) {
        (*env)->SetByteArrayRegion(env, buffer, 0, (jsize)n, tmp);
    }
    free(tmp);
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_top_wanxiang_app_runtime_pty_NativePty_writeFd(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer, jint offset, jint length) {
    if (length <= 0) return 0;
    jbyte *tmp = (jbyte *)malloc((size_t)length);
    if (tmp == NULL) return -1;
    (*env)->GetByteArrayRegion(env, buffer, offset, length, tmp);
    ssize_t n = write((int)fd, tmp, (size_t)length);
    free(tmp);
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_top_wanxiang_app_runtime_pty_NativePty_resizeFd(
    JNIEnv *env, jclass clazz, jint fd, jint columns, jint rows) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)(columns > 0 ? columns : 80);
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    return ioctl((int)fd, TIOCSWINSZ, &ws) == 0 ? 0 : -1;
}

/*
 * The child called setsid(), so -pid addresses its whole session group.
 * Signal 0 is used as an existence probe: 0 means alive, -1 means gone.
 */
JNIEXPORT jint JNICALL
Java_top_wanxiang_app_runtime_pty_NativePty_killPid(
    JNIEnv *env, jclass clazz, jint pid, jint sig) {
    if (pid <= 0) return -1;
    if (kill(-pid, sig) == 0) return 0;
    return kill(pid, sig) == 0 ? 0 : -1;
}

JNIEXPORT void JNICALL
Java_top_wanxiang_app_runtime_pty_NativePty_closeFd(
    JNIEnv *env, jclass clazz, jint fd) {
    if (fd >= 0) close(fd);
}

/* Reaps an already-dead child so it does not linger as a zombie. */
JNIEXPORT void JNICALL
Java_top_wanxiang_app_runtime_pty_NativePty_waitPid(
    JNIEnv *env, jclass clazz, jint pid) {
    if (pid > 0) {
        int status = 0;
        waitpid(pid, &status, 0);
    }
}

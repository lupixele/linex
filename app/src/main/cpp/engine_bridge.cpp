#include <jni.h>
#include <signal.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <errno.h>
#include <string.h>
#include <android/log.h>

#define TAG "LinuxDroid_NativeEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

extern "C" {

/**
 * Sends POSIX signals directly to a specific PID or Process Group (PGID if negative).
 * Used for SIGSTOP (19) to freeze processes, SIGCONT (18) to instantly resume,
 * and SIGTERM (15) / SIGKILL (9) to shut down.
 */
JNIEXPORT jboolean JNICALL
Java_com_linuxdroid_app_core_ProcessController_nativeSendSignal(
        JNIEnv *env,
        jobject thiz,
        jint pid_or_pgid,
        jint sig) {
    
    LOGI("nativeSendSignal: Dispatching signal %d to target %d", sig, pid_or_pgid);
    
    int result = kill((pid_t)pid_or_pgid, sig);
    if (result == 0) {
        LOGI("nativeSendSignal: Successfully dispatched signal %d to %d", sig, pid_or_pgid);
        return JNI_TRUE;
    } else {
        LOGE("nativeSendSignal: Failed to dispatch signal %d to %d (errno: %d, %s)",
             sig, pid_or_pgid, errno, strerror(errno));
        return JNI_FALSE;
    }
}

/**
 * Returns the process group ID for a given process ID.
 */
JNIEXPORT jint JNICALL
Java_com_linuxdroid_app_core_ProcessController_nativeGetProcessGroup(
        JNIEnv *env,
        jobject thiz,
        jint pid) {
    pid_t pgid = getpgid((pid_t)pid);
    if (pgid < 0) {
        LOGW("nativeGetProcessGroup: Failed to get pgid for pid %d (errno: %d)", pid, errno);
        return -1;
    }
    return (jint)pgid;
}

/**
 * Sets process group ID for process management and child trapping.
 */
JNIEXPORT jboolean JNICALL
Java_com_linuxdroid_app_core_ProcessController_nativeSetPgid(
        JNIEnv *env,
        jobject thiz,
        jint pid,
        jint pgid) {
    int res = setpgid((pid_t)pid, (pid_t)pgid);
    if (res == 0) {
        return JNI_TRUE;
    } else {
        LOGE("nativeSetPgid: Failed to set pgid for pid %d to %d (errno: %d)", pid, pgid, errno);
        return JNI_FALSE;
    }
}

/**
 * Verifies if a given process ID is still alive.
 */
JNIEXPORT jboolean JNICALL
Java_com_linuxdroid_app_core_ProcessController_nativeCheckProcessAlive(
        JNIEnv *env,
        jobject thiz,
        jint pid) {
    if (pid <= 0) return JNI_FALSE;
    int res = kill((pid_t)pid, 0);
    if (res == 0 || errno == EPERM) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

/**
 * Native chmod implementation to ensure bootstrap scripts and binaries have
 * the requisite executable and POSIX sticky bits without relying on external sh.
 */
JNIEXPORT jboolean JNICALL
Java_com_linuxdroid_app_core_ProcessController_nativeSetPermissions(
        JNIEnv *env,
        jobject thiz,
        jstring path_str,
        jint mode) {
    if (path_str == nullptr) return JNI_FALSE;

    const char *path = env->GetStringUTFChars(path_str, nullptr);
    if (path == nullptr) return JNI_FALSE;

    int res = chmod(path, (mode_t)mode);
    if (res != 0) {
        LOGE("nativeSetPermissions: chmod failed on %s to mode %o (errno: %d, %s)",
             path, mode, errno, strerror(errno));
    } else {
        LOGI("nativeSetPermissions: chmod %o succeeded for %s", mode, path);
    }

    env->ReleaseStringUTFChars(path_str, path);
    return (res == 0) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Native symlink creation helper for rootfs and socket path linkage.
 */
JNIEXPORT jboolean JNICALL
Java_com_linuxdroid_app_core_ProcessController_nativeCreateSymlink(
        JNIEnv *env,
        jobject thiz,
        jstring target_str,
        jstring linkpath_str) {
    if (target_str == nullptr || linkpath_str == nullptr) return JNI_FALSE;

    const char *target = env->GetStringUTFChars(target_str, nullptr);
    if (target == nullptr) return JNI_FALSE;

    const char *linkpath = env->GetStringUTFChars(linkpath_str, nullptr);
    if (linkpath == nullptr) {
        env->ReleaseStringUTFChars(target_str, target);
        return JNI_FALSE;
    }

    int res = symlink(target, linkpath);
    if (res != 0) {
        LOGE("nativeCreateSymlink: symlink(%s, %s) failed (errno: %d, %s)",
             target, linkpath, errno, strerror(errno));
    }

    env->ReleaseStringUTFChars(target_str, target);
    env->ReleaseStringUTFChars(linkpath_str, linkpath);
    return (res == 0) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Native input event stubs for X11SurfaceView
 */
JNIEXPORT void JNICALL
Java_com_linuxdroid_app_ui_session_X11SurfaceView_nativePointerMotion(
        JNIEnv *env,
        jobject thiz,
        jfloat x,
        jfloat y,
        jboolean is_relative) {
    // Stub: pass pointer motion to active X11 server instance
}

JNIEXPORT void JNICALL
Java_com_linuxdroid_app_ui_session_X11SurfaceView_nativePointerButton(
        JNIEnv *env,
        jobject thiz,
        jint button_index,
        jboolean is_down) {
    // Stub: pass pointer button event to active X11 server instance
}

JNIEXPORT void JNICALL
Java_com_linuxdroid_app_ui_session_X11SurfaceView_nativePointerScroll(
        JNIEnv *env,
        jobject thiz,
        jfloat distance_y) {
    // Stub: pass pointer scroll event to active X11 server instance
}

JNIEXPORT void JNICALL
Java_com_linuxdroid_app_ui_session_X11SurfaceView_nativeKeyEvent(
        JNIEnv *env,
        jobject thiz,
        jint key_code,
        jboolean is_down,
        jint meta_state) {
    // Stub: pass key event to active X11 server instance
}

} // extern "C"

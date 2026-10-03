/* SPDX-License-Identifier: GPL-2.0-or-later
 * Embeds QEMU 11.0.3 system/main.c's lock/main-loop protocol in one managed
 * Android service process. The service must exit after this invocation.
 */
#include "qemu/osdep.h"
#include "qemu-main.h"
#include "qemu/main-loop.h"
#include "system/replay.h"
#include "system/system.h"
#include <jni.h>
#include <spawn.h>
#include <stdatomic.h>

int (*qemu_main)(void);
static atomic_bool launched = false;

/* JNI's GetStringUTFChars uses modified UTF-8, which is unsuitable for POSIX
 * paths containing supplementary Unicode. Encode UTF-16 as standard UTF-8. */
static char *copy_utf8(JNIEnv *env, jstring value, size_t *bytes)
{
    jsize length = (*env)->GetStringLength(env, value);
    if (length < 1 || length > 4096) { return NULL; }
    const jchar *chars = (*env)->GetStringChars(env, value, NULL);
    if (!chars) { return NULL; }
    char *result = malloc((size_t)length * 4 + 1);
    size_t used = 0;
    if (!result) { (*env)->ReleaseStringChars(env, value, chars); return NULL; }
    for (jsize i = 0; i < length; i++) {
        uint32_t point = chars[i];
        if (point == 0) { goto invalid; }
        if (point >= 0xd800 && point <= 0xdbff) {
            if (++i >= length || chars[i] < 0xdc00 || chars[i] > 0xdfff) { goto invalid; }
            point = 0x10000 + ((point - 0xd800) << 10) + chars[i] - 0xdc00;
        } else if (point >= 0xdc00 && point <= 0xdfff) { goto invalid; }
        if (point < 0x80) { result[used++] = (char)point; }
        else if (point < 0x800) {
            result[used++] = (char)(0xc0 | (point >> 6));
            result[used++] = (char)(0x80 | (point & 0x3f));
        } else if (point < 0x10000) {
            result[used++] = (char)(0xe0 | (point >> 12));
            result[used++] = (char)(0x80 | ((point >> 6) & 0x3f));
            result[used++] = (char)(0x80 | (point & 0x3f));
        } else {
            result[used++] = (char)(0xf0 | (point >> 18));
            result[used++] = (char)(0x80 | ((point >> 12) & 0x3f));
            result[used++] = (char)(0x80 | ((point >> 6) & 0x3f));
            result[used++] = (char)(0x80 | (point & 0x3f));
        }
        if (used > 4096) { goto invalid; }
    }
    result[used] = '\0';
    *bytes = used;
    (*env)->ReleaseStringChars(env, value, chars);
    return result;
invalid:
    free(result);
    (*env)->ReleaseStringChars(env, value, chars);
    return NULL;
}

static jint reject(JNIEnv *env, const char *message)
{
    jclass type = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
    if (type) { (*env)->ThrowNew(env, type, message); }
    return -1;
}

JNIEXPORT jint JNICALL Java_com_linex_vm_NativeVm_run(
    JNIEnv *env, jobject owner, jobjectArray args)
{
    (void) owner;
    if (!args) { return reject(env, "Missing VM arguments"); }
    jsize count = (*env)->GetArrayLength(env, args);
    if (count < 1 || count > 64) { return reject(env, "Invalid VM argument count"); }
    char **argv = calloc((size_t)count + 1, sizeof(char *));
    if (!argv) { return reject(env, "VM argument allocation failed"); }
    size_t total = 0;
    for (jsize i = 0; i < count; i++) {
        jstring arg = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        if (!arg) { goto invalid; }
        size_t length = 0;
        argv[i] = copy_utf8(env, arg, &length);
        total += length;
        (*env)->DeleteLocalRef(env, arg);
        if (!argv[i] || total > 32768) { goto invalid; }
    }
    if (atomic_exchange(&launched, true)) { goto invalid; }
    qemu_init((int)count, argv);
    bql_unlock();
    replay_mutex_unlock();
    replay_mutex_lock();
    bql_lock();
    int status = qemu_main_loop();
    qemu_cleanup(status);
    bql_unlock();
    replay_mutex_unlock();
    /* QEMU retains pointers into argv for its process lifetime. Do not free or
     * attempt reinitialization; Kotlin tears down this managed process. */
    return status;

invalid:
    for (jsize i = 0; i < count; i++) { free(argv[i]); }
    free(argv);
    if ((*env)->ExceptionCheck(env)) { return -1; }
    return reject(env, "Invalid VM arguments or reused VM process");
}

/* Host helpers are forbidden in this slice. Link-time wrapping applies also to
 * static GLib and QEMU code, so an accidental helper path fails without fork.
 * Android pthread creation remains supported through libc's private internals.
 */
pid_t __wrap_fork(void) { errno = ENOTSUP; return -1; }
pid_t __wrap_vfork(void) { errno = ENOTSUP; return -1; }
int __wrap_posix_spawn(pid_t *p, const char *s, const posix_spawn_file_actions_t *a,
                      const posix_spawnattr_t *b, char *const v[], char *const e[])
{ (void)p; (void)s; (void)a; (void)b; (void)v; (void)e; return ENOTSUP; }
int __wrap_posix_spawnp(pid_t *p, const char *s, const posix_spawn_file_actions_t *a,
                       const posix_spawnattr_t *b, char *const v[], char *const e[])
{ return __wrap_posix_spawn(p, s, a, b, v, e); }
int __wrap_system(const char *s) { (void)s; errno = ENOTSUP; return -1; }
FILE *__wrap_popen(const char *s, const char *m)
{ (void)s; (void)m; errno = ENOTSUP; return NULL; }
int __wrap_execve(const char *s, char *const v[], char *const e[])
{ (void)s; (void)v; (void)e; errno = ENOTSUP; return -1; }
int __wrap_execvp(const char *s, char *const v[])
{ (void)s; (void)v; errno = ENOTSUP; return -1; }

/* POSIX shared-memory backends are unsupported by Bionic. Anonymous guest RAM
 * does not call these functions. Explicit failure prevents a fake shm backend. */
int shm_open(const char *s, int f, mode_t m)
{ (void)s; (void)f; (void)m; errno = ENOTSUP; return -1; }
int shm_unlink(const char *s) { (void)s; errno = ENOTSUP; return -1; }

/*
 * ptylauncher.c  —  LobiShell Linux Plugin edition
 *
 * JNI helper that:
 *   1. Calls forkpty() to create a child process with a PTY.
 *   2. In the child: sets up environment + calls execve() on the requested binary.
 *   3. In the parent: returns the master PTY fd and child PID to Java.
 *   4. Provides resizePty(), killProcess(), and dupFd() helpers.
 *
 * JNI class: de.lobianco.saftssh.linux.PtyLauncher
 *
 * Differences from app/src/main/cpp/ptylauncher.c:
 *   - All JNI symbol names embed "linux_PtyLauncher" (plugin package) instead of
 *     "data_mosh_PtyProcess" (main app package).
 *   - waitpidNonBlocking removed (not needed by the plugin service).
 *   - dupFd() added: returns dup(fd) so the plugin can hand a duplicate fd to the
 *     client over Binder (ParcelFileDescriptor) while retaining the original masterFd
 *     for resize/kill operations.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pty.h>          /* forkpty — Android bionic pty.h */
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <android/log.h>
#include <errno.h>

#define TAG "PtyLauncher"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ──────────────────────────────────────────────────────────────────────────
 * forkAndExec
 * Returns int[2] = { masterFd, pid } on success, null on failure.
 * ────────────────────────────────────────────────────────────────────────── */
JNIEXPORT jintArray JNICALL
Java_de_lobianco_saftssh_linux_PtyLauncher_forkAndExec(
        JNIEnv *env,
        jobject thiz,
        jstring jBinary,
        jobjectArray jArgs,
        jobjectArray jEnv,
        jint cols,
        jint rows,
        jstring jLinker)
{
    // Convert binary path
    const char *binary = (*env)->GetStringUTFChars(env, jBinary, NULL);

    // Optional system-linker path for the "linker-exec" trick (empty/null = direct execve).
    // On API 29+ an app's data-dir binaries are noexec (SELinux execute_no_trans), so we run
    // `execve("/system/bin/linker64", [linker, binary, args…])` instead — the allowed system
    // linker loads + runs the (only mmap'd) data-dir binary. See PHASE2.md.
    const char *linker = (jLinker != NULL) ? (*env)->GetStringUTFChars(env, jLinker, NULL) : NULL;

    // Convert args array → char* argv[]
    int argc = (*env)->GetArrayLength(env, jArgs);
    char **argv = (char **)malloc((argc + 1) * sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, jArgs, i);
        argv[i] = (char *)(*env)->GetStringUTFChars(env, s, NULL);
    }
    argv[argc] = NULL;

    // Convert env array → char* envp[]
    int envc = (*env)->GetArrayLength(env, jEnv);
    char **envp = (char **)malloc((envc + 1) * sizeof(char *));
    for (int i = 0; i < envc; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, jEnv, i);
        envp[i] = (char *)(*env)->GetStringUTFChars(env, s, NULL);
    }
    envp[envc] = NULL;

    // Build the linker-exec argv in the PARENT (the child after fork must stay async-signal-safe →
    // no malloc there). largv = [linker, binary, args[1], args[2], …, NULL]; the target's argv[0]
    // becomes the absolute binary path (args[0] is dropped — harmless for shells).
    char **largv = NULL;
    if (linker != NULL && linker[0] != '\0') {
        largv = (char **)malloc((argc + 2) * sizeof(char *));
        largv[0] = (char *)linker;
        largv[1] = (char *)binary;
        for (int i = 1; i < argc; i++) largv[i + 1] = argv[i];
        largv[argc + 1] = NULL;
    }

    // Set up initial window size
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)cols;
    ws.ws_row = (unsigned short)rows;

    // Captured before forking so the child (see below) can tell whether ITS parent — this plugin
    // process — has already died by the time it gets scheduled (fork()/getppid() race, see there).
    pid_t parentPidBeforeFork = getpid();

    int masterFd = -1;
    pid_t pid = forkpty(&masterFd, NULL, NULL, &ws);

    if (pid < 0) {
        LOGE("forkpty() failed: %s", strerror(errno));
        free(argv);
        free(envp);
        free(largv);
        if (linker != NULL) (*env)->ReleaseStringUTFChars(env, jLinker, linker);
        (*env)->ReleaseStringUTFChars(env, jBinary, binary);
        return NULL;
    }

    if (pid == 0) {
        // ── Child process ── (async-signal-safe only: prctl + getppid + execve)
        //
        // Without this, force-closing (or crashing) the plugin process sends it SIGKILL directly —
        // no Kotlin/Java code runs at all, so LinuxSessionService's killProcess()/destroy() cleanup
        // never fires. forkpty() already made this child (and sshd/runit's own script chain after
        // it) a session leader in a brand-new process group via setsid(), so it's fully independent
        // of the app's process tree from the kernel's point of view — it becomes a plain orphan,
        // reparented to init, and keeps running (and keeps LISTENing, for sshd) forever, regardless
        // of whether the userland's files still exist. Confirmed on-device: force-close app + plugin
        // + delete the userland, and sshd was still reachable.
        //
        // PR_SET_PDEATHSIG makes the kernel deliver SIGKILL to (this thread of) the child the moment
        // its parent thread dies, for ANY reason including SIGKILL — exactly this scenario. It does
        // NOT propagate to further descendants this process itself forks later (sshd forking a
        // per-connection handler, e.g.) — that already-documented gap (see killProcess()'s doc) is
        // narrower and needs cgroup/namespace containment to close fully; this fixes the reported
        // case, which is the long-lived LISTENER process itself outliving the app.
        prctl(PR_SET_PDEATHSIG, SIGKILL);
        // Race: the parent may have died between fork() returning here and the prctl() call above
        // actually taking effect, in which case no SIGKILL is coming. getppid() returning something
        // other than the pid we captured before forking means exactly that (a dead parent's children
        // are reparented, on Android as elsewhere) — self-terminate rather than run detached forever.
        if (getppid() != parentPidBeforeFork) {
            _exit(1);
        }
        if (largv != NULL) {
            execve(linker, largv, envp);   // linker-exec
        } else {
            execve(binary, argv, envp);    // direct
        }
        // If execve returns it failed; exit immediately so we don't run twice.
        _exit(127);
    }

    // ── Parent process ──
    LOGI("forkpty ok: pid=%d masterFd=%d", pid, masterFd);

    // Release JNI strings (parent only — child execs away and never returns here)
    (*env)->ReleaseStringUTFChars(env, jBinary, binary);
    for (int i = 0; i < argc; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, jArgs, i);
        (*env)->ReleaseStringUTFChars(env, s, argv[i]);
    }
    for (int i = 0; i < envc; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, jEnv, i);
        (*env)->ReleaseStringUTFChars(env, s, envp[i]);
    }
    free(argv);
    free(envp);
    free(largv);
    if (linker != NULL) (*env)->ReleaseStringUTFChars(env, jLinker, linker);

    // Return [masterFd, pid]
    jintArray result = (*env)->NewIntArray(env, 2);
    jint buf[2] = { masterFd, (jint)pid };
    (*env)->SetIntArrayRegion(env, result, 0, 2, buf);
    return result;
}

/* ──────────────────────────────────────────────────────────────────────────
 * resizePty — ioctl TIOCSWINSZ → kernel sends SIGWINCH to child process group
 * ────────────────────────────────────────────────────────────────────────── */
JNIEXPORT void JNICALL
Java_de_lobianco_saftssh_linux_PtyLauncher_resizePty(
        JNIEnv *env,
        jobject thiz,
        jint masterFd,
        jint cols,
        jint rows)
{
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short)cols;
    ws.ws_row = (unsigned short)rows;
    if (ioctl(masterFd, TIOCSWINSZ, &ws) < 0) {
        LOGE("TIOCSWINSZ failed: %s", strerror(errno));
    }
}

/* ──────────────────────────────────────────────────────────────────────────
 * killProcess — SIGKILL the child's whole process GROUP, not just the child
 *
 * forkpty() (via its internal login_tty()) calls setsid() on the child, making it a session
 * leader with its own new process group whose pgid equals its own pid. Anything the shell
 * launches inherits that same pgid unless it explicitly calls setsid()/setpgid() itself.
 *
 * Killing only the single tracked pid (the old behaviour) left every ordinary descendant of a
 * program that forks-and-returns without detaching still running: a shell that starts a VNC
 * server (vncserver-style wrapper scripts routinely fork the real Xvnc/x11vnc process and let
 * the parent shell command return immediately) survived "stopping" the session, invisibly, with
 * no tracked pid and no UI indication — reported as "VNC lief im Hintergrund ohne jegliches
 * Zeichen, obwohl das Userland laut App definitiv gestoppt war".
 *
 * kill(-pid, SIG) targets the whole process group instead of one process (POSIX kill(2)) — safe
 * here specifically because pid IS that group's pgid (forkpty made it the leader). This still
 * cannot reach a process that properly double-forks and calls its own setsid() to fully detach
 * into a brand-new session (classic Unix daemonization) — that process deliberately leaves this
 * group and needs namespace/cgroup-level containment to catch, which is a larger change. This
 * fixes the far more common case: a program that forks a worker but never bothers to detach it
 * from the group it was born into.
 * ────────────────────────────────────────────────────────────────────────── */
JNIEXPORT void JNICALL
Java_de_lobianco_saftssh_linux_PtyLauncher_killProcess(
        JNIEnv *env,
        jobject thiz,
        jint pid)
{
    if (kill(-(pid_t)pid, SIGKILL) < 0 && errno == ESRCH) {
        // No process group with that pgid (e.g. the child already reassigned its own group before
        // dying, or this build's forkpty somehow didn't make it the leader) — fall back to the
        // single-process kill so a legitimate, expected case never regresses into a silent no-op.
        kill((pid_t)pid, SIGKILL);
    }
}

/* ──────────────────────────────────────────────────────────────────────────
 * dupFd — duplicate a file descriptor
 *
 * The plugin retains masterFd for resize/kill but must hand a SEPARATE fd to
 * the Binder client (via ParcelFileDescriptor). ParcelFileDescriptor.adoptFd()
 * takes ownership and closes the fd when the PFD is closed; without dup() both
 * sides would share the same fd number and the first close would break resize.
 *
 * Usage in LinuxSessionService:
 *   val clientFd = PtyLauncher.dupFd(masterFd)    // new fd number
 *   return ParcelFileDescriptor.adoptFd(clientFd) // PFD owns clientFd
 *   // masterFd is still valid in the service for resizePty / killProcess
 * ────────────────────────────────────────────────────────────────────────── */
JNIEXPORT jint JNICALL
Java_de_lobianco_saftssh_linux_PtyLauncher_dupFd(
        JNIEnv *env,
        jobject thiz,
        jint fd)
{
    int newFd = dup((int)fd);
    if (newFd < 0) {
        LOGE("dup(%d) failed: %s", fd, strerror(errno));
    }
    return (jint)newFd;
}

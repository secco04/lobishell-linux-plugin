package de.lobianco.saftssh.linux

/**
 * JNI bridge to the native PTY launcher (libptylauncher.so).
 *
 * All four native functions correspond 1-to-1 with the JNI symbols in
 * ptylauncher.c (Java_de_lobianco_saftssh_linux_PtyLauncher_*).
 */
object PtyLauncher {

    init {
        System.loadLibrary("ptylauncher")
    }

    /**
     * Fork a child process attached to a new PTY and exec [binary].
     *
     * @param binary  Full path to the executable (e.g. "/system/bin/sh").
     * @param args    argv array passed to execve (index 0 is conventionally the program name).
     * @param env     envp array ("KEY=VALUE" strings).
     * @param cols    Initial terminal column count.
     * @param rows    Initial terminal row count.
     * @param linker  System linker path for the linker-exec trick (e.g. "/system/bin/linker64");
     *                empty string = direct execve. Needed to run $PREFIX binaries on API 29+.
     * @return        IntArray of size 2: [masterFd, pid], or null on forkpty failure.
     */
    external fun forkAndExec(
        binary: String,
        args: Array<String>,
        env: Array<String>,
        cols: Int,
        rows: Int,
        linker: String,
    ): IntArray?

    /**
     * Resize the PTY window (ioctl TIOCSWINSZ). The kernel will send SIGWINCH to
     * the child's process group so full-screen apps (vi, top, etc.) reflow.
     */
    external fun resizePty(masterFd: Int, cols: Int, rows: Int)

    /** Send SIGKILL to [pid]. */
    external fun killProcess(pid: Int)

    /**
     * Return a duplicate of [fd] via dup(2).
     *
     * Required for the fd handover pattern in [LinuxSessionService.getPtyFd]:
     * the service keeps [masterFd] for resize/kill, and passes a dup'd copy to
     * the Binder client wrapped in a [android.os.ParcelFileDescriptor].
     * When the client closes the PFD it only closes the dup — masterFd stays valid.
     */
    external fun dupFd(fd: Int): Int
}

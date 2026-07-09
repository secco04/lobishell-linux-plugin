// ILinuxSessionService.aidl
// AIDL contract — must stay byte-for-byte identical to the main app's copy.
package de.lobianco.saftssh.linux;

import de.lobianco.saftssh.linux.ILinuxSession;
import de.lobianco.saftssh.linux.ILinuxSessionCallback;

interface ILinuxSessionService {
    /**
     * Create a PTY-backed Linux session for the userland [userlandId] (one rootfs per connection).
     * [rootChroot] requests real-root chroot mode for THIS session (a genuine root shell, not
     * proot's ptrace-emulated one) instead of the default no-root proot — a per-connection choice.
     * Only honored when the plugin is the root build AND the device has working root access;
     * otherwise createSession() fails outright (does NOT silently fall back to proot — a session
     * that looks like what was requested but silently isn't would be misleading).
     * [callback] receives setup progress (may be null).
     */
    ILinuxSession createSession(int cols, int rows, String cwd, String userlandId, boolean rootChroot, in ILinuxSessionCallback callback);

    /** Ids of all installed userlands. */
    String[] listUserlandIds();

    /** Size in bytes of one userland (rootfs + tmp). */
    long userlandSize(String userlandId);

    /** Delete one userland. Returns bytes freed. */
    long clearUserland(String userlandId);

    /**
     * Starts (or no-ops if already running) a persistent SSH server inside userland [userlandId],
     * independent of any interactive createSession() PTY — it survives tab close/open and keeps
     * running via its own proot invocation. [authMode] is "password" or "pubkey"; [secret] is the
     * plaintext password or the public-key contents, respectively. [port] must be >= 1024 (proot's
     * fake root does not grant real CAP_NET_BIND_SERVICE for privileged ports).
     */
    boolean startSshd(String userlandId, int port, String authMode, String secret);

    /** Stops the persistent SSH server for userland [userlandId], if running. */
    boolean stopSshd(String userlandId);

    /** True if a persistent SSH server is currently running for userland [userlandId]. */
    boolean isSshdRunning(String userlandId);

    /**
     * Starts (or no-ops if already running) the runit service supervisor (`runsvdir /etc/service`)
     * for userland [userlandId], independent of any interactive createSession() PTY — same
     * own-proot-invocation pattern as [startSshd]. Once running, any service directory the user
     * creates under /etc/service/<name>/run (a plain executable script ending in `exec <daemon>`)
     * is auto-supervised: started, and restarted if it dies. No-root: this only works within
     * proot's own process/mount view, not real Linux namespaces or cgroups.
     */
    boolean startRunitSupervisor(String userlandId);

    /** Stops the runit supervisor for userland [userlandId] — supervised services also stop. */
    boolean stopRunitSupervisor(String userlandId);

    /** True if the runit supervisor is currently running for userland [userlandId]. */
    boolean isRunitSupervisorRunning(String userlandId);

    /**
     * Best-effort, informational-only root detection (su binary / Magisk / test-keys build tags) —
     * NOT the check createSession()'s [rootChroot] actually gates on (that uses a real `su -c id`
     * probe internally); this is a cheap hint only, not wired to any main-app behavior.
     */
    boolean isDeviceRooted();
}

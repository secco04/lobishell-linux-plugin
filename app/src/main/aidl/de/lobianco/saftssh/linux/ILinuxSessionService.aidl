// ILinuxSessionService.aidl
// AIDL contract — must stay byte-for-byte identical to the main app's copy.
package de.lobianco.saftssh.linux;

import de.lobianco.saftssh.linux.ILinuxSession;
import de.lobianco.saftssh.linux.ILinuxSessionCallback;

interface ILinuxSessionService {
    /**
     * Create a PTY-backed Linux session for the userland [userlandId] (one rootfs per connection).
     * [callback] receives setup progress (may be null).
     */
    ILinuxSession createSession(int cols, int rows, String cwd, String userlandId, in ILinuxSessionCallback callback);

    /** Ids of all installed userlands. */
    String[] listUserlandIds();

    /** Size in bytes of one userland (rootfs + tmp). */
    long userlandSize(String userlandId);

    /** Delete one userland. Returns bytes freed. */
    long clearUserland(String userlandId);
}

// ILinuxSessionCallback.aidl
// AIDL contract — must stay byte-for-byte identical to the main app's copy.
package de.lobianco.saftssh.linux;

/** Progress notifications during session setup (rootfs download/extract), shown in the terminal. */
oneway interface ILinuxSessionCallback {
    void onProgress(String line);
}

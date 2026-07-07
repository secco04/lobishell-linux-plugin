// ILinuxSession.aidl
// AIDL contract — must stay byte-for-byte identical to the main LobiShell app's copy.
package de.lobianco.saftssh.linux;

import android.os.ParcelFileDescriptor;

interface ILinuxSession {
    ParcelFileDescriptor getPtyFd();
    int getPid();
    void resize(int cols, int rows);
    void destroy();
}

# LobiShell Linux Plugin

This is a **standalone Android project** — open `linux-plugin/` as its own Android Studio
project. It is NOT a module of the main LobiShell app.

Before publishing, move this to its own Git repository. It must stay out of the paid main
app's repo and GPL boundary. The GPL bootstrap code for Phase 2 must only ever live here,
never in the commercial LobiShell app.

---

## What this is

The engine for LobiShell's `LINUX_PLUGIN` connection type. It exposes a bound AIDL service
(`LinuxSessionService`) that forkpty()'s `/system/bin/sh` and returns the PTY master fd to
the main app over Binder.

**applicationId:** `de.lobianco.saftssh.linux`

---

## Phases

### Phase 1b (this stub)
- forkpty + `/system/bin/sh` over Binder
- Clean env matching the main app's `LocalShellService`: `TERM`, `HOME`, `PATH`, `TMPDIR`,
  `COLUMNS`, `LINES`
- fd handover via `dup(2)`: service retains `masterFd` for resize/kill; client receives a
  duplicate fd wrapped in `ParcelFileDescriptor`

### Phase 2 (NOT here)
Bootstrap + `linker-exec` + `termux-exec` for a real apt/bash/Python userland. This GPL
bootstrap must only ever live in this plugin repo, never in the paid main app. Phase 2 will
set `PREFIX` to a real path inside the plugin's `filesDir`.

---

## AIDL contract

The two AIDL files under `app/src/main/aidl/de/lobianco/saftssh/linux/` **must stay
byte-for-byte identical** to the main app's copies. Any change to the interface requires a
coordinated update in both projects.

```
ILinuxSessionService  createSession(int cols, int rows, String cwd) → ILinuxSession
ILinuxSession         getPtyFd() → ParcelFileDescriptor
                      getPid()   → int
                      resize(int cols, int rows)
                      destroy()
```

---

## Building

Open this directory as its own Android Studio project (File → Open → select `linux-plugin/`).
Requires NDK for the native PTY launcher (`libptylauncher.so`).

The plugin APK must be installed on the device alongside the main LobiShell app. The main app
binds the service using the action `de.lobianco.saftssh.linux.BIND_SESSION_SERVICE`.

---

## Permission model

The service is protected by the custom permission `de.lobianco.saftssh.linux.READ_BINARY`
(protectionLevel `normal`). Both apps may be signed with different keys (plugin is open-source,
main app is commercial), so `signature` protection level is not used. The main app must declare
`<uses-permission android:name="de.lobianco.saftssh.linux.READ_BINARY" />` in its manifest.

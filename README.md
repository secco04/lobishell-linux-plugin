# Linux Plugin for LobiShell

This is a **standalone Android project** — open `linux-plugin/` as its own Android Studio
project. It is NOT a module of the main LobiShell app.


---

## What this is

The engine for LobiShell's `LINUX_PLUGIN` connection type. It exposes a bound AIDL service
(`LinuxSessionService`) that forkpty()'s `/system/bin/sh` and returns the PTY master fd to
the main app over Binder.

**applicationId:** `de.lobianco.saftssh.linux`

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

# Linux userland (proot + Ubuntu rootfs)

The plugin runs a real Ubuntu userland (bash, coreutils, `apt`-installable packages) via
[**proot**](https://proot-me.github.io/) — a ptrace-based syscall passthrough that lets an
unprivileged process fake `chroot`/`bind mount`/fake-root without any kernel module or root
access. proot execs its own loader, which `mmap`s the guest Ubuntu binaries directly, sidestepping
the app data-dir's `noexec` restriction entirely — no linker-exec trick, no LD_PRELOAD shim needed.

`libproot.so` / `libproot-loader.so` (prebuilt, `app/src/main/jniLibs/`), plus their runtime
deps `libtalloc.so.2-*` and `libandroid-shmem.so-*` (`app/src/main/assets/`), are prebuilt
binaries sourced from **Termux's** Android build of proot (their fork of
[proot-me/proot](https://github.com/proot-me/proot)) and its dependencies — confirmed by
`/data/data/com.termux/...` paths and `termux-exec` checks embedded directly in the binaries.
**No Termux application source code (UI, services, etc.) is used anywhere in this project** —
only these three prebuilt native libraries. proot itself is **GPL-2.0** — see
[LICENSE](LICENSE) for the full breakdown (talloc is LGPL-3.0-or-later, android-shmem is
BSD-3-Clause). Bundling the GPL proot binary is why this plugin is its own repo/app, entirely
separate from the commercial main LobiShell app: a GPL binary is bundled and shipped here, so
this whole project must be (and is) GPL-2.0-or-later itself.

On first connect, `RootfsInstaller` downloads and extracts a minimal Ubuntu rootfs, then proot
launches `/bin/bash --login` inside it. Packages installed via `apt` afterwards (Ubuntu's own
official packages, e.g. `openssh-server`) are under their own individual upstream licenses —
standard Debian/Ubuntu archive terms, nothing this plugin needs to redistribute since `apt`
fetches them itself over the network at runtime, not bundled in this repo.

## Fallback

If the rootfs can't be installed/started (no network on first run, or a genuine incompatibility
like the [16 KB page size issue](app/src/main/java/de/lobianco/saftssh/linux/LinuxSessionService.kt)),
the plugin falls back to a plain `/system/bin/sh` — no proot, no GPL code involved in that path.

## Known limitations / caveats

- **16 KB memory page devices:** Ubuntu's official prebuilt glibc is built for 4 KB pages only;
  a genuinely 16 KB-page kernel rejects its `mmap()` segments. Detected explicitly with a clear
  error message instead of a raw crash trace — not something this plugin can patch around.
- **Cannot be built/tested in the assistant environment** — verify on a device built from Android Studio.

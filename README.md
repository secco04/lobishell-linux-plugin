# LobiShell Linux Plugin

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

# Linux userland (bootstrap + linker-exec + termux-exec)

The plugin can run a real Linux userland (bash, coreutils, `apt`-installable packages) instead of just `/system/bin/sh`. This needs a **Termux-style bootstrap** (the prebuilt binaries) which is NOT bundled in this repo and must be supplied by the user.

## 2. Supplying the bootstrap (required to activate Phase 2)

- The installer ([BootstrapInstaller.kt](app/src/main/java/de/lobianco/saftssh/linux/BootstrapInstaller.kt)) reads an asset named `bootstrap-<arch>.zip` from `linux-plugin/app/src/main/assets/`, where `<arch>` is one of: `aarch64` (arm64-v8a), `arm` (armeabi-v7a), `x86_64`, `i686` (x86). Place the zip(s) for the device ABIs you target there.
- Easiest source: Termux's published bootstraps (e.g. the `bootstrap-*.zip` release assets in the `termux/termux-packages` project). Note the **prefix-relocation caveat** in section 5 — a stock Termux bootstrap is compiled for `/data/data/com.termux/files/usr`, while this plugin's `$PREFIX` is `/data/data/de.lobianco.saftssh.linux/files/usr`. Many binaries hardcode the Termux path; for a fully reliable userland you may need a bootstrap built with this plugin's `TERMUX_PREFIX`, or rely on the termux-exec runtime config (section 4).
- Zip layout (Termux format): regular entries are files relative to `$PREFIX` (e.g. `bin/bash`, `lib/libc++_shared.so`); a special `SYMLINKS.txt` entry lists `target←linkpath` pairs (the separator is the arrow character U+2190) which the installer recreates as symlinks.
- Installation is idempotent (skipped once `$PREFIX/bin/bash` exists), staged then atomically renamed.

### 2a. Alternative: download instead of bundling
If no asset is bundled, the installer can **download** the bootstrap on first connect:
- Set `BOOTSTRAP_URL_TEMPLATE` in `BootstrapInstaller.kt` to a URL with `<ARCH>` as a placeholder (e.g. a Termux bootstrap release `…/bootstrap-<ARCH>.zip`). Blank = download disabled.
- The download follows redirects (GitHub → object store), caches to the plugin's cache dir, then extracts.
- The plugin manifest declares `android.permission.INTERNET` for this path; remove it if you bundle the zip instead.
- **Security:** populate `BOOTSTRAP_SHA256` (arch → lowercase hex) to verify the downloaded zip — strongly recommended since this fetches executable code (the apt-equivalent; mind Play's device-abuse policy for the OSS plugin).

## 3. How execution works (linker-exec)

- On Android 10+ (API 29+, targetSdk ≥ 29), an app's data-dir files are **noexec** under SELinux (`execute_no_trans` on `app_data_file`), so `execve("$PREFIX/bin/bash")` is denied.
- Workaround ("linker-exec"): run `execve("/system/bin/linker64", ["/system/bin/linker64", "$PREFIX/bin/bash", …args])`. The allowed **system linker** loads and runs the data-dir binary (which is only mmap'd, not exec'd directly). 32-bit processes use `/system/bin/linker`.
- The native launcher ([ptylauncher.c](app/src/main/cpp/ptylauncher.c)) takes a `linker` argument to do exactly this for the first process (`bash`).

## 4. termux-exec (child processes)

- When `bash` runs `ls`/`apt`/etc., those are also data-dir binaries → they'd hit the same noexec wall. The **termux-exec** `LD_PRELOAD` shim (`$PREFIX/lib/libtermux-exec.so`, shipped inside the bootstrap) intercepts every `execve`/`execvp`/`posix_spawn` and applies the linker-exec rewrite + fixes script shebangs and PATH.
- The service sets `LD_PRELOAD=$PREFIX/lib/libtermux-exec.so` (if present) plus the runtime config env the modern shim reads: `TERMUX__PREFIX=$PREFIX` and `TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE=enable`. These let the prebuilt shim work even with a relocated prefix.
- Reference implementation: the `termux/termux-exec` project (the `ExecIntercept.c` LD_PRELOAD interceptor). We deliberately do NOT re-implement it — we use the prebuilt `.so` from the bootstrap.

## 5. Known limitations / caveats

- **Prefix relocation:** binaries compiled for `/data/data/com.termux/files/usr` may fail to find files at the plugin's prefix. Mitigations: build a custom bootstrap with `TERMUX_PREFIX=/data/data/de.lobianco.saftssh.linux/files/usr` (termux-packages supports a custom prefix), or rely on termux-exec's runtime prefix config (works for exec but not for paths hardcoded in binaries/scripts).
- **minSdk 26 / API 26–28:** linker-exec needs API 29+. On API 26–28 the plugin falls back to the plain system shell (the bootstrap won't be exec'able). targetSdk also matters: very old targetSdk (≤ 28) runs in an SELinux context that is exempt from noexec, but this plugin targets a modern SDK.
- **Cannot be built/tested in the assistant environment** — verify on a device built from Android Studio.
- The bootstrap is **GPL** (bash/coreutils/apt): keep it and this whole `linux-plugin/` project in its OWN repo, never in the paid main app.

## 6. Activation checklist

1. Drop the arch-matched `bootstrap-<arch>.zip` into `app/src/main/assets/`
2. Build + install the plugin APK
3. In the main LobiShell app create a "Linux (Plugin)" connection
4. First connect extracts the bootstrap and launches `bash --login` via linker-exec; subsequent connects reuse it
5. If no bootstrap asset is present, the plugin runs `/system/bin/sh` instead

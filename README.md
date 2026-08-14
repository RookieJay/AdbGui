# ADB GUI

A Windows-first Compose Multiplatform desktop GUI for the Android Debug Bridge (`adb`), aimed at developers and testers who want the common adb workflows — connect devices, manage apps, inspect device info, grab screenshots — without a terminal. Built as a Kotlin Multiplatform project (`:core` pure-Kotlin logic + `:desktop` Compose Desktop UI) targeting the JVM.

## v1 features

- **Device connection** — USB (auto-detected once authorized) and wireless (`adb connect <ip:port>`) devices, with persistent connection history and one-click reconnect.
- **Multi-device** — sidebar lists all connected devices; switch context with a click.
- **App management** — list third-party packages, install APK, uninstall, clear data.
- **Device info panel** — model, version, SDK, ABI, and other key properties.
- **Screenshot** — capture the device screen and save as PNG.
- **Structured logging** — file logger at `%APPDATA%/AdbGui/logs/adbgui.log` with INFO/WARN/ERROR/DEBUG levels; toggle DEBUG in Settings to see raw adb command I/O; open/export logs from the Settings screen.

## Requirements

- **JDK 21** (Android Studio's bundled JBR is sufficient for running and building; packaging needs a full JDK — see [packaging/README.md](packaging/README.md)).
- **adb on PATH** — v1 ships without a bundled adb binary, so it resolves adb from your `PATH` (or a user-configured override in Settings). Install platform-tools and ensure `adb` is reachable from a terminal.
- Gradle wrapper is included — no system Gradle required.

## Build & run

```bash
./gradlew :desktop:run
```

This compiles `:core` and `:desktop` and opens the Compose window. With no device connected the sidebar will be empty — that is expected.

### Mirror configuration (China network)

The project is configured for the restricted China network out of the box:

- `settings.gradle.kts` — Aliyun mirrors for plugin + dependency resolution (ahead of mavenCentral/gradlePluginPortal).
- `gradle/wrapper/gradle-wrapper.properties` — `distributionUrl` points at the Tencent cloud mirror of Gradle 8.11.
- A user-level `~/.gradle/init.gradle` may also redirect downloads through Aliyun/Tencent.

If you are **not** on a restricted network and prefer canonical sources, revert these to `gradlePluginPortal()` / `mavenCentral()` / `https://services.gradle.org/distributions/gradle-8.11-bin.zip` as appropriate. The mirrors are purely a network convenience and are not required by the code.

## Tests

```bash
./gradlew :core:test   # pure-Kotlin unit tests (parsers, domain logic)
./gradlew :desktop:test   # ViewModel tests + platform smoke
```

## Where settings & logs live

All runtime state is under `%APPDATA%/AdbGui/` (i.e. `C:\Users\<you>\AppData\Roaming\AdbGui\`):

| File | Purpose |
|---|---|
| `settings.json` | adb path override, log level, other prefs |
| `devices.json` | persistent device connection history (for one-click reconnect) |
| `logs/adbgui.log` | structured file log |

## Setting the adb path

Open **Settings** in the app. The adb binary is resolved with the priority: **user-configured override > bundled adb > `PATH`**. v1 ships without a bundled adb, so by default it uses whatever `adb` resolves to on your `PATH`. Enter an explicit path in Settings if you need to override it (e.g. a specific platform-tools version). The resolved source is shown in the UI.

## Packaging (MSI / portable)

See [packaging/README.md](packaging/README.md). Packaging requires a **full JDK 21** with `jpackage`/jmods (the Android Studio JBR lacks them) and, for the MSI only, WiX Toolset 3.11 on `PATH`. The portable AppImage needs only the full JDK.

## Known limitations (v1 scope)

The following are intentionally deferred to later phases (see `docs/superpowers/specs/2026-08-14-adb-gui-design.md` §11):

- No interactive shell terminal page (`adb shell` subprocess + ANSI handling).
- No real-time logcat stream (filtering/search/buffering).
- No file push/pull (drag-drop, progress, resume).
- No screen projection / scrcpy integration.
- No debug/system operations: port-forwarding, `adb pair`, reboot/recovery/sideload, root/remount, `monkey` stress tests.

## Manual device smoke test

Run this checklist with a real Android device connected and authorized. For wireless, use `adb connect <ip:port>` (the device must be on the same network with wireless debugging enabled).

1. `./gradlew :desktop:run` — app window opens.
2. **Sidebar** — the connected device appears in the live device list.
3. **App Manager** — select the device, open App Manager: third-party packages are listed; install an APK; uninstall one; clear data on one. Each action should succeed or fail inline (raw adb text shown, no modal dialog).
4. **Device Info** — shows model / version / SDK / ABI / other props.
5. **Screenshot** — captures and displays the screen; **Save** writes a PNG file.
6. **Disconnect (wireless)** — right-click a wireless device → Disconnect; it leaves the live list but stays in the history.
7. **Restart app** — close and re-run; the device history persists; reconnect the wireless device with one click from history.
8. **Logs** — `%APPDATA%/AdbGui/logs/adbgui.log` contains INFO entries for the commands run above.
9. **DEBUG toggle** — Settings → set log level to DEBUG and rerun a command; the log now shows raw adb I/O.
10. **Failure handling** — any command failure (e.g. uninstall a non-existent package) shows inline with adb raw text; no modal/stack-trace popup.

Expected: all pass.

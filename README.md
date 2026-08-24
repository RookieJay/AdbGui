# ADB GUI

**English** | [中文](README.zh-CN.md)

A Windows-first Compose Multiplatform desktop GUI for the Android Debug Bridge (`adb`), aimed at developers and testers who want the common adb workflows — connect devices, manage apps, inspect device info, grab screenshots — without a terminal. Built as a Kotlin Multiplatform project (`:core` pure-Kotlin logic + `:desktop` Compose Desktop UI) targeting the JVM.

**Status: v1 complete.** Subsequent features (shell, logcat, file push/pull, scrcpy, debug/system ops) are deferred — see "Next steps" below.

## v1 features

- **Device connection** — USB (auto-detected once authorized) and wireless (`adb connect <ip:port>`), multi-device sidebar, persistent connection history, and **one-click reconnect** of disconnected wireless devices.
- **Device discovery** — polls `adb devices` every ~2s (live, plug/unplug reflected within 2s). Auto-selects the first **online** device so feature pages are usable immediately; never steals an actively-selected online device; clears stale data when the selected device goes offline.
- **Selection UI** — selected device row highlighted; right-click a device row opens a context menu (**Reconnect** / Rename / Disconnect / Forget). **Forget** asks for confirmation.
- **App management** — list third-party packages, install APK, uninstall, clear data. Inline errors with raw adb text (no modal).
- **Device info panel** — model / version / SDK / ABI / serial / resolution (from `getprop`), auto-refreshes on device switch. **Export** a full device-detail report (getprop + wm size/density + meminfo + cpuinfo + battery + disk) to a timestamped `.txt`, with Open / Open-folder links after save.
- **Screenshot** — capture the screen; **Save** writes a timestamped PNG (`screenshot_yyyyMMdd-HHmmss.png`); after save, **Open image** / **Open folder** (selects the file in Explorer) links.
- **Structured logging** — `%APPDATA%/AdbGui/logs/adbgui.log`, rolling 5×2MB, INFO/WARN/ERROR/DEBUG; toggle DEBUG in Settings to see raw adb command I/O; open/export logs from Settings.
- **i18n** — switchable UI language, **default 中文**; English available; runtime switch in Settings (persisted). Extensible to more languages.

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

If you are **not** on a restricted network and prefer canonical sources, revert these to `gradlePluginPortal()` / `mavenCentral()` / `https://services.gradle.org/distributions/gradle-8.11-bin.zip` as appropriate. The mirrors are purely a network convenience (Aliyun is public/global, just preferred) and are not required by the code.

## Tests

```bash
./gradlew :core:test     # pure-Kotlin unit tests (parsers, domain logic, stores)
./gradlew :desktop:test  # ViewModel tests + platform smoke
```

## Where settings & logs live

All runtime state is under `%APPDATA%/AdbGui/` (i.e. `C:\Users\<you>\AppData\Roaming\AdbGui\`):

| File | Purpose |
|---|---|
| `settings.json` | adb path override, log level, **locale**, other prefs |
| `devices.json` | persistent device connection history (for one-click reconnect + alias) |
| `logs/adbgui.log` | structured file log (rolling 5×2MB) |

## Setting the adb path

Open **Settings** in the app. The adb binary is resolved with the priority: **user-configured override > bundled adb > `PATH`**. v1 ships without a bundled adb, so by default it uses whatever `adb` resolves to on your `PATH`. Enter an explicit path in Settings if you need to override it (e.g. a specific platform-tools version).

## Packaging (MSI / portable)

See [packaging/README.md](packaging/README.md). Packaging requires a **full JDK 21** with `jpackage`/jmods (the Android Studio JBR lacks them) and, for the MSI only, WiX Toolset 3.11 on `PATH`. The portable AppImage needs only the full JDK.

## Architecture (for contributors)

Two-module KMP layout:

- **`:core`** (pure Kotlin/JVM, no UI deps) — all adb interaction (`CommandRunner`, `DeviceTracker` polling, `DeviceRepository`), parsers, stores (`SettingsStore`, `DeviceHistoryStore`), logging abstraction. Fully unit-testable (no real adb — `FakeAdbProcessRunner` injects scripted output).
- **`:desktop`** (Compose Multiplatform) — UI (screens + ViewModels), platform impls (`JvmAdbProcessRunner`, `WindowsConfigDirProvider`, `FileLogger`), i18n, composition root.

Red lines (see `CLAUDE.md`): `:core` must not depend on Compose/UI; UI/ViewModels only touch `DeviceRepository` (never `CommandRunner`/adb directly); platform differences behind interfaces. UI strings go through `Strings.t(...)` (i18n); `:core` keeps adb raw text untranslated.

## Known limitations (v1 scope)

The following are intentionally deferred to later phases (see `docs/superpowers/specs/2026-08-14-adb-gui-design.md` §11):

- No interactive shell terminal page (`adb shell` subprocess + ANSI handling).
- No real-time logcat stream (filtering/search/buffering).
- No file push/pull (drag-drop, progress, resume).
- No screen projection / scrcpy integration.
- No debug/system operations: port-forwarding, `adb pair`, reboot/recovery/sideload, root/remount, `monkey` stress tests.

Known tech debt (non-blocking for v1 use) is recorded in `CHANGELOG.md`.

## Next steps

v1 is feature-complete. For subsequent work next week:
- **Deferred features** — see spec §11 + CHANGELOG "v1 范围外".
- **Tech debt worth tackling early** — `DeviceHistoryStore`/`SettingsStore` dispatcher injection (makes ViewModel tests deterministic, removes the poll-loop workarounds); remove dead code (`NoDeviceSelectedException`, `DeviceTracker.clock`, `AdbProcessRunner.startStream`, the empty `scope.launch{}` in `DeviceRepository`).
- **Architecture reference** — spec (`docs/superpowers/specs/2026-08-14-adb-gui-design.md`) + CHANGELOG. Note: the spec §5.3 describes a `track-devices` stream; the shipped implementation polls `adb devices` instead (the stream emits adb wire-protocol frames, not clean text). See CHANGELOG "真机测试发现并修复的问题".

## Manual device smoke test

Run this checklist with a real Android device connected and authorized. For wireless, use `adb connect <ip:port>` (the device must be on the same network with wireless debugging enabled).

1. `./gradlew :desktop:run` — app window opens.
2. **Sidebar** — the connected device appears and is auto-selected; its row is highlighted.
3. **Right-click** a device row → context menu (Reconnect / Rename / Disconnect / Forget). Forget asks for confirmation.
4. **App Manager** — open it; third-party packages are listed (auto-refreshed on device switch); install an APK; uninstall one; clear data. Failures show inline (raw adb text, no modal).
5. **Device Info** — shows model / version / SDK / ABI / serial / resolution (auto-refreshed on switch). **Export** → Save a timestamped `.txt` → Open / Open folder links.
6. **Screenshot** — Capture; **Save** writes a timestamped PNG; **Open image** / **Open folder** links appear.
7. **Reconnect** — disconnect a wireless device (right-click → Disconnect); it leaves the live list but stays in history; right-click it → **Reconnect** (or the + dialog) to reconnect.
8. **Stale-data check** — disconnect the selected device; the feature pages clear (no stale data from the previous device).
9. **Language** — Settings → switch 中文 / English; UI re-renders immediately; persists across restart (default 中文).
10. **Logs** — `%APPDATA%/AdbGui/logs/adbgui.log` has INFO entries; Settings → DEBUG shows raw adb I/O. Command failures show inline, no stack-trace popup.

Expected: all pass.

# Building ADB GUI distributions

> 自己发布新版本（含 MSI + 便携版 + 自动更新清单上传）见同目录 [`RELEASE.md`](./RELEASE.md)。

## Prerequisites
1. **Full JDK 21 with jmods + `jpackage`** (Temurin/Zulu/Corretto — not a JRE). The Android Studio JBR is a JRE-stripped JDK: it runs and compiles the app fine but **does NOT include `jpackage.exe` or jmods**, so packaging will fail with `Failed to check JDK distribution: 'jpackage.exe' is missing`. Install a full JDK 21 (e.g. [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21)) and set `JAVA_HOME` to it before running the commands below.
2. Run `./gradlew` via the wrapper (no system Gradle needed).
3. **WiX Toolset 3.11 on PATH** — required *only* for the MSI. Install from https://wixtoolset.org. Without WiX, build the portable AppImage instead. (The Compose plugin auto-downloads WiX from GitHub, which may be blocked on restricted networks — install WiX manually in that case.)

## Portable (no-install) — recommended, no extra tooling (still needs the full JDK above)
./gradlew :desktop:packageAppImage
# Output: desktop/build/compose/binaries/main/app/AdbGui/
#   Contains AdbGui.exe + bundled JRE (runtime/) + bundled platform-tools adb
#   (app/resources/adb/win/). Run AdbGui.exe directly — no adb on PATH needed.
# Zip this directory to distribute.

## MSI installer (per-user, Start menu shortcut) — requires WiX + full JDK
./gradlew :desktop:packageMsi
# Output: desktop/build/compose/binaries/main/msi/AdbGui-1.0.0.msi

> **不要"以管理员身份"运行 MSI**（右键 run as admin）。per-user 包提升安装会把 ARP 卸载项
> 写进 HKLM 并留下与后续非提升升级不一致的上下文——之后应用内升级若触发同版本重装
> （jpackage 的 ProductCode 由 应用名+版本 派生，同版本必同 ProductCode），Windows Installer
> 每个被覆盖文件的备份都会弹一个 Error 1926 模态框（2026-09-22 实测 25 连弹）。正常双击安装、
> 应用内升级都无需管理员；升级遇到报错的 `<盘符>\Config.Msi` 残留时才需要管理员删除它。
> 详见 CHANGELOG「升级体验修复」节。

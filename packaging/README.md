# Building ADB GUI distributions

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

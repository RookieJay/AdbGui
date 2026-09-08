#!/usr/bin/env bash
# Release helper: bump version, build MSI + AppImage, compute sha256, generate latest.json,
# and zip the portable (AppImage) dir. After this script, you only need to create the GitHub
# release (tag v<VERSION>) and upload the generated assets.
#
# Usage: ./packaging/release.sh 1.2.0
#
# Prerequisites:
#   - Full JDK 21 with jpackage on JAVA_HOME (Temurin at D:\software\jdk-21.0.12.1+1 by default;
#     override via JAVA_HOME env). The Android Studio JBR lacks jpackage.
#   - WiX on PATH (only for MSI; the Compose plugin auto-downloads it, may fail on restricted
#     networks — install manually from https://wixtoolset.org if so).
#   - git remote origin points to the GitHub repo (used to derive the release asset URLs).
set -euo pipefail

VERSION="${1:?usage: release.sh <version> e.g. 1.2.0}"
# Validate semver-ish (X.Y.Z, optional -prerelease).
[[ "$VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.\-]+)?$ ]] || {
  echo "invalid version: $VERSION (expected X.Y.Z)" >&2; exit 2; }

# Use Temurin 21 by default (jpackage present); fall back to JAVA_HOME if already set.
export JAVA_HOME="${JAVA_HOME:-D:/software/jdk-21.0.12.1+1}"
[[ -x "$JAVA_HOME/bin/jpackage.exe" ]] || { echo "jpackage not found at $JAVA_HOME" >&2; exit 2; }

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

GRADLE="./gradlew"
BUILD_DIR="desktop/build/compose/binaries/main"
MSI="$BUILD_DIR/msi/AdbGui-$VERSION.msi"
APPIMAGE_DIR="$BUILD_DIR/app/AdbGui"
LATEST_JSON="$BUILD_DIR/msi/latest.json"
PORTABLE_ZIP="$BUILD_DIR/AdbGui-$VERSION-portable.zip"

# Derive OWNER/REPO from origin (https://github.com/OWNER/REPO.git).
REMOTE=$(git remote get-url origin)
REPO=$(printf '%s' "$REMOTE" | sed 's|^.*github\.com/||; s|\.git$||')
[[ "$REPO" == */* ]] || { echo "could not parse owner/repo from origin: $REMOTE" >&2; exit 2; }
TAG="v$VERSION"
ASSET_BASE="https://github.com/$REPO/releases/download/$TAG"

echo "==> Bumping version to $VERSION in build.gradle.kts + AppMeta.kt"
# Use perl for portable in-place edit (works on Windows git bash; sed -i variants differ).
perl -i -pe "s/(packageVersion = \")[^\"]*(\")/\\1$VERSION\\2/" desktop/build.gradle.kts
perl -i -pe "s/(APP_VERSION = \")[^\"]*(\")/\\1$VERSION\\2/" desktop/src/main/kotlin/com/adbgui/desktop/platform/AppMeta.kt
grep -n "packageVersion\|APP_VERSION" desktop/build.gradle.kts desktop/src/main/kotlin/com/adbgui/desktop/platform/AppMeta.kt

echo "==> Building MSI + AppImage (JAVA_HOME=$JAVA_HOME)"
"$GRADLE" :desktop:packageMsi :desktop:packageAppImage
# copyBundledAdb is finalizedBy packageAppImage — adb lands in the AppImage's app/resources.

[[ -f "$MSI" ]] || { echo "MSI not found at $MSI" >&2; exit 2; }
[[ -d "$APPIMAGE_DIR" ]] || { echo "AppImage dir not found at $APPIMAGE_DIR" >&2; exit 2; }

echo "==> Computing sha256 + size of MSI"
SHA=$(sha256sum "$MSI" | awk '{print $1}')
SIZE=$(wc -c < "$MSI")
echo "  sha256=$SHA"
echo "  size=$SIZE bytes"

echo "==> Generating latest.json (url=MSI asset, portableUrl=portable zip asset)"
cat > "$LATEST_JSON" <<EOF
{
  "version": "$VERSION",
  "url": "$ASSET_BASE/AdbGui-$VERSION.msi",
  "portableUrl": "$ASSET_BASE/AdbGui-$VERSION-portable.zip",
  "sha256": "$SHA",
  "size": $SIZE,
  "notes": "AdbGui $VERSION",
  "minAppVersion": "1.0.0"
}
EOF
cat "$LATEST_JSON"

echo "==> Zipping portable (AppImage) -> $PORTABLE_ZIP"
# Compress-Archive from the parent dir so the zip's top-level entry is AdbGui/.
pushd "$(dirname "$APPIMAGE_DIR")" >/dev/null
powershell.exe -NoProfile -Command "Compress-Archive -Path AdbGui -DestinationPath '$(cygpath -w "$ROOT/$PORTABLE_ZIP" 2>/dev/null || echo "$ROOT/$PORTABLE_ZIP")' -Force"
popd >/dev/null
[[ -f "$PORTABLE_ZIP" ]] || echo "  WARN: portable zip not created (powershell unavailable? zip the AppImage dir manually)" >&2

echo
echo "==> Done. Artifacts:"
echo "    MSI:          $MSI"
echo "    Portable zip: $PORTABLE_ZIP"
echo "    latest.json:  $LATEST_JSON"
echo
echo "Next steps:"
echo "  1. Commit + tag + push:"
echo "       git add desktop/build.gradle.kts desktop/src/main/kotlin/com/adbgui/desktop/platform/AppMeta.kt"
echo "       git commit -m 'release: bump version to $VERSION'"
echo "       git tag $TAG"
echo "       git push origin master $TAG"
echo "  2. Create the GitHub release v$VERSION (https://github.com/$REPO/releases/new?tag=$TAG)"
echo "     and upload these 3 assets:"
echo "       $MSI  (as AdbGui-$VERSION.msi)"
echo "       $PORTABLE_ZIP  (as AdbGui-$VERSION-portable.zip)"
echo "       $LATEST_JSON  (as latest.json)"
echo "  3. Publish the release. Older apps will auto-update on next check."

# Release helper (Windows native PowerShell): build MSI + AppImage, compute
# sha256, generate latest.json (with portableUrl), and zip the portable dir.
# The version comes from gradle.properties (single source of truth) — edit
# `version=` there, then run this script.
# After running, create the GitHub release (tag v<VERSION>) and upload the generated assets.
#
# Usage:   packaging\release.bat                 (version read from gradle.properties)
#          packaging\release.bat 1.2.0           (optional: must match gradle.properties)
#          powershell -NoProfile -ExecutionPolicy Bypass -File packaging\release.ps1
#          pwsh packaging\release.ps1            (if PowerShell 7 is installed)
# Compatible with both Windows PowerShell 5.1 (powershell.exe) and PowerShell 7 (pwsh).
#
# Prerequisites:
#   - Full JDK 21 with jpackage on JAVA_HOME (Temurin at D:\software\jdk-21.0.12.1+1 by default;
#     override via $env:JAVA_HOME). The Android Studio JBR lacks jpackage.
#   - WiX on PATH for MSI (the Compose plugin auto-downloads it; install manually if blocked).
#   - git remote origin points to the GitHub repo (used to derive release asset URLs).
param(
    [Parameter(Position = 0)]
    [string]$Version
)

$ErrorActionPreference = 'Stop'

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'D:\software\jdk-21.0.12.1+1' }
if (-not (Test-Path "$env:JAVA_HOME\bin\jpackage.exe")) {
    $temurin = 'D:\software\jdk-21.0.12.1+1'
    if (Test-Path "$temurin\bin\jpackage.exe") {
        $env:JAVA_HOME = $temurin
    } else {
        Write-Error "jpackage not found at JAVA_HOME=$env:JAVA_HOME (and Temurin default $temurin missing). Set JAVA_HOME to a full JDK 21 with jpackage."; exit 2
    }
}

$root = (git rev-parse --show-toplevel)
Set-Location $root

# Version single source of truth: root gradle.properties. Read as UTF-8 (no BOM).
$propsText = [System.IO.File]::ReadAllText("$root\gradle.properties", [System.Text.Encoding]::UTF8)
if ($propsText -notmatch '(?m)^version\s*=\s*(\S+)\s*$') {
    Write-Error "version= not found in gradle.properties (single source of truth)"; exit 2
}
$gradleVersion = $Matches[1]
if ($Version -and $Version -ne $gradleVersion) {
    Write-Error "requested $Version but gradle.properties says $gradleVersion; edit gradle.properties instead (single source of truth)"; exit 2
}
$Version = $gradleVersion
if ($Version -notmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.\-]+)?$') {
    Write-Error "invalid version: $Version (expected X.Y.Z)"; exit 2
}
Write-Host "==> Releasing version $Version (from gradle.properties)"

$buildDir = 'desktop\build\compose\binaries\main'
$msi = "$buildDir\msi\AdbGui-$Version.msi"
$appImageDir = "$buildDir\app\AdbGui"
$latestJson = "$buildDir\msi\latest.json"
$portableZip = "$buildDir\AdbGui-$Version-portable.zip"

# Derive OWNER/REPO from origin (https://github.com/OWNER/REPO.git).
$remote = (git remote get-url origin)
if ($remote -match 'github\.com[:/]([^/]+/[^/]+?)(\.git)?$') {
    $repo = $Matches[1]
} else {
    Write-Error "could not parse owner/repo from origin: $remote"; exit 2
}
$tag = "v$Version"
$assetBase = "https://github.com/$repo/releases/download/$tag"

Write-Host "==> Building MSI + AppImage (JAVA_HOME=$env:JAVA_HOME)"
& .\gradlew.bat :desktop:packageMsi :desktop:packageAppImage
if ($LASTEXITCODE -ne 0) { Write-Error "gradle build failed"; exit $LASTEXITCODE }

if (-not (Test-Path $msi)) { Write-Error "MSI not found at $msi"; exit 2 }
if (-not (Test-Path $appImageDir)) { Write-Error "AppImage dir not found at $appImageDir"; exit 2 }

Write-Host "==> Computing sha256 + size of MSI"
$sha = (Get-FileHash -Algorithm SHA256 $msi).Hash.ToLower()
$size = (Get-Item $msi).Length
Write-Host "  sha256=$sha"
Write-Host "  size=$size bytes"

Write-Host "==> Generating latest.json (url=MSI asset, portableUrl=portable zip asset)"
$latest = @"
{
  "version": "$Version",
  "url": "$assetBase/AdbGui-$Version.msi",
  "portableUrl": "$assetBase/AdbGui-$Version-portable.zip",
  "sha256": "$sha",
  "size": $size,
  "notes": "AdbGui $Version",
  "minAppVersion": "1.0.0"
}
"@
$latest | Set-Content $latestJson
Get-Content $latestJson

Write-Host "==> Zipping portable (AppImage) -> $portableZip"
# Compress-Archive from the parent dir so the zip's top-level entry is AdbGui/.
$parent = Split-Path -Parent $appImageDir
$leaf = Split-Path -Leaf $appImageDir
Push-Location $parent
Compress-Archive -Path $leaf -DestinationPath "$root\$portableZip" -Force
Pop-Location
if (-not (Test-Path $portableZip)) {
    Write-Warning "portable zip not created (zip the AppImage dir manually: $appImageDir)"
}

Write-Host ""
Write-Host "==> Done. Artifacts:"
Write-Host "    MSI:          $msi"
Write-Host "    Portable zip: $portableZip"
Write-Host "    latest.json:  $latestJson"
Write-Host ""
Write-Host "Next steps:"
Write-Host "  1. Commit + tag + push:"
Write-Host "       git add gradle.properties"
Write-Host "       git commit -m `"release: bump version to $Version`""
Write-Host "       git tag $tag"
Write-Host "       git push origin master $tag"
Write-Host "  2. Create the GitHub release $tag (https://github.com/$repo/releases/new?tag=$tag)"
Write-Host "     and upload these 3 assets:"
Write-Host "       $msi  (as AdbGui-$Version.msi)"
Write-Host "       $portableZip  (as AdbGui-$Version-portable.zip)"
Write-Host "       $latestJson  (as latest.json)"
Write-Host "  3. Publish the release. Older apps will auto-update on next check."

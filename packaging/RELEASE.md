# ADB GUI 发布指南

自己发布新版本时照着这份走。两条路径：**A. 只发便携版**（最快，无 WiX 依赖）、**B. 完整发布**（MSI + 便携版 + latest.json，老版本自动更新需要）。

> 命令在 Windows Git Bash 里跑；代码/commit 用英文，正文随意。

---

## 0. 前置条件（一次性）

| 依赖 | 用途 | 说明 |
|---|---|---|
| **完整 JDK 21（含 jpackage + jmods）** | 打包 | Temurin 21：`D:\software\jdk-21.0.12.1+1`。Android Studio 的 JBR **没有 jpackage**，不能用。 |
| **Gradle wrapper** | 构建 | 仓库自带 `./gradlew`，不要用系统 gradle。 |
| **WiX Toolset 3.11** | 仅 MSI 需要 | https://wixtoolset.org。Compose 插件会自动从 GitHub 下 WiX，国内网络可能被墙 → 手动装。**只发便携版不需要。** |
| git remote `origin` → `github.com/RookieJay/AdbGui` | 发布脚本推导 asset URL | `git remote get-url origin` 确认。 |

---

## 1. 版本号在哪改

两个文件必须**同步**改（脚本会自动改，手动发也要保证一致）：

- `desktop/build.gradle.kts` → `packageVersion = "1.0.0"`
- `desktop/src/main/kotlin/com/adbgui/desktop/platform/AppMeta.kt` → `APP_VERSION = "1.0.0"`

`AppMeta.APP_VERSION` 是运行时版本真相源，老版本用它和 `latest.json` 的 `version` 比对决定是否提示更新。**改了版本就必须打全量包并发布 latest.json**，否则用户看到的"已是最新"会和实际对不上。

版本格式：`X.Y.Z`（可选 `-prerelease`），脚本用正则校验。

---

## 路径 A：只发便携版（快速验证 / 小版本）

适合：给人试新改动、临时验证、不想动 GitHub Release。

```bash
# 1. 用 Temurin 的 jpackage 打 AppImage
export JAVA_HOME="D:/software/jdk-21.0.12.1+1"
./gradlew :desktop:packageAppImage
# 产物：desktop/build/compose/binaries/main/app/AdbGui/
#   含 AdbGui.exe + runtime/ + app/resources/adb/win/（内置 adb）

# 2. 直接 zip（也可不 zip，让人跑目录里的 exe）
pushd desktop/build/compose/binaries/main/app >/dev/null
powershell.exe -NoProfile -Command "Compress-Archive -Path AdbGui -DestinationPath 'D:\StudioProjects\ADBGUI\desktop\build\compose\binaries\main\AdbGui-<VERSION>-portable.zip' -Force"
popd >/dev/null
```

- **不改版本号、不打 MSI、不生成 latest.json、不碰 GitHub Release**。
- 不影响自动更新链路（老版本不会看到这个包）。
- 缺点：没有 sha256/版本元数据，仅适合一次性分发。

> 9-19 给用户验证冷启动的就是这条路径打的 `AdbGui-coldstart-portable.zip`。

---

## 路径 B：完整发布（推荐，含自动更新）

一条命令搞定：改版本号 → 打 MSI + AppImage → 算 sha256 → 生成 latest.json → zip 便携版。

```bash
# Git Bash
./packaging/release.sh 1.2.0
# 或 cmd / Windows 终端 / Android Studio 内置终端（无需装 pwsh）
packaging\release.bat 1.2.0
```

脚本跑完会输出三个产物（路径在 `desktop/build/compose/binaries/main/`）：

| 产物 | 路径 | 上传到 GitHub Release 时命名为 |
|---|---|---|
| MSI 安装包 | `msi/AdbGui-<VERSION>.msi` | `AdbGui-<VERSION>.msi` |
| 便携版 zip | `AdbGui-<VERSION>-portable.zip` | `AdbGui-<VERSION>-portable.zip` |
| 更新清单 | `msi/latest.json` | `latest.json` |

### latest.json 字段（脚本生成，别手改）

```json
{
  "version": "1.2.0",
  "url": "https://github.com/RookieJay/AdbGui/releases/download/v1.2.0/AdbGui-1.2.0.msi",
  "portableUrl": "https://github.com/RookieJay/AdbGui/releases/download/v1.2.0/AdbGui-1.2.0-portable.zip",
  "sha256": "...",
  "size": 12345678,
  "notes": "AdbGui 1.2.0",
  "minAppVersion": "1.0.0"
}
```

- `url`：MSI 下载地址（主更新路径）。
- `portableUrl`：可选，有的话 UI 多一个"下载便携版"按钮。
- `sha256`/`size`：MSI 的校验值（便携版不校验）。
- `minAppVersion`：低于此版本不提示更新（留作硬性升级门槛）。

### 发布步骤（脚本跑完后手动）

```bash
# 1. 提交版本号改动 + 打 tag + 推送
git add desktop/build.gradle.kts desktop/src/main/kotlin/com/adbgui/desktop/platform/AppMeta.kt
git commit -m "release: bump version to 1.2.0"
git tag v1.2.0
git push origin master v1.2.0

# 2. 到 GitHub 网页创建 Release
#    https://github.com/RookieJay/AdbGui/releases/new?tag=v1.2.0
#    上传上面三个产物（名字必须和 latest.json 里的 url/portableUrl 文件名一致）

# 3. 发布 Release。老版本下次检查更新会自动拉到。
```

---

## 2. 自动更新是怎么工作的（发布时必须懂）

老版本启动后会请求 `latest.json` 比对版本，源在 `core/.../update/UpdateSourceRegistry.kt`：

| sourceId | URL | 用途 |
|---|---|---|
| `github-official`（默认） | `https://github.com/RookieJay/AdbGui/releases/latest/download/latest.json` | GitHub 直连 |
| `github-mirror` | `https://gh-proxy.com/<上面那个URL>` | 国内反代，设置页可切 |

`releases/latest/download/latest.json` 是 GitHub 的**稳定 URL**——它永远指向**最新发布**的 Release 里的 `latest.json`。所以：

- **每次发新版，`latest.json` 都必须随 Release 一起上传**，否则老版本拉不到新清单。
- **Release 必须点"Publish"真正发布**，Draft 状态 `releases/latest/download/` 指不到。
- 三个 asset 的**文件名必须和 `latest.json` 里 `url`/`portableUrl` 的文件名完全一致**（脚本已对齐，别手动改名）。
- 便携版下载地址走 `portableUrl`；如果新版只发 MSI，可省 `portableUrl`（脚本不删字段就照填，留空更安全——需要时手编 latest.json 删掉该行）。

---

## 3. 发布后自检

1. **清单可达**：浏览器开 `https://github.com/RookieJay/AdbGui/releases/latest/download/latest.json`，应返回刚发的 JSON，`version` 是新版本号。
2. **asset 可达**：点 JSON 里的 `url` 和 `portableUrl`，应能下载 MSI / zip。
3. **老版本能感知**：留一台装着旧版的机器，启动 → 设置里看更新源 → 应弹出"有新版本 1.2.0"。
4. **sha256 对得上**：`certutil -hashfile AdbGui-1.2.0.msi SHA256`，和 latest.json 的 `sha256` 一致。

---

## 4. 常见坑

| 现象 | 原因 | 解决 |
|---|---|---|
| `Failed to check JDK distribution: 'jpackage.exe' is missing` | JAVA_HOME 指到 JBR 了 | `export JAVA_HOME="D:/software/jdk-21.0.12.1+1"` |
| 打 MSI 卡在下载 WiX | 国内网络挡 GitHub | 手动装 WiX 3.11 并加 PATH；或只发便携版（路径 A） |
| 老版本提示"已是最新"但实际有新版 | latest.json 没上传 / Release 还是 Draft / 版本号没改 | 见上文三步 |
| `update: available 1.2.0` 但点更新失败 | asset 文件名和 latest.json 里的不一致 | 重命名 asset 或改 latest.json 重新上传 |
| 打出来的 exe 是旧代码 | 仓库有未提交改动 / 上次打包缓存 | `./gradlew clean :desktop:packageAppImage` 重打 |
| 版本号两处不一致 | 只改了一处 | 脚本会同时改；手动发记得两处都改 |

---

## 5. 速查：发版要做的事

```
# 完整发版（路径 B）
./packaging/release.sh <VERSION>          # 打包 + 生成 latest.json
git add ... && git commit -m "release: bump version to <VERSION>"
git tag v<VERSION> && git push origin master v<VERSION>
# → GitHub 建 Release、传 3 个 asset、Publish
# → 浏览器开 latest.json 稳定 URL 自检
```

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

**只改一处**：根 `gradle.properties` 的 `version=` 行。它是唯一真相源，其余全部自动派生：

- `desktop/build.gradle.kts` 的 `packageVersion`（jpackage/MSI 用，预发布后缀会被剥掉）由 Gradle `project.version` 派生。
- 运行时 `AppMeta.APP_VERSION`（更新检查比对用）来自 Gradle `processResources` 生成的 `/version.properties`，打进 jar，AppMeta 运行时读取。

`AppMeta.APP_VERSION` 是运行时版本，老版本用它和 `latest.json` 的 `version` 比对决定是否提示更新。**改了版本就必须打全量包并发布 latest.json**，否则用户看到的"已是最新"会和实际对不上。

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

一条命令搞定：打 MSI + AppImage → 算 sha256 → 生成 latest.json → zip 便携版。版本号从 `gradle.properties` 读（先改好 `version=`）。

```bash
# Git Bash
./packaging/release.sh
# 或 cmd / Windows 终端 / Android Studio 内置终端（无需装 pwsh）
packaging\release.bat
# 传参仅作一致性校验（不匹配会报错）：
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
git add gradle.properties
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

## 3.5 本地模拟在线升级（不发 GitHub 也能全链路测试）

发布前想验证"老版本 → 检查更新 → 下载 → 校验 → 安装"整条链路，用自定义更新源指向本机 HTTP 服务即可：

```bash
# 1. 准备测试目录（项目根的 test/ 就是干这个的）：
#    test/latest.json          ← 从 release 脚本产物复制，url 改成 http://127.0.0.1:8000/<msi文件名>
#    test/AdbGui-<VERSION>.msi ← 要升级到的目标安装包
# 2. 起本地服务器（在 test/ 目录里跑）：
cd test
python -m http.server 8000

# 3. 老版本应用里：设置 → 更新源 → 自定义，填：
#    http://127.0.0.1:8000/latest.json
# 4. 检查更新 → 下载 → 安装，走真实流程。
```

**两个必须核对的点**（2026-09-24 实测踩过）：

- `latest.json` 的 `sha256`/`size` 必须是对 `url` 指向的**那个 msi 文件**重算的——从上个版本复制 manifest 只改 version/url 不改哈希，会下载成功但校验报"文件可能损坏"。
- 打的 msi 必须是**改完 version= 之后重打的**（`generateVersionProperties` 有 input 声明，正常会自动重生成 `version.properties`；如果应用里显示的版本和包名对不上，`msiexec /a <msi> /qn TARGETDIR=<目录>` 解包后查 jar 里的 `version.properties`）。

**安装上下文要求**：应用内升级是非提权（per-user）跑 msiexec。如果本机曾经用管理员装过（ARP 条目出现在 HKLM 而非 HKCU、且存在管理员属主的 `<盘符>:\Config.Msi`），升级时每个文件会弹 Error 1926。一次性根治：管理员卸载 + 删 `<盘符>:\Config.Msi` + 普通身份重装。**平时安装/升级都别用管理员。**

---

## 4. 常见坑

| 现象 | 原因 | 解决 |
|---|---|---|
| `Failed to check JDK distribution: 'jpackage.exe' is missing` | JAVA_HOME 指到 JBR 了 | `export JAVA_HOME="D:/software/jdk-21.0.12.1+1"` |
| 打 MSI 卡在下载 WiX | 国内网络挡 GitHub | 手动装 WiX 3.11 并加 PATH；或只发便携版（路径 A） |
| 老版本提示"已是最新"但实际有新版 | latest.json 没上传 / Release 还是 Draft / 版本号没改 | 见上文三步 |
| `update: available 1.2.0` 但点更新失败 | asset 文件名和 latest.json 里的不一致 | 重命名 asset 或改 latest.json 重新上传 |
| 打出来的 exe 是旧代码 | 仓库有未提交改动 / 上次打包缓存 | `./gradlew clean :desktop:packageAppImage` 重打 |
| 传给脚本的版本和实际发布不符 | 脚本从 `gradle.properties` 读版本，传参只是校验 | 只改 `gradle.properties` 的 `version=`，不匹配会直接报错 |
| 点"安装更新"后 msiexec 弹帮助框而非安装 UI | `INSTALLDIR` 含空格时被整段加引号，msiexec 判命令行非法（1639） | 已修（JNA ShellExecute 传原始参数串）；如再出现查 `%APPDATA%/AdbGui/logs/` 的 `msiexec /i parameters=` 行 |
| 升级安装过程中逐文件弹 "Could not set file security ... Error: 5"（1926） | 本机基座是管理员/机器级(HKLM)安装，非提权升级碰旧产品文件 | 管理员卸载现有版本 + 删 `<盘符>:\Config.Msi`，再普通身份重装；详见 §3.5 |

---

## 5. 速查：发版要做的事

```
# 完整发版（路径 B）
# 1. 改 gradle.properties 的 version=
# 2. ./packaging/release.sh                # 打包 + 生成 latest.json
git add gradle.properties && git commit -m "release: bump version to <VERSION>"
git tag v<VERSION> && git push origin master v<VERSION>
# → GitHub 建 Release、传 3 个 asset、Publish
# → 浏览器开 latest.json 稳定 URL 自检
```

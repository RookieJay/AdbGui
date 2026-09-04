# ADB GUI — App Console & System Ops 功能批次设计

- **日期**：2026-09-04
- **状态**：设计草案，待用户复核后转 writing-plans
- **范围**：五个增强 —— A(bugreport) + B(install-multiple + flags) + C(am start 构建器) + D(权限 grant/revoke) + E(应用详情 version/path/libs)；其中 E 与 D 共用 `dumpsys package` parser + fixture
- **依据**：对照《ADB 用法大全》(WanAndroid #2310) 命令速查表 + 本项目 CHANGELOG/roadmap；CLAUDE.md 架构红线
- **参照既有实现**：`CommandRunner`、`AppConsoleViewModel`/`AppConsoleScreen`、`SystemOpsViewModel`、`InstallResultParser`、`Extra`/`ExtraType`、截图保存/Open 链接模式

## 1. 背景与动机

WanAndroid #2310《ADB 用法大全》是中文安卓社区广泛引用的 ADB 命令速查表。对照本应用现状（CHANGELOG 各 v2 节 + roadmap），绝大多数命令已覆盖：连接管理、应用安装/卸载/清数据/停止/启动、截图、logcat、shell、文件 push/pull、scrcpy 投屏+录制、reboot/root/remount、adb pair、系统信息查询页、端口转发、CDP 调试、文本输入、按键模拟。

本批次补齐速查表里尚未覆盖、且对开发/测试人员真实高频的能力：

| # | 能力 | 速查表对应 | 价值 |
|---|---|---|---|
| A | `adb bugreport` | 诊断导出 | 崩溃/ANR/性能排查的"一站式"证据 zip |
| B | `adb install-multiple` + flags | 安装变体 | split APK/app bundle 安装；降级/test/授权安装 |
| C | `am start` 带 action+data+extras | Deep link 启动 | 测试 deep link / 带参启动指定 Activity |
| D | `pm grant` / `pm revoke` | 运行时权限 | 测权限流免手动去设置页 |
| E | 应用详情（version/path/libs） | `dumpsys package` 只读 | 列出 app 的 versionName/versionCode、安装路径、原生库 `.so` |

E 与 D 同源（都解析 `adb shell dumpsys package <pkg>`），共用一个 parser + 一份 fixture，见 §6。

### 现状修正（文档失同步）

roadmap 文档 `2026-08-21-gap-analysis-and-roadmap.md` 与 CLAUDE.md「v1 范围边界」段说"端口转发、monkey 压测尚未实现"，实际端口转发（`PortForwardingScreen` + `CommandRunner.forward` + `ForwardListParser`）、CDP 调试（`CdpController` + `CdpDebugScreen`）、G1 文本输入（`CommandRunner.inputText` + `RemoteScreen`）均已实现并接入导航。本 spec 不修这两份文档（属独立文档清理任务），但实现以代码现状为准。

## 2. 架构红线（不可破，逐条确认）

1. **`:core` 不依赖 UI** —— A/B/C/D+E 的新 `CommandRunner` 方法纯 adb 交互，无 Compose/awt import。✅
2. **UI 不直接碰 adb** —— 所有新方法经 `DeviceRepository` 透传，ViewModel 只回调 repo。✅
3. **`:core` 不起真 adb** —— 走 `AdbProcessRunner`；测试用 `FakeAdbProcessRunner` 注入录制输出。✅
4. **平台差异藏接口背后** —— bugreport 的保存目录对话框走 `:desktop/platform/FileDialogs`（既有接口）。✅
5. **解析与执行分离** —— D+E 的 `DumpsysPackageParser` 是纯函数 object；`CommandRunner` 调它。✅

## 3. A — bugreport（System Ops 页）

### 3.1 core

`CommandRunner.bugreport(serial: String, destDir: String): BugreportResult`

- host 命令（非 shell）：`adb -s <serial> bugreport <destDir>`。adb 在 destDir 内生成 `bugreport-<日期>.zip`，stdout 末尾报生成的文件路径。
- 长时操作（10–60s，老设备/TV 更久）：用 `runner.run(adb(), listOf("-s", serial, "bugreport", destDir))`，设大 timeout。`AdbProcessRunner.run` 的协程取消会终止 adb 子进程 → **可取消**（ViewModel 持 job 并 `cancel()`）。
- `BugreportResult(zipPath: String, stdout: String)` data class（`domain/`）。
- 路径解析：优先从 stdout 解析 adb 报告的路径；解析失败回退用 destDir 扫描最新 `bugreport-*.zip`；都没有则抛 `AdbCommandException("adb bugreport", exitCode, "no bugreport zip found in $destDir; stdout=...")`。
- 非零退出抛 `AdbCommandException`（含 stderr）。
- 日志：INFO 级记 `adb -s <serial> bugreport <destDir> -> exit=<code>, zip=<path>`（路径含用户目录，不算敏感；不记 zip 内容）。

TDD（`CommandRunnerTest`）：
- `bugreport_returns_zip_path_from_stdout` —— fake runner 录制成功 stdout（含 "Bug report is stored at .../bugreport-xxx.zip"），断言 `result.zipPath` 正确。
- `bugreport_nonzero_throws` —— exit 1 + stderr，断言抛 `AdbCommandException`。
- `bugreport_falls_back_to_dir_scan_when_stdout_has_no_path` —— stdout 无路径、destDir 有（fake 不产生文件 → 此回退路径在单测里只能覆盖"stdout 无路径"分支；真机验证目录扫描）。

### 3.2 desktop

`SystemOpsViewModel` 扩展（**独立于 reboot 的 busy**，长时不阻塞重启按钮）：
- 新状态：`_bugreportBusy: MutableStateFlow<Boolean>`、`_bugreportResult: MutableStateFlow<BugreportResult?>`、`_bugreportError: MutableStateFlow<String?>`、`bugreportJob: Job?`。
- `fun bugreport(destDir: String)` = `scope.launch { ... repo.bugreport(serial, destDir) ... }`；try/catch 镜像既有 op 的错误格式（`AdbCommandException` 折叠 stderr）。
- `fun cancelBugreport()` = `bugreportJob?.cancel()`。

`SystemOpsScreen` 加"导出 bugreport"区：
- 按钮"导出 bugreport"（无设备/未在线禁用）→ 调 `FileDialogs.pickDirectory`（若既有；否则补一个选目录方法，见 §7）→ `vm.bugreport(dir)`。
- busy 时显示 `CircularProgressIndicator` + 取消按钮。
- 完成显示 zip 路径 + Open（系统默认打开 zip）/ Open folder（资源管理器选中文件，复用截图的 `explorer.exe /select,<path>`）。
- 失败红色内联条（折叠 adb 原文）。

## 4. B — install-multiple + flags（App Console 安装）

### 4.1 core

`InstallFlags` data class（`domain/`）：
```kotlin
data class InstallFlags(
    val reinstall: Boolean,    // -r  keep data
    val allowTest: Boolean,   // -t  test APK
    val downgrade: Boolean,   // -d  allow downgrade
    val grantPerms: Boolean,   // -g  grant all runtime perms
)
```

`CommandRunner.install(serial: String, paths: List<String>, flags: InstallFlags): InstallResult`
- `paths` 非空守卫（空 → IllegalArgumentException，VM 层已守卫但 core 也挡）。
- `paths.size == 1` → `install [-r] [-t] [-d] [-g] <path>`
- `paths.size > 1` → `install-multiple [-r] [-t] [-d] [-g] <p1> <p2> ...`
- flag → argv：`reinstall→"-r"`, `allowTest→"-t"`, `downgrade→"-d"`, `grantPerms→"-g"`，只追加 true 的。
- 复用 `InstallResultParser.parse(stdout, stderr, exitCode)`（Success/Failure 文本一致）；失败抛 `AdbCommandException(command="install/install-multiple ...", exitCode, stderr)`。
- **删除旧 `install(serial, apkPath, reinstall)`**（无死代码红线）；`DeviceRepository.install` 与 `AppConsoleViewModel.install` 改签名。

TDD：
- `install_single_with_flags_builds_correct_argv` —— fake runner 断言收到的 argv = `[-s, serial, install, -r, -t, <path>]`（flags 组合）。
- `install_multiple_builds_install_multiple_argv` —— 两个路径 → `install-multiple -r -d -g p1 p2`。
- `install_failure_throws` —— 录制 `Failure [INSTALL_FAILED_*]` stdout，断言抛 `AdbCommandException`。
- `install_empty_paths_throws_argument` —— 防御。

### 4.2 desktop

`AppConsoleViewModel`：
- `fun install(paths: List<String>, flags: InstallFlags)` 替换旧 `install(apkPath)`。
- 状态机单测：成功（`_message` + 刷新包列表）、失败（`_error` 折叠 stderr）、空路径守卫、busy 互斥。

`AppConsoleScreen`：
- 安装按钮旁加 flags 复选框行（本地 Compose state）：`Reinstall(-r)` 默认勾上（对齐当前 `reinstall=true` 行为）、`Allow test(-t)`、`Downgrade(-d)`、`Grant perms(-g)`。
- 文件选择改多选：`FileDialogs.pickFiles(plural, filePattern="*.apk")`（需补，见 §7）。
- 拖拽：`onDrop` 收集所有 .apk，**≥2 个走 `installMultiple` 一次**，1 个走单文件 `install`（带当前 flags）。**顺手修 split-apk 逐个装的 bug**。
- i18n：flag 标签 + 安装多文件提示。

## 5. C — am start 构建器（App Console Advanced）

### 5.1 core

`CommandRunner.startActivity(serial: String, action: String?, data: String?, component: String?, extras: List<Extra>): String`

- argv：`shell, am, start` + 可选 `-a <action>` + 可选 `-d <data>` + 可选 `-n <component>` + extras（`<type.flag> <key> <value>` per extra）。
- `action` 与 `component` 至少一个非空（core 不强制，VM 守卫；core 收到都空时 adb 自己报错抛 `AdbCommandException`，可接受）。
- 复用既有 `Extra`/`ExtraType`（`domain/`）。
- 保留 `startApp`(monkey LAUNCHER) 与 `startAppActivity`（仅 -n）——简单场景仍用它们。
- 返回 stdout（`am start` 成功输出 `Starting: Intent { ... }`，失败 adb 非零退出抛 `AdbCommandException`）。

TDD：
- `startActivity_with_action_and_data_builds_argv` —— action=VIEW, data=http://x → argv 含 `-a android.intent.action.VIEW -d http://x`。
- `startActivity_with_component_and_extras` —— component=`pkg/.Act`, extras=[string k=v] → argv 含 `-n pkg/.Act --es k v`。
- `startActivity_failure_throws` —— 非零退出抛 `AdbCommandException`。

### 5.2 desktop

`AppConsoleViewModel`：
- `fun startActivity(action: String?, data: String?, component: String?, extras: List<Extra>)`。
- 状态机单测：成功（`_message`）、失败（`_error`）、空守卫（action 与 component 都空时按钮禁用，不调）。

`AppConsoleScreen` AdvancedPanel：
- 现有"am start"段（仅 Activity 名）扩展为：Component（`OutlinedTextField`，placeholder `pkg/.MainActivity`）、Action、Data URI、Extras 行。
- **抽取 `ExtrasEditor` 共享 composable**（从 broadcast 段抽出），am start 与 broadcast 共用，消除两份重复的 extras 行 UI。
- Component 段处理：用户只填 component → `startActivity(component=...)`；只填 action+data → deep link；都填 → 一起传。
- i18n：字段标签 + 段标题。

## 6. D+E — dumpsys package 共用：应用详情（E）+ 权限 grant/revoke（D）

E（应用详情：version/path/libs）与 D（权限）都解析同一条 `adb shell dumpsys package <pkg>` 的输出，挖不同字段。为避免两个 parser 重复解析同一 stdout、避免 UI 连用详情+权限时跑两次 dumpsys，**两者共用一个 parser + 一份 fixture + 一个 CommandRunner 方法**。E 的 nativeLibs 来自 `ls nativeLibraryDir`（复用既有 `LsParser`），不依赖 dumpsys。

### 6.1 共用 core：DumpsysPackageParser + dumpsysPackage

`PermissionInfo`（同原 D §6.1）：
```kotlin
data class PermissionInfo(
    val name: String,
    val granted: Boolean,
    val runtime: Boolean,
    val group: String? = null,
)
```

`DumpsysPackage` data class（`domain/`，NEW 聚合体）：
```kotlin
data class DumpsysPackage(
    val versionName: String?,
    val versionCode: Long?,
    val codePath: String?,          // /data/app/.../base.apk 或 app 目录
    val publicSourceDir: String?,   // base apk 路径
    val nativeLibraryDir: String?,
    val primaryCpuAbi: String?,
    val permissions: List<PermissionInfo>,
)
```

`DumpsysPackageParser` —— 纯函数 object（`core/adb/`），**由原 `DumpsysPackagePermissionsParser` 改名扩签名**：
- 输入：`adb shell dumpsys package <pkg>` 完整 stdout。
- 解析 permissions 段（requested/install/runtime）照旧 → `List<PermissionInfo>`（runtime 段标 `runtime=true`）；新增 `versionName=` / `versionCode=` / `codePath=` / `publicSourceDir=` / `nativeLibraryDir=` / `primaryCpuAbi=` 行（均出现在 `Packages:` 段 key=value 形式）。
- 输出：`DumpsysPackage`。
- 缺字段记 `null`，不静默吞；整个输出找不到 `Packages:` 段视为解析失败 —— **parser 返回 `null`**（对齐 `:core` 既有 parser "找不到目标段返回 null/空、不抛" 的惯例；具体在 plan 阶段读 `GetpropParser`/`PackageListParser` 确认对齐），`CommandRunner.dumpsysPackage` 收到 null 包成 `AdbCommandException(command="adb shell dumpsys package <pkg>", exitCode=-1, stderr="<dumpsys 原文前 200 字> no Packages: section")`，带 adb 原文兜底，不静默吞。
- **fixture 共用**（CLAUDE.md §4）：`core/src/test/resources/fixtures/dumpsys_package_<variant>.txt`，首行注释标设备型号+manufacturer+Android 版本+SDK+build id+录制日期+命令（`adb shell dumpsys package <pkg>`）。**≥2 个变体**（如 Android 11 phone + Android 13+ TV；现代 Android `runtime permissions:` 段，老版本格式不同）。**E 和 D 共用同一批 fixture**——录一次即覆盖两边字段。不许手写 fixture。

`CommandRunner.dumpsysPackage(serial: String, pkg: String): DumpsysPackage`
- `runShellCmd(serial, "dumpsys package $pkg")`（已 sanitizeShellOutput）→ `DumpsysPackageParser.parse(stdout)`（parser 返回 null 时包成 `AdbCommandException`，见上）。
- pkg 守卫：`^[A-Za-z0-9._]+$`（同 SystemInfoViewModel 包名守卫，纵深防御）；非法 → IllegalArgumentException。
- 非零退出抛 `AdbCommandException`（含 stderr）。

（**不保留 `listPermissions` 薄包装**——无死代码红线：VM 直接调 `dumpsysPackage(...).permissions` 取权限，少一层间接；D 的 VM 单测同样可断言 `dumpsysPackage` 返回的 `.permissions`。）

`CommandRunner.grant(serial: String, pkg: String, perm: String): String`
- `runCmd(serial, listOf("shell", "pm", "grant", pkg, perm)).stdout`；非零抛 `AdbCommandException`（某些权限非 runtime 或已授予会报错，透传 adb 原文）。

`CommandRunner.revoke(serial: String, pkg: String, perm: String): String`
- `pm revoke`，同上。

TDD（`DumpsysPackageParserTest` + `CommandRunnerTest`）：
- parser 读 fixture：`dumpsys_package_android13.txt` → 断言 `versionName/versionCode/codePath/nativeLibraryDir` 正确 **且** `permissions` 含 `android.permission.CAMERA` runtime granted=false（原 D 断言并入，一次断言两边字段）。
- `dumpsysPackage_passes_pkg_to_dumpsys` —— fake runner 断言命令是 `shell dumpsys package <pkg>` + 返回 parser 结果。
- `dumpsysPackage_rejects_invalid_pkg` —— 非法包名抛 IllegalArgumentException。
- `grant`/`revoke` 透传 + 非零抛异常（不变）。

### 6.2 E — 应用详情（App Console 行内展开，lazy）

`PackageDetail` data class（`domain/`，UI 视图，扁平、不含 permissions，与 `DumpsysPackage` 解耦）：
```kotlin
data class PackageDetail(
    val versionName: String?,
    val versionCode: Long?,
    val codePath: String?,
    val publicSourceDir: String?,
    val nativeLibraryDir: String?,
    val primaryCpuAbi: String?,
    val nativeLibs: List<String>,   // .so 文件名
)
```

`CommandRunner.listNativeLibs(serial: String, dir: String): List<String>`
- 复用既有 `ls(serial, dir)`（`LsParser`），取文件名以 `.so` 结尾者；`dir` 为空或目录不存在 → 空列表（不抛）。
- 守卫：`dir` 须为绝对路径且不含 shell 元字符（`^[^\s;&|`'$<>]+$`）；不合法 → 空列表 + 日志 warn（不抛，避免一个坏路径打断整行详情）。

`DeviceRepository.packageDetail(serial: String, pkg: String): PackageDetail`
- 调 `dumpsysPackage` → 取 version/path/libDir/abi；若 `nativeLibraryDir` 非空调 `listNativeLibs` 拼装 `PackageDetail`；否则 `nativeLibs = emptyList()`。
- 一次 dumpsys + 至多一次 ls。

desktop：
- `AppConsoleViewModel`：三个独立状态（对齐 §3.2 bugreport 模式）：`_detailBusy: MutableStateFlow<Boolean>`、`_detail: MutableStateFlow<PackageDetail?>`、`_detailError: MutableStateFlow<String?>`；`fun loadDetail(pkg)` + 切换选中包时清空。错误内联，不弹窗。
- `AppConsoleScreen` / `PackageSelectRow`：行尾加展开箭头；展开后在该行下方显示 version（`versionName (versionCode)`）、codePath / publicSourceDir（带复制按钮）、primaryCpuAbi、nativeLibs 列表（`.so` 文件名）；无 nativeLibraryDir 的 app 显示"无原生库"；busy 显示 inline spinner；失败红条 + adb 原文折叠。
- 行内详情与 Advanced 面板权限段独立加载，但底层都走 `dumpsysPackage`；**VM 持最近一次 `DumpsysPackage` 缓存（该做，非可选）**——detail 与 permissions 都从它取，连看不双跑 dumpsys；仅 `togglePermission` 成功后才局部更新缓存里的对应权限 granted（不重拉整表）。切换选中包时清缓存。

TDD：VM 状态机单测（加载成功 / 加载失败 / 无 native lib / 切换包清空 detail）。`listNativeLibs` 复用 `LsParser` 既有测试覆盖，无需新 fixture。

### 6.3 D desktop — 权限 UI

`AppConsoleViewModel`：
- `_permissions: MutableStateFlow<List<PermissionInfo>>`、`_permissionsBusy`、`_permissionsError`、`_cachedDumpsys: MutableStateFlow<DumpsysPackage?>`（与 E 详情共用缓存，见 §6.2）。
- `fun loadPermissions(pkg: String)`：调 `repo.dumpsysPackage(...)`，缓存到 `_cachedDumpsys`，取 `.permissions` 填 `_permissions`。若 `_cachedDumpsys` 已是该包则直接取 `.permissions` 不重跑 dumpsys。
- `fun togglePermission(pkg: String, perm: String, grant: Boolean)`（grant=true 调 grant，false 调 revoke；成功后局部更新 `_permissions` 与 `_cachedDumpsys` 里对应权限的 granted，避免整表重拉）。
- 状态机单测：加载成功/失败、toggle 成功局部更新 granted、toggle 失败 `_error` + granted 不变。

`AppConsoleScreen` AdvancedPanel 加"权限"段：
- 选中包后可"加载权限"按钮 → 列出 runtime 权限（name + granted 复选框）；install permissions 只读展示（granted 状态文本，无 toggle）。
- 权限多时用 `LazyColumn heightIn(max=200)` + 滚动，避免撑爆 Advanced 面板。
- toggle 复选框 → `vm.togglePermission`；busy 时禁用。
- i18n：段标题/加载/状态。

### 6.4 D+E 的前置约束（流程）

parser 脆弱 + 跨版本变体 → **fixture 未就位前不写 parser**（避免手写 fixture 踩 CLAUDE.md §4 红线）。E 和 D 共用 fixture，录制一次即可解锁两者。fixture 未到时：
- D：`grant`/`revoke`（无 fixture 依赖）+ 权限 UI 骨架（空列表）可先做。
- E：`listNativeLibs` 方法本身无 fixture 依赖（复用 LsParser，可 TDD），但端到端 E 需要 `nativeLibraryDir` 来自 `dumpsysPackage`，故 E 整体随 fixture；fixture 未到时可先做详情 UI 骨架（version/path 占位"加载中/无数据"，nativeLibs 空）。
- 排期：D+E 绑定排在 A/B/C 之后；fixture 录制是一次性真机操作（录 `dumpsys package <某三方包>`，D 和 E 同时受益）。

## 7. 跨切面

- **i18n**：所有新文案走 `Strings.t`（zh+en）。新增 key 约估：A ~6、B ~6、C ~5、D ~8、E ~6（version/path/libs/无原生库/复制等）。
- **测试**：`:core` TDD 全覆盖（CommandRunnerTest + DumpsysPackageParserTest）；`:desktop` VM 状态机单测（含 E 的详情状态机）。Compose 快照测试不强制。
- **platform/FileDialogs**：A 需要选目录；B 需要多选文件。检查既有 `FileDialog` 能力，缺则补 `pickDirectory` / `pickFiles`（platform 层，`:core` 接口在既有 `PathProbe`/文件对话框抽象里——实现确认中，plan 阶段核实）。
- **提交节奏**：每项独立提交，Conventional Commits：`feat(core): bugreport` / `feat(core,desktop): install-multiple + flags` / `feat(core,desktop): am start builder` / `feat(core,desktop): dumpsys-package 详情+权限`（D+E 合并 parser，一次提交或拆 core/desktop 两次）。E 的 nativeLibs 复用 LsParser 不单列。
- **顺序**：B → C → A → D+E（B 顺手修 split-apk bug 价值高且独立；C 复用既有 Extras UI 抽取，中；A 独立 System Ops；D+E 最后，共用 fixture，绑一起录一次 dumpsys 录制）。每项可独立交付/合并，唯 D+E 在 parser/fixture 上耦合。
- **技术债不扩**：不引入占位代码（D+E 的 UI 骨架在 fixture 未到前不渲染"假数据"，权限列表空就空、详情字段占位"加载中/无数据"而非假版本号）；重构移除旧 `install` 签名时同时删声明与所有引用；`DumpsysPackagePermissionsParser` 改名为 `DumpsysPackageParser` 时删旧名。
- **错误处理**：命令失败内联红条 + adb 原文折叠（既有模式）；bugreport 长时 spinner + 可取消；权限 toggle 失败不中断整表；E 详情加载失败内联、不弹窗。

## 8. 实现顺序与依赖

```
B (install-multiple) ──┐
C (am start) ─────────┤── 独立，可并行
A (bugreport) ────────┘
D+E (dumpsys package 详情+权限) ── 共用 parser+fixture；fixture 录制前 grant/revoke+listNativeLibs+UI 骨架可先做，dumpsysPackage 字段待 fixture
```

每项 bounded，可按需挑顺序。建议 B 先（含 bug 修复价值）；D+E 绑一起最后做。

## 9. 待决问题

- `FileDialogs` 是否已有 `pickDirectory` / `pickFiles` 多选？plan 阶段第一步核实；缺则在 platform 层补（Windows 实现 + `:core` 接口如有必要）。
- **bugreport 的超时与取消语义**：既有 `AdbProcessRunner.run`（文本版）据 CHANGELOG 无 timeout（仅 `runBinary` 加了）；bugreport 走 `run` 时——(a) 协程 `cancel()` 是否真正终止 adb 子进程（`run` 实现是否响应 cancellation）需 plan 阶段读 `JvmAdbProcessRunner.run` 确认；若不响应，则给 `run` 加 `timeoutMs` 参数（对齐 `runBinary` 的 async 读 + `withTimeoutOrNull` + 强杀），或 bugreport 改走 `runBinary` 再解析 stdout 文本。这是 A 的实现前提。
- D+E 的 fixture：用户需在 ≥2 台不同 Android 版本设备上录制 `adb shell dumpsys package <某三方包>` 输出。**同一批 fixture 服务 D（permissions 段）和 E（version/path/nativeLibraryDir 段）**，录一次两边受益。若无法立即录制，D+E 的 `dumpsysPackage` 字段（version/path/perms 解析）整体后置；`grant`/`revoke` + `listNativeLibs`（复用 LsParser，无 fixture 依赖）+ 两边 UI 骨架可先做。当前设计坚持完整 GUI + 真实 fixture，不手写。

## 10. 红线检查清单（对照 CLAUDE.md）

- [ ] `:core` 新方法无 UI import（含 E 的 `listNativeLibs`、`dumpsysPackage`）
- [ ] UI 只经 `DeviceRepository` 回调（E 的 `packageDetail` 也透传）
- [ ] `:core` 不起真 adb，走 `AdbProcessRunner`，测试用 Fake
- [ ] 平台差异在 `:desktop/platform`
- [ ] 解析（D+E 合并的 `DumpsysPackageParser`）与执行（CommandRunner）分离；旧名 `DumpsysPackagePermissionsParser` 已删
- [ ] `:core` I/O 注入 Dispatcher（bugreport 的 `runner.run` 已走既有 runner，无新 Dispatcher 硬编码；新 store 无）
- [ ] 无死代码（删旧 `install` 签名 + 所有引用；`DumpsysPackagePermissionsParser` 改名后旧名不残留）
- [ ] 跨线程可变状态标记（ViewModel 的 `bugreportJob` 在主线程 scope，无需 `@Volatile`；如引入后台 job 句柄则标注）
- [ ] D+E 的 fixture 真实录制（同一批 `dumpsys_package_*.txt` 服务两边）+ 首行注释完备
- [ ] TDD：`:core` 先红测后绿测（`DumpsysPackageParserTest` 一次断言 version/path + permissions）
- [ ] i18n 全覆盖 zh+en（含 E 的 version/path/libs/无原生库文案）

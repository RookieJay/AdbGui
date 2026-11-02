# ADB GUI — Logcat AS 式过滤（包名 / Tag / 右键快捷 / 持久化）

- **日期**：2026-10-14
- **状态**：已评审，待实现
- **范围**：Logcat 页过滤增强——①包名/应用过滤下拉；②Tag 下拉 + 多重排除；③日志行右键快捷过滤；④过滤条件持久化
- **基线**：`docs/superpowers/specs/2026-08-17-logcat-design.md`（修订其 §过滤模型/§过滤栏；其余章节仍有效）
- **依据**：对标 Android Studio Logcat 面板；现状只有级别下拉 + 全文搜索，`LogcatFilters` 的 `tagInclude/tagExclude/pid` 字段无 UI 暴露

## 1. 背景与目标

| # | 能力 | 现状 | 目标 |
|---|---|---|---|
| 1 | 按应用过滤 | 无 | 下拉选 app，只看它的日志（AS 的 package 过滤等价物） |
| 2 | 按 tag 过滤 | 模型有字段，UI 无 | 下拉从缓冲区现算 tag 列表（频次降序）单选；右键可排除多个 |
| 3 | 行级快捷过滤 | 无 | 右键日志行 → 只看/排除此 tag、只看此 PID |
| 4 | 过滤条件持久化 | 无 | 记住上次的过滤条件，重启恢复 |

**架构不变**：ring 全量缓存 + 客户端过滤；`:core` 持状态与解析，UI 只渲染 + 转发（基线 spec 分层不变量继续有效）。

## 2. 核心决策

### D1：包名 → pid 用 `pidof` 解析，客户端按 pid 集合过滤

logcat threadtime 行里有 pid 无 uid，需建立包名→日志行的映射。三条候选路线：

- **pid 方案（选定）**：选包后 `adb shell pidof <pkg>` 解析出 pid 集合，`matches()` 客户端按 `line.pid in pids` 过滤。
- uid 方案（`-v threadtime,uid` + `pm list packages -U`）：AS 同款原理、app 重启后精确覆盖，但需改流格式 + 解析器 + fixture，且 Android 7 以下（用户的 TCL Android 6 TV）不支持 uid 修饰符，仍需降级到 pid 方案——两套都要做，本次不做。
- 服务端 `logcat --uid=<uid>`：与"ring 全量、客户端过滤"架构冲突（切包后其他过滤的历史丢失），否决。

pid 方案的已知限制（接受并写进 UI 文案）：
- app 未运行时选中它看不到任何日志（软提示，非错误）；
- isolated 进程（`pkg:xxx` 命名）pidof 抓不到；
- 重解析**之前**已死进程的历史行无法归属（重解析启动后旧 pid 累加进集合，不丢）。

### D2：重解析循环——pid 只增不减

- `packageName` 被设置时：立即解析一次，之后常驻子 job 每 **5s** 重解析（间隔为构造参数 `reResolveIntervalMs`，测试注小值，同 `publishIntervalMs` 模式）。
- 每次解析结果**并集**进 `filters.pids`（app 重启换 pid 后，旧 pid 的历史行仍显示）；`packageName` 清空则停循环并清 `pids`。
- 重解析失败（设备闪断 / adb 异常）：log warn + **保留旧 pids**，不清空、不打断流。
- 设备切换（`start()`）时若 `packageName` 已设：重新解析一次（换设备 pid 空间不同）。

### D3：`pids` 进 `LogcatFilters` 值对象（而非 controller 侧挂状态）

理由：`dumpLogcat()`（全量导出）以 `matches(line, filters)` 复用同一谓词，pid 集合必须在 filters 里，否则导出路径丢包名过滤。右键"只看此 PID" = `pids=setOf(pid)` 且 `packageName=null`（此时无重解析循环——两个语义不混）。

### D4：过滤条件持久化到 settings.json，防抖保存

- `Settings` 增加 `logcatFilters`（`@Serializable` 嵌套 data class）：levelSet（名称列表）、tagInclude、tagExcludes、text、textIsRegex、**packageName**。**不存 pids**（会话态，恢复时按 D2 重解析）。
- VM 在过滤条件变化后 **500ms 防抖**调 `store.update { }` 保存；LogcatScreen 初始本地状态取自已保存值（延续"本地 state 驱动输入、setFilters 异步生效"的现有模式）。

## 3. 组件设计

### 3.1 `LogcatFilters`（`LogcatController.kt`）

```kotlin
data class LogcatFilters(
    val levelSet: Set<LogcatLevel> = LogcatLevel.entries.toSet(),
    val packageName: String? = null,        // 用户选的 app（下拉）；null = 无包过滤
    val pids: Set<Int> = emptySet(),        // 生效 pid 过滤（包 pid 集合或右键单 pid）
    val tagInclude: String? = null,         // 单选（Tag 下拉）
    val tagExcludes: List<String> = emptyList(),  // 多重排除（右键累加）
    val text: String? = null,
    val textIsRegex: Boolean = false,
)
```

变更：删 `pid: Int?`（被 `pids` 取代）；`tagExclude: String?` → `tagExcludes: List<String>`。

`matches()` 新增：
- `f.pids.isNotEmpty() && line.pid !in f.pids` → false；
- `f.tagExcludes` 任一（blank 项忽略）与 `line.tag` contains（忽略大小写）→ false。

### 3.2 `CommandRunner.pidOf(serial, pkg): List<Int>`

- `adb shell pidof <pkg>`（`runCmd`）；stdout trim 后按空白 split，`toIntOrNull` 收集。**空输出（含纯空白）= 未运行，返回空列表**，不是错误。
- `pkgRegex` 守卫（同 `dumpsysPackage`，防 shell 注入）。
- 传输层失败（`AdbCommandException`）正常抛给调用方；由 controller 的 D2 失败路径兜住。
- DEBUG 级记命令与结果摘要。

### 3.3 `LogcatController`

- `setFilters(f)`：`f.packageName != current.packageName` 时——非空：kick 重解析循环（首拍立即）；空：停循环并清 `pids`。其余情况照旧（O(N) 重算 + 立即 publish）。
- 重解析循环：controller scope 常驻 job，`packageName` 非空期间 `while { delay(interval); pidOf(); 并集; 若变化 → 更新 _filters.value.pids + recomputeFiltered + publish }`。串行化与 `mutex` 的关系：pidOf 是挂起网络调用，不得持锁调用；结果并集与重算在 `mutex.withLock` 内。
- `start(serial)`：进入时若 `packageName` 非空 → 重解析一次（D2）。
- 新增 `snapshotTags(): List<Pair<String, Int>>`：从已发布的 `lines` 快照（immutable List，无锁、非挂起）现算 distinct tag→count，频次降序，cap 200。tag 列表天然跟随当前过滤视图（如已选包过滤则只列该 app 的 tag）。打开 Tag 下拉时调，不做 StateFlow。
- `dumpLogcat`：签名不变（filters 已含 pids，谓词自动生效）。

### 3.4 `Settings` / `SettingsStore`

```kotlin
@Serializable
data class SavedLogcatFilters(
    val levelSet: List<String> = listOf("V","D","I","W","E","F"),
    val tagInclude: String? = null,
    val tagExcludes: List<String> = emptyList(),
    val text: String? = null,
    val textIsRegex: Boolean = false,
    val packageName: String? = null,
)
// Settings 增加：val logcatFilters: SavedLogcatFilters = SavedLogcatFilters(),
```

序列化向后兼容（新字段带默认值）。levelSet 存名称（`LogcatLevel.valueOf`，未知名称忽略跳过）。

### 3.5 `LogcatViewModel`

- 新增 `packages: StateFlow<List<PackageInfo>?>`——首次需要时（UI 调 `loadPackages()`）经 `DeviceRepository.listPackages` 加载并缓存，加载失败内联错误（`packagesError`）。注意：VM 现构造不持 `DeviceRepository`，需加注入（`Main.kt` 接线）。
- 新增 `snapshotTags()` 转发 controller。
- 新增 `savedFilters: SavedLogcatFilters?`——构造时从 `store.state` 读一次（注入 `SettingsStore`，先例：`SettingsViewModel`）；`setFilters` 包装：转发 controller + 500ms 防抖写 `store.update`。
- 持久化失败（settings.json 写失败）：log warn，不打断（静默降级，下次再存）。

### 3.6 `LogcatScreen`（过滤栏 + 右键）

过滤栏布局：

```
[级别 ▾] [App ▾] [Tag ▾] [搜索框 weight 1f + 正则 pill] [⏸] [⏺] [📁] [🗑] [📋] [⬇]
```

- **App 下拉**：`DropdownMenu` 面板 = 搜索 TextField + LazyColumn（包名）+「显示系统应用」Checkbox + 顶部「全部」项。数据 `vm.packages`（打开时 `loadPackages()`，已缓存不重发）；默认只列三方，勾选后含系统。选中后按钮文案显示包名（超长省略）。`packageName` 非空且 `pids` 空且无行 → 列表区顶部软提示「应用未运行或暂无日志」（caption 样式，非错误 banner）。
- **Tag 下拉**：面板 = 搜索 + LazyColumn（`vm.snapshotTags()` 结果过滤），单选 include；顶部「全部」清除 include（**不清除** excludes——排除是叠加语义，右键/Tag 下拉行内提供单独清除入口：每个列表项右侧「排除」小按钮 + 顶部「清除排除」项）。
- **行右键**：行外包 `Box` + `mouseClickable`（`buttons.isSecondaryPressed`）→ `DropdownMenu`：「只看此 tag」「排除此 tag」「只看此 PID」「清除 PID/应用过滤」（仅 `pids`/`packageName` 激活时显示）。与 `SelectionContainer` 的事件冲突在实现时实测调顺（必要时右键区域用独立 layer，不影响左键选择）。
- 本地过滤 state（levelSet/text/useRegex/tagInclude/tagExcludes/packageName）初始值取自 `vm.savedFilters`；`vm.setFilters` 调用点全部改为传完整 `LogcatFilters`（含 tag/包字段）。

### 3.7 i18n

`Strings` 新增 zh/en 双语键：App 下拉（应用/全部/显示系统应用/搜索包名/加载失败/未运行提示）、Tag 下拉（tag/全部/排除/清除排除）、右键菜单四项、按钮 aria 文案。

## 4. 边界与不做的事

- **录制不受视图过滤影响**：LogcatRecorder 录的是独立全量流，本特性不改它。
- `-v uid` 流格式升级、isolated 进程（`pkg:xxx`）覆盖、AS 式 `tag:x level:y` 查询语法、tag 颜色——不做（YAGNI）。
- 包列表不做"运行中优先"分区（需 `ps -A` 新命令+解析，价值低；选中后 pidof 自然揭示运行态）。
- 过滤条件**全局**存一份（不按设备分存；AS 同样全局记 query）。
- 右键菜单不做「排除此 PID」（负 pid 过滤模型复杂度不值）。

## 5. 错误处理

- pidof 失败：D2 保旧值路径（log warn，无 UI 打断）。
- 包列表加载失败：App 下拉面板内内联错误文案 + 重试（再次打开下拉即重试）。
- settings 保存失败：静默降级（log warn）。
- `packageName` 未运行：软提示，非错误；用户切走/清过滤即恢复。
- 全部遵守「错误本地化，不弹模态」。

## 6. 测试计划（:core TDD，先写失败测试）

`CommandRunnerTest` 新增：
1. `pidOf` 单 pid / 多 pid / 空输出（未运行）→ fixture 真实录制（见 §7）。
2. `pidOf` 非法包名（空格/元字符）→ require 拒绝。

`LogcatControllerTest` 新增/更新：
3. `pids` 过滤（包 pid 集合）+ `dumpLogcat` 同谓词。
4. 设 `packageName` → 首拍立即解析（Fake `whenArgsContains(["pidof"])` 脚本）；重解析 interval（注入 10ms）后新 pid 并集进来、旧 pid 保留。
5. pidof 脚本抛错/返回失败 → 旧 pids 保留、无异常外泄。
6. `packageName` 清空 → 循环停（后续 pidof 脚本不再被调用，可断言 `runs`）+ `pids` 清空。
7. `tagExcludes` 多重排除、blank 忽略、忽略大小写。
8. `snapshotTags()`：频次降序、cap 200。
9. 既有用例更新：`pid: Int?` → `pids`。

`SettingsStoreTest` 新增：
10. `logcatFilters` 序列化往返（含默认值向后兼容：旧 json 无该字段可加载）。

`LogcatViewModelTest` 新增：
11. `loadPackages` 转发 + 失败内联。
12. `setFilters` 防抖持久化（`runTest` + advanceTimeBy 500ms；持久化失败静默）。
13. `savedFilters` 恢复初值。

## 7. 真机 fixture（实现期录制，规则见 CLAUDE.md §技术债 4）

| fixture | 命令 | 覆盖 |
|---|---|---|
| `pidof_single.txt` | `adb -s <serial> shell pidof <运行中的三方包>` | 单 pid |
| `pidof_multi.txt` | 选一个多进程 app（如 `com.android.systemui` 或微信类） | 空格分隔多 pid |
| `pidof_empty.txt` | `adb -s <serial> shell pidof com.not.installed.app` | 空输出 |

文件头注释：设备型号/manufacturer/Android 版本/SDK/build id + 录制日期 + 命令。

## 8. 实现顺序（供 writing-plans 展开）

1. `LogcatFilters` 模型变更 + `matches()` + controller 既有测试更新（纯重构先行）。
2. `CommandRunner.pidOf` + fixture + 测试。
3. controller 重解析循环 + `snapshotTags()` + 测试。
4. `Settings`/`SettingsStore` 新字段 + 测试。
5. VM：packages/snapshotTags/savedFilters/防抖持久化 + 测试。
6. UI：过滤栏三下拉 + 右键菜单 + 软提示 + i18n + `Main.kt` 接线。
7. 真机验证（用户执行）：TCL Android 6 上选包过滤 + 重启 app 后 pid 并集 + 持久化重启恢复。

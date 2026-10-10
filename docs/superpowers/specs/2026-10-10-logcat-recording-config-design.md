# ADB GUI — Logcat 录制优化（可配置目录 + 总量上限 + 按日合并）

- **日期**：2026-10-10
- **状态**：设计已与用户确认（对话定稿），待复核后转 writing-plans
- **范围**：修订 2026-09-30 录制子系统设计——①录制目录可配置（热切换 + 自动迁移）；②auto 总量上限清理；③auto 文件按日合并（同设备同天续写）；④auto/manual 子目录区分（文件名去掉前缀）；⑤存量文件一次性归位；⑥设置页录制管理 UI
- **基线**：`docs/superpowers/specs/2026-09-30-logcat-recording-design.md`（本文件修订其 §3.2/§3.3/§3.4/§7；其余章节仍有效）
- **依据**：实测 `logcat-recordings/` 两天 21 个文件共 190 MB——文件随设备重连频率碎片化、磁盘占用无总量约束、目录写死在配置目录

## 1. 背景与痛点

| # | 痛点 | 现状 |
|---|---|---|
| 1 | 目录写死 | `Main.kt` 构造时固定 `configDir/logcat-recordings`，无法指向大盘 |
| 2 | 总量无上限 | 只按 7 天清理，高频设备 7 天内可积 1 GB+ |
| 3 | 文件碎片化 | 每次设备上线开新文件，WiFi adb 一天重连 20 次 = 20 个碎文件 |
| 4 | 空文件残留 | 设备闪连产生 0 字节文件，永远不会清 |
| 5 | 设置页无感知 | 看不到目录、占用大小，无打开入口 |

另发现基线 spec 与实现不一致：spec §3.2 说手动录制文件带 `manual_` 前缀，实现实际是 `<label>_<stamp>.log` 无前缀、清理靠"非 `auto_` 前缀"区分。本设计统一改为**子目录区分**，一并消除该不一致。

## 2. 核心决策

### D1：auto/manual 子目录区分，文件名无前缀

```
<录制目录>/
├── auto/                        ← 受 7 天 + 总量 + 0 字节清理约束
│   └── <label>_<yyyyMMdd>.log           （轮转：<label>_<yyyyMMdd>_<n>.log）
└── manual/                      ← 永不自动删
    └── <label>_<stamp>.log
```

语义编码在目录而非文件名：清理只扫 `auto/`，`latestRecordingFile()` 扫两个子目录取最新。`<label>` 维持现状（品牌_型号_Android版本，getprop 失败回退 serialSafe）。

### D2：目录可配置，热切换 + 自动迁移

- 有效目录 = `settings.logcatRecordingsDir`（null = 默认 `configDir/logcat-recordings`）。
- 运行中改目录 → 迁移序列（io 上，持 mutex）：
  1. 停止全部录制（auto + manual；manual 以 `recording_dir_changed` 收尾，已写内容不丢）；
  2. 旧目录的 `auto/`、`manual/` 及根目录残留 `.log` 逐个移动到新目录对应位置（逐文件 `runCatching`，失败跳过 + WARN，不阻塞）；
  3. 旧目录空了删空目录；
  4. 对当前在线设备在新目录重启 auto。manual 不自动重启（用户手动点开始）。
- 不弹进度条（文件量小，同步完成）。

### D3：auto 按日合并，同设备同天续写

- 设备上线起录：`auto/<label>_<yyyyMMdd>.log`，当天已存在则**追加**（append 模式）。
- 轮转续开：`auto/<label>_<yyyyMMdd>_<n>.log`（目录内最小未用 n），**新文件不追加**——100 MB/文件封顶语义不变。
- 文件日期 = 当天首次起录日；跨午夜不换文件（会话不中断就续写当天那个），跨天靠重连/轮转自然分界。已知语义，接受。
- 追加起录时写标记行 `--------- adbgui session start <ts> serial=<serial>`（与既有 reconnect 标记同风格，可 grep 区分会话）。

### D4：总量上限（与 7 天双约束）

- `enforceSizeCap()`：`auto/` 下按 `lastModified` 从旧到新删除，直到总占用 ≤ 上限。
- **排除正在写的文件**（活动会话的文件删了 watchdog 会傻重启）与 manual（本来就不在 `auto/`）。
- 排除后仍超限（上限设得比活动写入总量还小）：只 WARN，不硬停录制。
- 触发时机：启动清理时、每次开新 auto 文件后（含轮转续开）、设置里改上限后。
- 7 天保留保留不变，与总量谁先触发谁删。

### D5：存量文件一次性归位

启动清理时（io 上，runCatching 包裹）：若录制目录**根**下有 `.log` 文件（旧版本布局），`auto_*.log` 挪进 `auto/`（文件名保留前缀不重命名——反正 7 天内会被清掉），其余 `.log` 挪进 `manual/`。挪不动的 WARN 跳过，下次启动再试。归位后所有逻辑只面对 `auto/`+`manual/` 统一布局，不再兼容根路径扫描。

## 3. 组件设计修订

### 3.1 Settings（`SettingsStore.kt`）

```kotlin
/** null = 默认 <配置目录>/logcat-recordings（spec D2）。 */
val logcatRecordingsDir: String? = null,
/** auto 录制总占用上限（MB）。超出删最旧 auto 文件；manual 永不自动删（spec D4）。默认 500。 */
val logcatRecordingMaxTotalMb: Int = 500,
```

编译期常量维持：7 天保留、100 MB 轮转（不做设置项，YAGNI）。序列化向后兼容（新字段带默认值）。

### 3.2 LogcatRecorder

构造新增 `append: Boolean = false`：

- `true` 时 `Files.newBufferedWriter(file, CREATE, APPEND)`（现状为 `TRUNCATE_EXISTING`）；
- `bytesWritten` 初值 = 追加目标的现有文件大小（轮转阈值按**文件总大小**生效，而非本会话增量）；
- `lineCount` 仍从 0 起（会话语义）。

manager 负责决定传不传 append（当天文件存在与否）。

### 3.3 LogcatRecordingManager

- 构造参数 `recordingsDir: Path` 语义改为**默认目录**（`Main.kt` 仍传 `configDir.resolve("logcat-recordings")`）；有效目录私有 getter 从 `settings.state.logcatRecordingsDir` 推导。`recordingsDir` 从 `val` 公开属性收窄为私有。
- `observeDevices` 的 combine 增加 `logcatRecordingsDir` 维度：检测变化 → D2 迁移序列（迁移期间 mutex 持有，observeDevices 自身逻辑天然串行在 collector 里，无额外锁问题）。
- combine 增加 `logcatRecordingMaxTotalMb` 维度：变化 → 立即 `enforceSizeCap()`。
- `startAutoLocked`：文件名改 D3 规则（`auto/` 子目录 + 日期命名 + 存在即追加）；每次开新文件后调 `enforceSizeCap()`。
- `startManualLocked`：文件写 `manual/` 子目录（命名不变）。
- `cleanupOldAutoFiles()`（改名 `cleanupOnStartup()`）：在 io 上依次做 D5 归位 → 7 天清理 → 0 字节清理（`auto/` 下 0 字节且 lastModified 超 1 天）→ `enforceSizeCap()`。全 `runCatching`，失败 WARN 不阻塞。
- 新增 `totalRecordingBytes(): Long`（io 上扫 `auto/`+`manual/` 求和，设置页占用展示用）。
- `latestRecordingFile()`：扫 `auto/`+`manual/` 两个子目录。
- `fileWatchdog` / `restartRecordersWithMissingFiles`：不变（文件被删 → 原地重启，当天文件已被删则开新文件，行为自洽）。

### 3.4 设置页（SettingsScreen "自动录制"节扩展）+ i18n

- **录制目录**行：当前有效路径（SelectableText）+「更改…」（`FileDialogs.pickDirectory`）+「打开」按钮（目录不存在先创建）。恢复默认 = 选择器里提供"默认"操作或留空逻辑——用「重置为默认」小按钮。
- **总上限**行：数字输入 + 应用（校验 50–100000 MB），沿用 ringCap 输入框交互。
- **占用展示**：`已用 190 MB / 上限 500 MB`（VM 经 `totalRecordingBytes()` 获取；目录/上限变化后自动刷新，节首次组合时加载一次）。
- `SettingsViewModel` 注入 `LogcatRecordingManager`（`Main.kt` 接线；VM→manager 转发，不越层）。错误内联展示。

### 3.5 i18n

`Strings` 新增 zh/en 双语键：目录标签/更改/打开/重置默认、上限标签/校验错误、占用展示文案、迁移失败状态文案。

## 4. 边界与不做的事

- 总量超限不硬停录制（写失败才停，那是 write_failed 路径的事）。
- 不做按小时分片、保留天数可配置、迁移进度条（YAGNI）。
- 迁移失败跳过即跳过，不重试不排队（下次启动归位逻辑兜底根目录残留）。
- 基线 spec §5 全部边界（应用关闭即停、TCL 空 logd、manual+auto 并存等）继续有效。

## 5. 错误处理

- 迁移/归位/清理全部 io + runCatching，单文件失败 WARN 跳过，不阻塞启动与录制。
- 目录不可写（用户选了只读路径）：新起的 recorder 走既有 write_failed 路径（error 状态 + 退避），设置页状态栏内联提示。
- `enforceSizeCap` 单文件删除失败（被占用等）：跳过该文件继续处理其余，不重试（下次触发时兜底）。

## 6. 测试计划（:core TDD，先写失败测试）

`LogcatRecorderTest` 新增：
1. append 模式：已有内容保留、新行追加、`bytesWritten` 起始 = 现有大小（注入小 rotationBytes 验证按文件总大小轮转）。
2. append 起录写 `session start` 标记行。

`LogcatRecordingManagerTest` 新增/更新：
3. 按日命名 + 当天文件存在 → 追加续写（不截断）。
4. 轮转续开 → `_n` 新文件、不追加、n 取最小未用。
5. `enforceSizeCap`：删最旧、跳过活动文件与 manual、删不到目标只记日志不抛。
6. 改目录（settings 更新）→ 迁移序列执行：文件挪到新目录子结构、auto 重启、manual 收尾 reason 正确。
7. 迁移中某文件移动失败（注入）→ 跳过 + 继续。
8. 启动归位：根目录旧 `auto_*.log`/手动文件分别挪进 `auto/`、`manual/`。
9. 0 字节且超 1 天的 auto 文件被删；0 字节但新鲜的（正在录）不删。
10. 既有用例更新：文件命名/目录布局变化导致的断言调整。

`SettingsStoreTest`：两新字段序列化默认值。

`SettingsViewModelTest`：setLogcatRecordingsDir / setLogcatRecordingMaxTotalMb 转发、占用加载。

## 7. 实现顺序（供 writing-plans 展开）

1. `Settings` 新字段 + store 测试。
2. `LogcatRecorder` append 模式 + 测试。
3. `LogcatRecordingManager`：目录推导 + 子目录布局 + 按日命名/追加 + 测试（更新既有命名断言）。
4. Manager：enforceSizeCap + 0 字节清理 + 启动归位 + 测试。
5. Manager：目录热切换迁移 + 测试。
6. `SettingsViewModel` + `SettingsScreen` UI + i18n + `Main.kt` 接线。
7. 真机验证（用户执行）：改目录迁移、按日合并、占用展示、总量清理（注入小上限已测，真机验首尾）。

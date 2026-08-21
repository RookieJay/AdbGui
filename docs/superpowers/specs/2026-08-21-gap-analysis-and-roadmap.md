# ADB GUI — 功能差距分析与后续规划

- **日期**：2026-08-21
- **状态**：规划草案，待用户决定优先级后逐项开 plan
- **依据**：对比 `D:\software\EasyADB` 的 `config/*.xml` 与本项目现有功能；本项目 CHANGELOG 各 v2 节

## 1. 背景

本项目 v1 + v2 已交付连接管理、应用管理、设备信息、截图、Logcat、Shell、文件浏览器、Nav Reorg（App Console + Device Overview）、scrcpy 投屏（含录制）、系统操作（reboot/root/remount）、adb pair。EasyADB（PyQt5 桌面工具）作为参照系，盘点本项目尚未覆盖的能力，规划后续。

## 2. 差距清单

### 2.1 已覆盖（无需重复）

| 能力 | 我们的实现 | EasyADB 对应 |
|---|---|---|
| 应用卸载/安装/停止/启动/重启/清数据 | App Console | 通用应用操作模板 |
| 广播 / ContentProvider 查询 | App Console 高级区 | 通用应用操作模板 |
| 截图 | Device Overview | Screen Shot |
| 视频录制 | scrcpy `--record` | Screen Record（`screenrecord`） |
| 按键模拟（返回/方向/确认/音量/菜单/电源） | Device Overview Remote 区 | 按键模拟模板 |

### 2.2 缺失功能与归属规划

| # | 能力 | adb 命令 | 推荐归属 | 工作量 | 优先级 |
|---|---|---|---|---|---|
| G1 | **文本输入** | `adb shell input text "xxx"` | Device Overview 遥控器区 | 小 | 高 |
| G2 | **系统信息查询页** | 见 §2.2.1 | 新独立页 | 中 | 中 |
| G3 | **adb 版本信息** | `adb version` | Settings 页底部 | 很小 | 低 |
| G4 | **monkey 压测** | `adb shell monkey ...` | System Ops 页内分区 | 中 | 低（用户暂不紧急） |
| G5 | **Shell/Root/Remount 挪到 Device Overview** | — | Device Overview 新增「设备工具」区 | 小 | 中 |
| G6 | **APK Helper** | 未知（EasyADB 未填说明） | 待定 | — | 暂缓 |

### 2.2.1 系统信息查询页（G2）详细规划

**性质**：只读查询，返回文本块，需要展示/复制/导出。与 System Ops（"做动作"）职责不同——系统信息是"看状态"。

**页面结构**：
- 左侧：命令列表（分组，同 EasyADB `cmdConfig.xml` 的 group/sub_group 结构）
- 右侧：结果文本区（`SelectableText` + 复制 + 导出按钮，复用 DeviceInfo 导出模式）
- 顶部：当前选中设备 serial 显示

**命令清单**（来自 EasyADB `cmdConfig.xml`，按分组）：

| 分组 | 命令 | 说明 |
|---|---|---|
| Packages | `pm list packages -f` | 所有包+路径（我们已有 `pm list packages -3` 三方包，这个是全量带路径） |
| Packages | `pm list libraries` | 系统支持的库文件 |
| Packages | `pm list features \| cut -c9- \| sort -u` | 硬件特性 |
| Packages | `pm path {pkg}` | 应用安装路径（需选包） |
| Packages | `dumpsys package {pkg} \| grep version` | 应用版本 |
| Display | `wm density` | 屏幕密度 |
| Display | `dumpsys window \| grep mCurrentFocus` | 当前焦点窗口（排查前台 Activity） |
| System / Prop | `getprop` | 全部系统属性（我们 DeviceInfo 已有解析版，这里是原始全量） |
| System / Disk | `dumpsys diskstats` | 磁盘状态 |
| System / Disk | `df -h` | 磁盘可用 |
| System / Memory | `dumpsys meminfo` | 全部内存信息 |
| System / Memory | `dumpsys meminfo {pkg}` | 指定应用内存（需选包） |
| System / Memory | `top -n 1` | Top 进程 |
| System | `ps` | 进程列表 |
| System | `cat /proc/cpuinfo` | CPU 处理器信息 |
| System | `dumpsys cpuinfo` | CPU 使用率 |
| System | `uptime` | 运行时长 |
| Network | `ifconfig` | 网络接口 |
| Network | `cat /sys/class/net/eth0/address` | MAC 地址 |
| Network | `cat /etc/hosts` | hosts 文件 |
| About / ADB | `adb version`（非 shell） | adb 版本 |
| About / ADB | `adb help`（非 shell） | adb 帮助 |

**实现要点**：
- `:core`：`CommandRunner.runShellCmd(serial, cmd): String`（通用 shell 命令执行，返回 stdout，失败抛 AdbCommandException）——这是关键新方法，所有查询都走它
- `:core`：**不需要新 Parser**——这些命令输出是给人看的文本，原样返回即可（和 DeviceInfo 的 `deviceDetailReport` 模式一致）
- `:desktop`：`SystemInfoViewModel`（持有「当前命令 + 结果文本 + busy」StateFlow）+ `SystemInfoScreen`（左列表 + 右文本区）
- `:desktop`：`AppShell` NavPage 加 `SYSTEM_INFO`，导航 6→7
- i18n：命令名翻译（zh/en）——命令分组标题 + 每条命令的显示名

**关于需选包的命令**（`pm path {pkg}` / `dumpsys meminfo {pkg}` / `dumpsys package {pkg}`）：页面顶部加一个包名选择下拉（复用 AppConsole 的 `listPackages` 结果，或独立调一次）。

## 3. 导航重构建议（G5 + G2 组合）

当前导航 6 项：DEVICE_OVERVIEW / APP_CONSOLE / LOGCAT / SHELL / SYSTEM_OPS / FILE_EXPLORER + Settings。

建议调整：

| 页 | 现有内容 | 调整后 |
|---|---|---|
| Device Overview | 截图/投屏/遥控器/设备信息 | + 文本输入（G1）+ Shell/Root/Remount 工具区（G5，从 System Ops 挪入） |
| App Console | 不变 | 不变 |
| Logcat / Shell / File Explorer | 不变 | 不变 |
| System Ops | reboot 类 + root + remount + shell | 保留 reboot 类 + **monkey 压测分区**（G4） |
| **系统信息（新页）** | — | G2 全部查询命令 |
| Settings | 不变 | + adb 版本信息（G3） |

导航 6→7（加「系统信息」）。System Ops 页精简为"影响设备运行状态的操作"（重启 + 压测），即时工具操作（shell/root/remount）归到 Device Overview。

## 4. 实现顺序建议

按工作量从小到大、依赖关系排列：

1. **G3 adb 版本信息**（很小，独立，无依赖）——快速见效
2. **G1 文本输入**（小，独立）——Device Overview 遥控器区扩展
3. **G5 Shell/Root/Remount 挪到 Device Overview**（小，纯 UI 重构 + Main/AppShell 接线）
4. **G2 系统信息页**（中，新页 + 新 VM + 新 CommandRunner 方法 + i18n）——本轮最大项
5. **G4 monkey 压测**（中，用户暂不紧急）——System Ops 分区
6. **G6 APK Helper**——暂缓，需求不明

每项独立可交付，可按需挑顺序。G2 和 G5 可并行（不互相依赖）。G1 依赖 Device Overview 已有结构（已就绪）。

## 5. 红线检查

- **`:core` no UI deps**：G2 的 `runShellCmd` 是纯 shell 执行，无 UI。G1 的 `inputText` 同理。✅
- **UI 不直接碰 adb**：G2 的 SystemInfoViewModel 走 `DeviceRepository.runShellCmd` 委托，不越层。✅
- **TDD on `:core`**：G2 的 `runShellCmd` 需补 `CommandRunnerTest`（fake runner 断言命令传递 + 失败抛异常）。G1 的 `inputText` 同理。G3 的 `adbVersion` 需补测试。
- **i18n**：G2 命令名/分组标题全走 Strings.t。G1 文本输入标签走 Strings.t。
- **fixture 真实录制**：G2 的命令输出若要做 Parser（当前规划不做 Parser，原样返回文本），则需 fixture；不做 Parser 则无此约束。
- **Conventional Commits**：`feat(core):` / `feat(desktop):` / `refactor(desktop):`。

## 6. 待决问题

- G6 APK Helper 的具体功能需求不明（EasyADB README 未填），暂不开。
- G4 monkey 压测用户表示"暂不紧急"，本轮规划但不实现。
- G2 系统信息页是否需要"自定义命令"输入框（让用户自己输 adb 命令跑）？EasyADB 没有，我们规划也不加（保持 GUI 化定位，自定义命令用 Shell 页）。

## 7. EasyADB 设计取舍（取精华去糟粕）

参照 EasyADB 截图（2026-08-21）+ `config/*.xml` 配置，逐项判定借鉴与否：

### 7.1 取（精华）

| EasyADB 设计 | 借鉴方式 | 落地到 |
|---|---|---|
| **系统信息命令分组树**（Packages/Display/System/Network/About 五组，每组若干命令） | G2 系统信息页左侧用分组列表，右侧文本输出区。分组结构直接抄它的逻辑（按 adb 命令域分组），命令清单精简（去掉冗余/不实用的） | G2 |
| **命令 = 点一下即执行 + 输出在旁**的交互模式 | 系统信息页点命令 → 右侧立即显示 stdout，busy 转圈，完成可复制/导出。不弹窗、不跳页 | G2 |
| **需选包的命令**（`pm path {pkg}` / `dumpsys meminfo {pkg}` / `dumpsys package {pkg}`）顶部有包名选择 | 系统信息页顶部加一个包名下拉（复用 `listPackages`），需选包的命令在选中包前禁用 | G2 |
| **按键模拟网格**（返回/方向/确认/音量/菜单/电源 9 键） | 我们 Device Overview Remote 区已有 D-pad + Back/Home/Menu + 音量 + 自定义，**已超过它**，不抄 | — |
| **应用操作图标网格**（卸载/安装/停止/启动/重启/清数据/文本输入/广播/Provider） | 我们 App Console 已有列表+操作面板模式，更清晰。**不抄网格**（图标网格视觉杂、文字标签省了不好认），但**借鉴"一屏可见所有操作"的紧凑性**——App Console 下半操作面板保持横向按钮排列 | — |

### 7.2 弃（糟粕）

| EasyADB 设计 | 不借鉴原因 |
|---|---|
| **三栏布局**（左设备+操作 / 中输出 / 右命令树） | 三栏在窄屏拥挤；我们用导航页切换（Device Overview/App Console/系统信息），每页专注一类，更清晰 |
| **XML 配置命令表**（`cmdConfig.xml` 把命令配成可点列表） | 灵活但要求用户懂 adb；我们定位是 GUI 化（每个功能有专属 UI 控件），自定义命令走 Shell 页。**不做成可配置命令表** |
| **图标网格操作模板**（`function_templates.xml` 9 宫格图标） | 图标辨识度低、无文字标签不好认；我们用文字按钮更明确 |
| **README 全模板未填** | 不学这种文档风格 |
| **`screenrecord` 命令行录制** | 我们 scrcpy `--record` 已覆盖且体验更好（scrcpy 录制时还能看画面） |
| **adb help 命令** | 输出极长且对 GUI 用户无用（要看帮助去命令行 `adb help`），不进系统信息页 |

### 7.3 G2 系统信息页命令清单（精简后）

EasyADB 的 `cmdConfig.xml` 列了 ~20 条，精简掉冗余/不实用/我们已有的，保留这些：

| 分组 | 命令 | 说明 | 需选包 |
|---|---|---|---|
| 应用 | `pm path {pkg}` | 应用安装路径 | ✅ |
| 应用 | `dumpsys package {pkg} \| grep -E "versionName\|versionCode"` | 应用版本 | ✅ |
| 应用 | `pm list features` | 硬件特性 | |
| 应用 | `pm list libraries` | 系统库 | |
| 显示 | `wm density` | 屏幕密度 | |
| 显示 | `dumpsys window \| grep mCurrentFocus` | 当前焦点窗口 | |
| 系统 | `getprop` | 全部系统属性（原始全量；DeviceInfo 有解析版） | |
| 系统 | `dumpsys diskstats` | 磁盘状态 | |
| 系统 | `df -h` | 磁盘可用 | |
| 系统 | `dumpsys meminfo` | 全部内存 | |
| 系统 | `dumpsys meminfo {pkg}` | 指定应用内存 | ✅ |
| 系统 | `top -n 1` | Top 进程 | |
| 系统 | `cat /proc/cpuinfo` | CPU 信息 | |
| 系统 | `uptime` | 运行时长 | |
| 网络 | `ifconfig` | 网络接口 | |
| 网络 | `cat /sys/class/net/eth0/address` | MAC 地址（部分设备无 eth0，fallback wlan0） | |

去掉的：`pm list packages -f`（我们已有三方包列表）、`dumpsys cpuinfo`（`top -n 1` 更实用）、`cat /etc/hosts`（低频）、`adb version`/`adb help`（移到 Settings / 不做）。

命令清单可后续按需增删——G2 页面设计成"命令列表是数据驱动的"（VM 持有一个 `List<InfoCommand>`，UI 渲染它），加命令只改数据不改 UI。


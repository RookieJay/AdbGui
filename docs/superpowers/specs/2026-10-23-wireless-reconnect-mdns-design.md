# 无线设备自动回连与设备去重 — 设计文档

- **日期**：2026-10-23
- **状态**：设计已与用户确认（四层结构 + 自建 mDNS），待用户审阅
- **背景动因**：手机无线调试端口随机变化，用户被迫反复"重新配对"（实际并不需要），且历史列表出现同 IP 多条重复记录

## 1. 问题

1. **端口漂移**：Android 11+ 无线调试的连接端口在「重开无线调试开关 / 手机重启 / 换 WiFi」时随机变化。端口不变的条件：开关保持打开。现状：历史记录存了旧 `ip:port`，重连失败，用户误以为要重新配对。
2. **列表重复**：历史以 adb serial（无线设备即 `ip:port`）为唯一键，端口一变即新增一条"新设备"，同 IP 越积越多。
3. **mDNS 不可依赖**（见 §2 实证），单纯依赖 adb 的 mDNS 发现无法交付"全自动回连"。

## 2. 实证结论（2026-10-23 现场实验，用户网络 + OnePlus PJD110 / ColorOS）

| 实验 | 结果 |
|---|---|
| 无配对直连 `adb connect ip:新端口` | ✅ 成功——配对密钥长期有效，端口变了**不需要重新配对** |
| `adb connect` 后 `getprop ro.serialno` | ✅ 得到真实 serial（`af199bb4`），可作设备唯一 ID |
| 手机 mDNS 广播（开关瞬间 / 页面停留 / 活动连接 / 开关重开全程 110s 监听） | ❌ 0 个包——该机型不发 `_adb-tls-connect._tcp` |
| 手机 5353 单播查询（直发 UDP） | ❌ 无响应——手机上无 mDNS 响应者 |
| 网络 mDNS 组播本身 | ✅ 正常（收到 TCL TV `adb-unidentified._adb._tcp` 持续广播） |
| `adb mdns services`（adb 自带发现） | ❌ **空**——TV 在广播却收不到，adb 自身 mDNS 接收在本机是坏的 |
| 端口变化规律 | 开关重开必换端口（38897 → 37751）；开关保持打开端口不变 |
| 用户观察 AS"自动回连" | 间歇性成功（手机偶尔广播时），与 adb server 共享连接池，非独立机制 |

**设计含义**：(a) 不能依赖 adb 的 `mdns services`；(b) 自建 mDNS 探测可行且更可靠（原始 socket 实测能收到广播）；(c) 必须有不依赖 mDNS 的回连层；(d) 去重必须靠真实 serial，不能靠 IP 或机型。

## 3. 设计总览：四层回连机制

```
Layer 1  保存端点自动重试      —— 端口没变（最常见：手机唤醒/回网）→ 自动回连
Layer 2  自建 mDNS 探测        —— 端口变了且设备广播（标准设备）→ 自动发现新端口并连接
Layer 3  端口失效内联更新      —— 端口变了且无广播（用户的 OnePlus）→ 看手机屏输 5 位端口一键重连
Layer 4  真实 serial 去重      —— 所有层共用的设备身份与历史合并
```

每一层独立有效，层层兜底；`Layer 4` 是数据模型层，贯穿其余三层。

## 4. `:core` 组件设计

### 4.1 `MdnsScanner`（新，包 `com.adbgui.core.mdns`）

纯 JVM UDP 组播探测，**不依赖 adb 的 mdns 后端**。

- **查询**：每 10s 向 `224.0.0.251:5353` 发 PTR 查询 `_adb-tls-connect._tcp.local` 与 `_adb._tcp.local`（前者 Android 11+ 无线调试，后者老式 TCP adb——TCL TV 即此类）。
- **监听**：绑定 `0.0.0.0:5353`（SO_REUSEADDR，已实证可与 adb server 共存）持续收组播；绑定失败则退化为临时端口 + 单播 legacy 查询（应答方会单答回源端口）。
- **接口枚举**：`NetworkInterface` 枚举 up + multicast 的 IPv4 接口，逐个 join 组播组（多网卡环境必须，用户机器就有 3 个物理/虚拟网卡）。
- **输出**：`StateFlow<List<MdnsService>>`，`MdnsService(serviceName, type, host, port, txt: Map<String,String>)`。`serviceName` 形如 `adb-<serial>-XXXX`（标准手机含真实 serial，可用于 Layer 4 身份匹配）或 `adb-unidentified`（老式，无 serial，按 IP 匹配）。
- **可测性**：socket 交互藏 `MdnsSocket` 接口后（`send`/`receive`），测试注入 Fake 喂录制报文；报文解析是纯函数（见 4.2）。
- 协程：注入 scope（长生命周期，同 `DeviceTracker`）+ 注入 `io: CoroutineDispatcher`（规范 §技术债 1）。

### 4.2 `MdnsResponseParser`（新，纯函数 object）

DNS-SD 报文（`ByteArray`）→ `List<MdnsService>`：解析 header、压缩域名指针、PTR/SRV/A/TXT 记录。 malformed 报文跳过并保留原文到日志（不静默吞，遵循解析约定）。

### 4.3 `CommandRunner` / `DeviceRepository`

- 无新增 adb 命令；`connect`/`disconnect`/`runShellCmd` 均已存在。
- `DeviceRepository.connectWireless(ip, port)`：成功后新增 **deviceId 学习**——`runShellCmd(serial, "getprop ro.serialno")` 拿真实 serial，写入历史并触发合并（4.4）。查询失败（unauthorized 等）不阻塞连接成功路径，deviceId 留空，下次再学。
- 对 adb server 自行连上的未知 `ip:port` 设备（tracker 观察到 online 且不在历史）：复用现有 autoName 的 getprop 懒加载路径顺带学习 deviceId（不额外加命令调用）。
- 新增 `WirelessReconnector`（Layer 1）：对历史无线设备周期重试 `adb connect ip:port`；指数退避 5s→60s 封顶，成功即重置；只重试近 7 天用过（`lastUsedAt`）的条目、上限 10 条，避免历史堆积导致进程风暴。注入 scope 可取消。

### 4.4 `DeviceHistoryStore`：deviceId 与合并规则

schema 变更（`devices.json`，`ignoreUnknownKeys` + 默认值天然向后兼容）：

```
DeviceHistoryEntry {
  ...现有字段...
  + deviceId: String?     // 真实 serial（getprop ro.serialno），设备唯一 ID
}
```

**合并规则**（原子读改写，新增 `mergeOnConnect(learnedDeviceId, serial, ip, port)` 一次完成）：

1. 同 `deviceId` 的多条 → 保留一条：吸收 alias/tag（非空优先）、更新 serial/ip/port/时间戳，其余删除。
2. serial == deviceId 的 USB 条目与无线条目 → 同一设备合并（一台设备插线与无线不再占两条）。
3. **同 IP 无 deviceId 的旧无线条目**（即用户现存重复）：新连接确立 deviceId 后，`wirelessIp` 相同且 serial 形如 `ip:port` 的旧条目视为同一设备的残留，删除并记日志。误删风险接受（同 IP 被不同设备先后占用属罕见；宁可偶尔多显示也不静默错并——若旧条目带不同非空 alias 则保留不动，交给用户手动删）。
4. deviceId 拿不到（unauthorized、老设备无 prop）→ 不合并，仅按现有逻辑更新自身条目。

**`DeviceRepository` 视图合并**：`recompute` 分组键从 serial 改为 `deviceId ?: serial`；同组多条历史（迁移期的过渡态）折叠为一条显示（取最新），存储层合并完成后自然消解。

### 4.5 mDNS 服务 → 连接决策（`DeviceRepository`）

- 扫描到服务：`connect(ip, port)`。已配对直接成功；未配对 connect 失败（`PORT_STALE` 之外的原因）→ 进"发现的设备"列表并标注"需配对"。
- 服务名可提取 serial 时，先与历史 deviceId 比对：已知设备 → 更新端口重连；未知 → 仅入发现列表不自动连。
- 老式 `_adb._tcp` 服务（TV）：默认只入发现列表/更新已知同 IP 条目，不自动连（TV 常驻，自动连打扰少但保持一致即可——见 §8 边界：自动连仅限 tls-connect 类）。

## 5. `:desktop` UI 变更

1. **设备行内联端口更新**（Layer 3）：无线设备重连失败且 `ConnectFailureReason.PORT_STALE` → 行内提示"端口已变，请查看手机无线调试页"，IP 预填只读，5 位端口输入框 + 重连按钮。无模态弹窗（错误处理约定）。
2. **ConnectDialog**：输入框支持整串粘贴 `10.0.5.221:37751` 自动拆分；新增"发现的设备"区（mDNS StateFlow 渲染）：显示 `ip:port`（+ 已知设备的别名），一键连接/配对跳转。
3. **设置**：新增 `autoReconnect: Boolean`（默认开）控制 Layer 1 重试循环；`SettingsStore` 照常持久化。
4. ViewModel 断言 `StateFlow` 状态机（现状约定），Compose 快照不强制。

## 6. 错误处理与日志

- mDNS 报文解析失败：DEBUG 记录原始字节摘要，跳过该包。
- 重连/mDNS 连接失败：内联状态，不打断；连续失败按 §4.3 退避。
- 合并删除历史条目：INFO 级记录被删条目（可审计）。
- 不记敏感数据；mDNS TXT 内容只记键名。

## 7. 测试策略（`:core` 一律 TDD）

- `MdnsResponseParser`：fixture 喂真实抓包字节（见下），覆盖 PTR/SRV/A/TXT、压缩指针、malformed。
- `MdnsScanner`：Fake `MdnsSocket` + `Dispatchers.Unconfined`，断言查询周期、服务去重、StateFlow 输出。
- `DeviceHistoryStore.mergeOnConnect`：各合并分支（同 deviceId / USB+无线 / 同 IP 残留 / 保留非空 alias）。
- `WirelessReconnector`：Fake runner 下断言退避序列、成功重置、条目过滤（7 天/上限 10）。
- `DeviceRepository`：连接成功学习 deviceId、未知 online 设备懒学习、视图按 deviceId 折叠。
- **fixture 采集计划**：
  - ✅ 现在就能录：TCL TV 的 `adb-unidentified._adb._tcp` 真实报文（SRV/A/TXT 齐全，来源注明 TCL Android 6 + 抓包命令/工具 + 日期）。
  - ⚠️ **待办**：真实 `_adb-tls-connect._tcp` 报文——用户 OnePlus 不广播，需遇广播设备（或任何标准手机开无线调试）时录制，文件头注明来源。与 PairResultParser fixture 待办同性质。
  - 手写合成报文仅限**单元测试的边界用例**（malformed、超长字段），不充当正常路径 fixture。

## 8. 范围边界（不做）

- `adb tcpip 5555` 固定端口模式（显式排除，用户确认）
- 配对码自动化 / pairing 服务的 mDNS 集成（`_adb-tls-pairing._tcp` 只解析不联动）
- 老式 `_adb._tcp` 设备的自动连接（只展示/更新端口）
- 局域网端口扫描探测

## 9. 实现顺序建议（供 writing-plans 展开）

1. `MdnsResponseParser` + TV fixture（纯函数，先落地）
2. `MdnsScanner`（socket 抽象 + 循环）
3. deviceId schema + `mergeOnConnect`（含迁移）
4. `connectWireless` deviceId 学习 + 视图折叠
5. `WirelessReconnector`
6. `MdnsScanner` → 连接决策接入
7. UI（端口更新内联、ConnectDialog 发现区、设置开关）

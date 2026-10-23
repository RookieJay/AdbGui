# 无线自动回连 + 自建 mDNS + deviceId 去重 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 无线设备端口漂移后自动/半自动回连（端点重试 + 自建 mDNS 探测 + 端口失效内联更新），并以真实 serial（deviceId）合并历史设备记录。

**Architecture:** 四层机制（spec §3）。`:core` 新增 `mdns` 包（纯 JVM UDP 组播扫描 + DNS-SD 报文纯函数解析，socket 藏接口便于测试）；`DeviceHistoryStore` 增加 `deviceId` 字段与原子合并；`DeviceRepository` 负责 deviceId 学习、视图按 deviceId 折叠、mDNS→连接决策；`:desktop` 新增 `WirelessReconnector` 装配、ConnectDialog 发现列表、设备行内联端口更新。

**Tech Stack:** Kotlin / kotlinx.coroutines（StateFlow、runTest 虚拟时间）、kotlinx.serialization（devices.json / settings.json）、java.net（desktop/platform 的实际 socket）、Compose Multiplatform（UI）、kotlin.test。

**Spec:** `docs/superpowers/specs/2026-10-23-wireless-reconnect-mdns-design.md`（含实证结论表——为什么这么做，执行者必读 §2、§4）

## Global Constraints

- **`:core` 不 import Compose / java.awt / javax.swing**（`java.net` 允许，仅限 `:desktop/platform` 的 Jvm 实现；`:core` 只见接口）。
- **`:core` TDD**：先写失败测试 → 验证失败 → 最小实现 → 通过 → 提交。测试跑 `./gradlew :core:test --tests "..."`。
- **所有 I/O 注入 `io: CoroutineDispatcher`**，测试传 `Dispatchers.Unconfined`（技术债规范 1）。
- **跨线程可变 `var` 必须 `@Volatile` 或 Mutex 保护**（技术债规范 3）。
- **不留死代码**：未引用的类/参数/占位 launch 一律不加（技术债规范 2）。
- **fixture 必须真实录制**：mDNS 二进制报文录自现场网络（TCL TV 广播，见 Task 1 采集脚本）；合成报文只做单元边界用例。
- **错误内联不弹模态**；解析失败保留原文进 DEBUG 日志，不静默吞。
- **Conventional Commits**；每个 Task 一个提交。
- 命令惯例：Windows 上用 `./gradlew`（Git Bash 路径）或 `gradlew.bat`。
- i18n：新文案同时加 `Strings.kt` 的 zh（约 L94 起）与 en（约 L578 起）两个 map。

---

### Task 1: `MdnsService` 模型 + `MdnsResponseParser` 纯函数（含真实报文 fixture）

**Files:**
- Create: `core/src/main/kotlin/com/adbgui/core/domain/MdnsModels.kt`
- Create: `core/src/main/kotlin/com/adbgui/core/mdns/MdnsResponseParser.kt`
- Create: `core/src/test/kotlin/com/adbgui/core/mdns/DnsPacketBuilder.kt`（测试构造器）
- Create: `core/src/test/kotlin/com/adbgui/core/mdns/MdnsResponseParserTest.kt`
- Create: `core/src/test/resources/fixtures/mdns/mdns_adb_unidentified.bin`（真实录制，见 Step 0）
- Create: `core/src/test/resources/fixtures/mdns/mdns_adb_unidentified.txt`（来源说明）

**Interfaces:**
- Produces（后续 Task 依赖的精确签名）:
  - `data class MdnsService(instanceName: String, serviceType: String, host: String, port: Int, txt: Map<String, String>)`，计算属性 `val deviceSerial: String?`（标准名 `adb-<serial>-<suffix>` 提取，提取不出为 null）
  - `object MdnsResponseParser { fun parse(packet: ByteArray): List<MdnsService> }`——只返回带 SRV（端口已知）的服务；malformed 输入返回 `emptyList()`

- [ ] **Step 0: 采集真实 mDNS 报文 fixture**

  现场网络里 TCL TV（192.168.88.240，Android 6 adbd）持续广播 `adb-unidentified._adb._tcp`，约每 1-3 分钟一包。运行以下 PowerShell（保存到临时文件执行即可，不入库）等待捕获，**等 3 分钟拿不到即向用户求助，不要手写编造**：

  ```powershell
  # capture-mdns.ps1 — dump raw mDNS packets containing 'adb' to fixture files
  $out = 'D:\StudioProjects\ADBGUI\core\src\test\resources\fixtures\mdns'
  New-Item -ItemType Directory -Force $out | Out-Null
  $sock = New-Object System.Net.Sockets.Socket([System.Net.Sockets.AddressFamily]::InterNetwork, [System.Net.Sockets.SocketType]::Dgram, [System.Net.Sockets.ProtocolType]::Udp)
  $sock.SetSocketOption([System.Net.Sockets.SocketOptionLevel]::Socket, [System.Net.Sockets.SocketOptionName]::ReuseAddress, $true)
  $sock.Bind((New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 5353)))
  $sock.SetSocketOption([System.Net.Sockets.SocketOptionLevel]::IP, [System.Net.Sockets.SocketOptionName]::AddMembership, (New-Object System.Net.Sockets.MulticastOption([System.Net.IPAddress]::Parse('224.0.0.251'), [System.Net.IPAddress]::Parse('10.0.4.136'))))
  $sock.ReceiveTimeout = 180000
  for ($i = 0; $i -lt 5; $i++) {
    $buf = New-Object byte[] 4096
    $ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
    $n = $sock.ReceiveFrom($buf, [ref]$ep)
    $txt = [System.Text.Encoding]::ASCII.GetString($buf, 0, $n)
    if ($txt -match 'adb') {
      $path = Join-Path $out "mdns_adb_unidentified.bin"
      [System.IO.File]::WriteAllBytes($path, $buf[0..($n-1)])
      Write-Host "saved $n bytes from $($ep.Address) to $path"
      break
    }
  }
  $sock.Close()
  ```

  抓到后写来源说明 `mdns_adb_unidentified.txt`（fixture 规范：设备 + 日期 + 采集方式）：

  ```
  来源：TCL Android TV（Android 6，adbd tcpip 模式，IP 192.168.88.240）
  采集：2026-10-XX，PowerShell UDP 5353 组播监听（ReuseAddress 与 adb server 共存）
  内容：adb-unidentified._adb._tcp 服务广播（PTR/SRV/TXT/A 应答）
  ```

  若 192.168.88.240 不在广播（电视关机等），把 join 的接口 IP 换成本机实际 IPv4（`Get-NetIPAddress` 查）再试。

- [ ] **Step 1: 写失败测试（构造器部分先行）**

  `DnsPacketBuilder.kt`（测试专用，构造合法 DNS-SD response 报文；name 重复出现时自动用压缩指针，覆盖指针解析路径）：

  ```kotlin
  package com.adbgui.core.mdns

  /** Test-only builder for DNS-SD response packets. Repeated names are encoded as
   *  compression pointers (0xC0) so the parser's pointer path gets coverage. */
  object DnsPacketBuilder {
      sealed class Rec
      data class Ptr(val name: String, val target: String) : Rec()
      data class Srv(val name: String, val port: Int, val target: String) : Rec()
      data class Txt(val name: String, val pairs: Map<String, String>) : Rec()
      data class A(val name: String, val address: String) : Rec()

      fun response(records: List<Rec>): ByteArray {
          val body = ByteWriter()
          val nameOffsets = mutableMapOf<String, Int>()
          val recs = ArrayList<Pair<Int /*type*/, ByteArray>>()
          for (rec in records) {
              when (rec) {
                  is Ptr -> {
                      val w = ByteWriter()
                      w.name(rec.name, nameOffsets)
                      w.u16(12)              // PTR
                      w.u16(1)               // class IN
                      w.u32(120)             // ttl
                      val rd = ByteWriter(); rd.name(rec.target, nameOffsets)
                      w.u16(rd.size); w.bytes(rd)
                      recs += 12 to w
                  }
                  is Srv -> {
                      val w = ByteWriter()
                      w.name(rec.name, nameOffsets)
                      w.u16(33)              // SRV
                      w.u16(1); w.u32(120)
                      val rd = ByteWriter()
                      rd.u16(0); rd.u16(0)   // priority, weight
                      rd.u16(rec.port)
                      rd.name(rec.target, nameOffsets)
                      w.u16(rd.size); w.bytes(rd)
                      recs += 33 to w
                  }
                  is Txt -> {
                      val w = ByteWriter()
                      w.name(rec.name, nameOffsets)
                      w.u16(16)              // TXT
                      w.u16(1); w.u32(120)
                      val rd = ByteWriter()
                      rec.pairs.forEach { (k, v) ->
                          val entry = "$k=$v".toByteArray(Charsets.US_ASCII)
                          rd.u8(entry.size); rd.bytes(entry)
                      }
                      w.u16(rd.size); w.bytes(rd)
                      recs += 16 to w
                  }
                  is A -> {
                      val w = ByteWriter()
                      w.name(rec.name, nameOffsets)
                      w.u16(1)               // A
                      w.u16(1); w.u32(120)
                      val rd = ByteWriter()
                      rec.address.split('.').forEach { rd.u8(it.toInt()) }
                      w.u16(4); w.bytes(rd)
                      recs += 1 to w
                  }
              }
          }
          val head = ByteWriter()
          head.u16(0)                        // id
          head.u16(0x8400)                   // QR=1 response
          head.u16(0)                        // QD
          head.u16(recs.size)                // AN
          head.u16(0); head.u16(0)
          return head.toByteArray() + recs.flatMap { listOf(it.first) }.let { ByteArray(0) }
              .let { _ -> recs.fold(ByteArray(0)) { acc, r -> acc + r.second.toByteArray() } }
              .let { head.toByteArray() + it }
      }

      private class ByteWriter {
          private val buf = mutableListOf<Byte>()
          val size get() = buf.size
          fun u8(v: Int) { buf += v.toByte() }
          fun u16(v: Int) { u8(v shr 8); u8(v and 0xFF) }
          fun u32(v: Int) { u16(v shr 16); u16(v and 0xFFFF) }
          fun bytes(w: ByteWriter) { w.buf.forEach { buf += it } }
          fun bytes(b: ByteArray) { b.forEach { buf += it } }
          fun name(n: String, offsets: MutableMap<String, Int>) {
              if (offsets.containsKey(n)) { u16(0xC000 or offsets[n]!!); return }
              offsets[n] = buf.size  // hmm: value must be the offset BEFORE encoding
              n.split('.').forEach { label ->
                  u8(label.length)
                  label.toByteArray(Charsets.US_ASCII).forEach { buf += it }
              }
              u8(0)
          }
          fun toByteArray() = buf.toByteArray()
      }
  }
  ```

  注意上面 `name()` 里 `offsets[n] = buf.size` 必须放在编码**之前**记录（当前写法已对，核对一遍）；`response()` 最后的拼接链写笨了没关系，能产出 `header ++ answers` 字节即可——提交前把这段整理成直白的 `header + answers.reduce { acc, b -> acc + b }` 形式，别留绕圈的代码。

  `MdnsResponseParserTest.kt`：

  ```kotlin
  package com.adbgui.core.mdns

  import kotlin.test.Test
  import kotlin.test.assertEquals
  import kotlin.test.assertNull
  import kotlin.test.assertTrue

  class MdnsResponseParserTest {

      private val inst = "adb-af199bb4-Q9dhj._adb-tls-connect._tcp.local"
      private val target = "Android-13.local"

      private fun phonePacket(port: Int = 37751) = DnsPacketBuilder.response(
          listOf(
              DnsPacketBuilder.Ptr("_adb-tls-connect._tcp.local", inst),
              DnsPacketBuilder.Srv(inst, port, target),
              DnsPacketBuilder.Txt(inst, mapOf("ls" to "1")),
              DnsPacketBuilder.A(target, "10.0.5.221"),
          )
      )

      @Test
      fun parses_srv_port_a_host_and_txt() {
          val services = MdnsResponseParser.parse(phonePacket())
          assertEquals(1, services.size)
          val s = services[0]
          assertEquals("adb-af199bb4-Q9dhj", s.instanceName)
          assertEquals("_adb-tls-connect._tcp", s.serviceType)
          assertEquals("10.0.5.221", s.host)
          assertEquals(37751, s.port)
          assertEquals(mapOf("ls" to "1"), s.txt)
      }

      @Test
      fun deviceSerial_extracts_serial_from_standard_instance_name() {
          assertEquals("af199bb4", MdnsService("adb-af199bb4-Q9dhj", "_adb-tls-connect._tcp", "h", 1, emptyMap()).deviceSerial)
          assertNull(MdnsService("adb-unidentified", "_adb._tcp", "h", 1, emptyMap()).deviceSerial) // 单 '-'，无 serial
      }

      @Test
      fun packet_without_srv_is_skipped() {
          val pkt = DnsPacketBuilder.response(listOf(DnsPacketBuilder.Ptr("_adb._tcp.local", "adb-unidentified._adb._tcp.local")))
          assertTrue(MdnsResponseParser.parse(pkt).isEmpty())
      }

      @Test
      fun queries_and_malformed_input_return_empty() {
          assertTrue(MdnsResponseParser.parse(ByteArray(0)).isEmpty())
          assertTrue(MdnsResponseParser.parse(ByteArray(20)).isEmpty()) // 非法长度
          val query = DnsPacketBuilder.ByteWriter() // 若 ByteWriter 私有，改用直接构造 12 字节 header
      }

      @Test
      fun real_tv_fixture_parses_structurally() {
          val bytes = java.nio.file.Files.readAllBytes(
              java.nio.file.Path.of("src/test/resources/fixtures/mdns/mdns_adb_unidentified.bin"))
          // 先用 dump（Step 3 的临时代码）确认真实字段后，把下面断言改成实测值。
          // 结构性底线：解析出恰好 1 个 _adb._tcp 服务，端口 1..65535，host 非空或为空皆按实测填写。
          val services = MdnsResponseParser.parse(bytes)
          assertEquals(1, services.size)
          assertEquals("_adb._tcp", services[0].serviceType)
          assertTrue(services[0].instanceName.contains("adb"))
      }
  }
  ```

  （`queries_and_malformed_input_return_empty` 里那行 ByteWriter 引用若编译不过，直接手写 12 字节 query header `byteArrayOf(0,0,0,0,0,1,0,0,0,0,0,0)` 加一个 question name 字节。）

- [ ] **Step 2: 跑测试验证失败**

  Run: `./gradlew :core:test --tests "com.adbgui.core.mdns.MdnsResponseParserTest"`
  Expected: 编译失败（`MdnsService` / `MdnsResponseParser` 不存在）

- [ ] **Step 3: 最小实现（含真实 fixture 字段确认）**

  `MdnsModels.kt`：

  ```kotlin
  package com.adbgui.core.domain

  /** 一个 mDNS 发现的 adb 服务（spec 2026-10-23 §4.1/§4.2）。host/port 来自 SRV+A。 */
  data class MdnsService(
      /** 实例名，如 "adb-af199bb4-Q9dhj"（标准无线调试，含 serial）或 "adb-unidentified"（老式 TCP adb）。 */
      val instanceName: String,
      /** 服务类型："_adb-tls-connect._tcp"（Android 11+ 无线调试）或 "_adb._tcp"（老式）。 */
      val serviceType: String,
      val host: String,
      val port: Int,
      val txt: Map<String, String>,
  ) {
      /** 标准实例名 "adb-<serial>-<suffix>" 里的真实 serial；提取不出返回 null。 */
      val deviceSerial: String? get() =
          if (instanceName.startsWith("adb-") && instanceName.count { it == '-' } >= 2)
              instanceName.removePrefix("adb-").substringBeforeLast('-')
          else null
  }
  ```

  `MdnsResponseParser.kt`：

  ```kotlin
  package com.adbgui.core.mdns

  import com.adbgui.core.domain.MdnsService

  /** 解析 mDNS/DNS-SD 响应报文（RFC 6762）。纯函数、无状态：malformed 输入返回 emptyList()，
   *  调用方负责 DEBUG 级记录原文（解析约定：不静默吞，由调用方兜底）。 */
  object MdnsResponseParser {
      private const val TYPE_A = 1
      private const val TYPE_PTR = 12
      private const val TYPE_TXT = 16
      private const val TYPE_SRV = 33

      fun parse(packet: ByteArray): List<MdnsService> {
          if (packet.size < 12) return emptyList()
          if (packet[2].toInt() and 0x80 == 0) return emptyList()          // 不是 response
          val qd = u16(packet, 4)
          val an = u16(packet, 6)
          val r = Reader(packet)
          r.pos = 12
          repeat(qd) { r.readName(); r.skip(4) }                            // type + class

          val instances = mutableListOf<String>()        // PTR rdata: "<instance>.<type>.local"
          val srvs = mutableMapOf<String, Pair<Int, String>>()   // instanceFqdn -> (port, target)
          val txts = mutableMapOf<String, Map<String, String>>()
          val addrs = mutableMapOf<String, String>()     // name -> ipv4

          repeat(an) {
              val name = r.readName()
              val type = u16(packet, r.pos); r.skip(2)
              r.skip(2); r.skip(4)                                          // class, ttl
              val rdlen = u16(packet, r.pos); r.pos += 2
              val rdataStart = r.pos
              when (type) {
                  TYPE_PTR -> instances += r.readName()
                  TYPE_SRV -> {
                      r.skip(4)                                             // priority + weight
                      val port = u16(packet, r.pos); r.skip(2)
                      srvs[name] = port to r.readName()
                  }
                  TYPE_TXT -> {
                      val pairs = mutableMapOf<String, String>()
                      var p = r.pos
                      val end = rdataStart + rdlen
                      while (p < end) {
                          val len = packet[p].toInt() and 0xFF
                          if (len == 0 || p + 1 + len > end) break
                          val entry = String(packet, p + 1, len, Charsets.US_ASCII)
                          val k = entry.substringBefore('=')
                          if (entry.contains('=')) pairs[k] = entry.substringAfter('=')
                          else pairs[k] = ""                                // DNS-SD flag 形式
                          p += 1 + len
                      }
                      txts[name] = pairs
                  }
                  TYPE_A -> if (rdlen == 4) {
                      addrs[name] = "${u8(packet, rdataStart)}.${u8(packet, rdataStart + 1)}.${u8(packet, rdataStart + 2)}.${u8(packet, rdataStart + 3)}"
                  }
              }
              r.pos = rdataStart + rdlen
          }

          return instances.mapNotNull { fqdn ->
              val withoutLocal = fqdn.removeSuffix(".local")
              val inst = withoutLocal.substringBefore('.')
              val type = withoutLocal.substringAfter('.', "")
              if (type.isEmpty() || !type.startsWith('_')) return@mapNotNull null
              val srv = srvs[fqdn] ?: return@mapNotNull null                // 无 SRV = 无端口，跳过
              val host = addrs[srv.second].takeIf { !it.isNullOrEmpty() } ?: addrs[fqdn] ?: ""
              MdnsService(inst, type, host, srv.first, txts[fqdn] ?: emptyMap())
          }
      }

      private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
      private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

      /** 域名读取，支持 0xC0 压缩指针与防环。读取完，pos 停在名字（含指针）之后的下一字节。 */
      private class Reader(val b: ByteArray) {
          var pos = 0
          fun readName(): String {
              val labels = mutableListOf<String>()
              var p = pos
              var next = -1
              var jumps = 0
              loop@ while (p < b.size) {
                  val len = b[p].toInt() and 0xFF
                  when {
                      len == 0 -> { p++; break@loop }
                      len and 0xC0 == 0xC0 -> {
                          if (p + 1 >= b.size) break@loop
                          if (next < 0) next = p + 2
                          p = ((len and 0x3F) shl 8) or (b[p + 1].toInt() and 0xFF)
                          if (++jumps > 32) break@loop                        // 环保护
                      }
                      else -> {
                          if (p + 1 + len > b.size) break@loop
                          labels += String(b, p + 1, len, Charsets.US_ASCII)
                          p += 1 + len
                      }
                  }
              }
              pos = if (next >= 0) next else p
              return labels.joinToString(".")
          }
          fun skip(n: Int) { pos += n }
      }
  }
  ```

  然后处理真实 fixture 断言：临时加一个 `@Test fun dump() { ... print parse(bytes) ... }` 跑一遍，看 TV 报文解析出的实际 `instanceName`/`host`/`port`/`txt`，把 `real_tv_fixture_parses_structurally` 的断言改成实测值（若 TV 报文确实无 SRV/端口导致 `parse` 为空，则该测试改为断言 `emptyList()` 并在测试注释里写明"TV 报文无 SRV，SRV 路径由构造器测试覆盖"，spec §7 已备案这种情况）。

- [ ] **Step 4: 跑测试验证通过**

  Run: `./gradlew :core:test --tests "com.adbgui.core.mdns.MdnsResponseParserTest"`
  Expected: PASS（含真实 fixture 测试）

- [ ] **Step 5: Commit**

  ```bash
  git add core/src/main/kotlin/com/adbgui/core/domain/MdnsModels.kt core/src/main/kotlin/com/adbgui/core/mdns/MdnsResponseParser.kt core/src/test/kotlin/com/adbgui/core/mdns/ core/src/test/resources/fixtures/mdns/
  git commit -m "feat(core): MdnsResponseParser + real TV-packet fixture for DNS-SD parsing"
  ```

---

### Task 2: `MdnsSocket` 接口 + `JvmMdnsSocket`（desktop/platform）

**Files:**
- Create: `core/src/main/kotlin/com/adbgui/core/mdns/MdnsSocket.kt`
- Create: `core/src/main/kotlin/com/adbgui/core/mdns/FakeMdnsSocket.kt`（core main，仿 `FakeAdbProcessRunner` 供测试）
- Create: `desktop/src/main/kotlin/com/adbgui/desktop/platform/JvmMdnsSocket.kt`
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/platform/JvmMdnsSocketSmokeTest.kt`（仿 `JvmAdbProcessRunnerSmokeTest` 的轻量冒烟）

**Interfaces:**
- Produces:
  - `interface MdnsSocket { suspend fun sendQuery(serviceType: String); suspend fun receive(timeoutMs: Int): ByteArray?; fun close() }` —— `sendQuery("<type>")` 发 PTR 查询 `<type>.local`；`receive` 超时返回 null
  - `class FakeMdnsSocket : MdnsSocket`——测试注入待收报文队列；`val sentQueries: List<String>`；`fun emit(packet: ByteArray)`；`fun receiveWillBlock()`

- [ ] **Step 1: 写失败测试**

  FakeMdnsSocket 与接口同文件放 core，其行为由 Task 3 的 scanner 测试直接消费；本 Task 冒烟测试只验证 Jvm 实现可开可关：

  ```kotlin
  package com.adbgui.desktop.platform

  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.runBlocking
  import kotlin.test.Test

  class JvmMdnsSocketSmokeTest {
      @Test
      fun open_receive_timeout_and_close_roundtrip() = runBlocking {
          val s = JvmMdnsSocket()
          try {
              // 无网络依赖的断言：5353 绑定成功（与 adb server 共存靠 SO_REUSEADDR）+ 超时返回 null
              val pkt = s.receive(200)
              kotlin.test.assertNull(pkt)
          } finally { s.close() }
      }
  }
  ```

  Run: `./gradlew :desktop:test --tests "com.adbgui.desktop.platform.JvmMdnsSocketSmokeTest"`
  Expected: 编译失败（类不存在）

- [ ] **Step 2: 实现**

  `MdnsSocket.kt` + `FakeMdnsSocket.kt`：

  ```kotlin
  package com.adbgui.core.mdns

  /** mDNS UDP socket 的可测抽象（spec §4.1）。实现负责：绑定 5353（SO_REUSEADDR 与 adb server
   *  共存，2026-10-23 实测可行）、在所有 up+multicast IPv4 接口 join 组播组、按接口发送查询。 */
  interface MdnsSocket {
      /** 发一条 PTR 查询 "<serviceType>.local" 到 224.0.0.251:5353。 */
      suspend fun sendQuery(serviceType: String)
      /** 收一个包，超时返回 null。socket 由实现保证已 join 组播组。 */
      suspend fun receive(timeoutMs: Int): ByteArray?
      fun close()
  }

  /** 测试用：脚本化注入待收报文。 */
  class FakeMdnsSocket : MdnsSocket {
      val sentQueries = mutableListOf<String>()
      private val incoming = ArrayDeque<ByteArray>()
      @Volatile var closed = false
      override suspend fun sendQuery(serviceType: String) { sentQueries += serviceType }
      override suspend fun receive(timeoutMs: Int): ByteArray? = if (incoming.isEmpty()) null else incoming.removeFirst()
      fun emit(packet: ByteArray) { incoming += packet }
      override fun close() { closed = true }
  }
  ```

  `JvmMdnsSocket.kt`：

  ```kotlin
  package com.adbgui.desktop.platform

  import com.adbgui.core.mdns.MdnsSocket
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.runInterruptible
  import kotlinx.coroutines.withContext
  import java.net.DatagramPacket
  import java.net.InetAddress
  import java.net.InetSocketAddress
  import java.net.MulticastSocket
  import java.net.NetworkInterface

  /** 真实 mDNS socket：绑 0.0.0.0:5353（ReuseAddress 与 adb server 共存），逐接口 join 224.0.0.251，
   *  查询按接口逐个发。绑定失败抛 IllegalStateException（调用方决定降级为不扫描）。 */
  class JvmMdnsSocket : MdnsSocket {
      private val group = InetAddress.getByName("224.0.0.251")
      private val socket: MulticastSocket = MulticastSocket(null).apply {
          reuseAddress = true
          bind(InetSocketAddress(5353))
      }
      private val interfaces: List<NetworkInterface> =
          NetworkInterface.getNetworkInterfaces().toList()
              .filter { it.isUp && it.supportsMulticast() && !it.isLoopback }
              .filter { nif -> nif.inetAddresses.toList().any { it is java.net.Inet4Address && !it.isLoopbackAddress } }

      init {
          interfaces.forEach { nif ->
              nif.inetAddresses.filterIsInstance<java.net.Inet4Address>().filter { !it.isLoopbackAddress }.forEach { addr ->
                  socket.joinGroup(java.net.InetSocketAddress(group, 5353), java.net.NetworkInterface.getByInetAddress(addr))
              }
          }
      }

      override suspend fun sendQuery(serviceType: String) = withContext(Dispatchers.IO) {
          val q = queryPacket(serviceType)
          interfaces.forEach { nif ->
              runCatching {
                  socket.networkInterface = nif
                  socket.send(DatagramPacket(q, q.size, group, 5353))
              } // 单接口失败（虚拟网卡等）不影响其它接口
          }
      }

      override suspend fun receive(timeoutMs: Int): ByteArray? = withContext(Dispatchers.IO) {
          runInterruptible {
              socket.soTimeout = timeoutMs
              val buf = ByteArray(4096)
              val p = DatagramPacket(buf, buf.size)
              try {
                  socket.receive(p)
                  buf.copyOf(p.length)
              } catch (e: java.net.SocketTimeoutException) { null }
          }
      }

      override fun close() { socket.close() }

      private fun queryPacket(serviceType: String): ByteArray {
          val name = serviceType.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) +
              "local".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
          val q = mutableListOf<Byte>()
          q += listOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0).map { it.toByte() }
          name.forEach { q += it }
          q += listOf(0, 12, 0, 1).map { it.toByte() }      // QTYPE=PTR, QCLASS=IN
          return q.toByteArray()
      }
  }
  ```

  （`Inet4Address` 的 import 展开成全限定是避免与 `InetAddress` 混淆，落码时整理干净。）

- [ ] **Step 3: 跑测试验证通过**

  Run: `./gradlew :desktop:test --tests "com.adbgui.desktop.platform.JvmMdnsSocketSmokeTest"`
  Expected: PASS。若 5353 绑定失败（adb server 重启后抢占）：确认 `reuseAddress` 在 bind **之前**设置（顺序错了会绑定失败——这是最可能的坑）。

- [ ] **Step 4: Commit**

  ```bash
  git add core/src/main/kotlin/com/adbgui/core/mdns/MdnsSocket.kt core/src/main/kotlin/com/adbgui/core/mdns/FakeMdnsSocket.kt desktop/src/main/kotlin/com/adbgui/desktop/platform/JvmMdnsSocket.kt desktop/src/test/kotlin/com/adbgui/desktop/platform/JvmMdnsSocketSmokeTest.kt
  git commit -m "feat(core,desktop): MdnsSocket abstraction + JVM multicast impl coexisting with adb server"
  ```

---

### Task 3: `MdnsScanner`（扫描循环 + `StateFlow<List<MdnsService>>`）

**Files:**
- Create: `core/src/main/kotlin/com/adbgui/core/mdns/MdnsScanner.kt`
- Test: `core/src/test/kotlin/com/adbgui/core/mdns/MdnsScannerTest.kt`

**Interfaces:**
- Consumes: `MdnsSocket`（Task 2）、`MdnsResponseParser.parse`（Task 1）、`Logger`
- Produces:
  - `class MdnsScanner(socket: MdnsSocket, logger: Logger, scope: CoroutineScope, io: CoroutineDispatcher = Dispatchers.IO, clock: () -> Long = System::currentTimeMillis)`
  - `val services: StateFlow<List<MdnsService>>`（只含 `port > 0` 的服务；30s 未见即过期移除）
  - `fun start()` / `fun stop()`（幂等，同 `DeviceTracker` 语义）

- [ ] **Step 1: 写失败测试**

  ```kotlin
  package com.adbgui.core.mdns

  import com.adbgui.core.domain.MdnsService
  import com.adbgui.core.log.Logger
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.test.advanceTimeBy
  import kotlinx.coroutines.test.runTest
  import kotlin.test.Test
  import kotlin.test.assertEquals
  import kotlin.test.assertTrue

  class MdnsScannerTest {
      private val logger = object : Logger {
          override fun debug(msg: String) {}
          override fun info(msg: String) {}
          override fun warn(msg: String, t: Throwable?) {}
          override fun error(msg: String, t: Throwable?) {}
      }

      private val phonePacket = DnsPacketBuilder.response(
          listOf(
              DnsPacketBuilder.Ptr("_adb-tls-connect._tcp.local", "adb-af199bb4-Q9dhj._adb-tls-connect._tcp.local"),
              DnsPacketBuilder.Srv("adb-af199bb4-Q9dhj._adb-tls-connect._tcp.local", 37751, "Android-13.local"),
              DnsPacketBuilder.A("Android-13.local", "10.0.5.221"),
          )
      )

      @Test
      fun emits_discovered_service_and_queries_both_types() = runTest {
          val socket = FakeMdnsSocket()
          val scanner = MdnsScanner(socket, logger, backgroundScope, Dispatchers.Unconfined) { currentTime }
          scanner.start()
          advanceTimeBy(1_000)                                  // 触发首轮查询
          assertEquals(listOf("_adb-tls-connect._tcp", "_adb._tcp"), socket.sentQueries.sorted())
          socket.emit(phonePacket)
          advanceTimeBy(1_000)
          assertEquals(1, scanner.services.value.size)
          assertEquals("10.0.5.221", scanner.services.value[0].host)
          scanner.stop()
      }

      @Test
      fun service_expires_after_30s_without_refresh() = runTest {
          val socket = FakeMdnsSocket()
          val scanner = MdnsScanner(socket, logger, backgroundScope, Dispatchers.Unconfined) { currentTime }
          scanner.start()
          advanceTimeBy(1_000)
          socket.emit(phonePacket)
          advanceTimeBy(1_000)
          assertEquals(1, scanner.services.value.size)
          advanceTimeBy(31_000)                                 // 超过 30s 无刷新
          assertEquals(0, scanner.services.value.size)
          scanner.stop()
      }

      @Test
      fun start_is_idempotent_and_stop_stops() = runTest {
          val socket = FakeMdnsSocket()
          val scanner = MdnsScanner(socket, logger, backgroundScope, Dispatchers.Unconfined) { currentTime }
          scanner.start(); scanner.start()
          advanceTimeBy(11_000)                                 // 10s 周期查询两次
          assertEquals(4, socket.sentQueries.size)              // 2 types × 2 rounds
          scanner.stop(); scanner.stop()
          val before = socket.sentQueries.size
          advanceTimeBy(21_000)
          assertEquals(before, socket.sentQueries.size)
      }

      @Test
      fun malformed_packet_is_logged_and_skipped() = runTest {
          val socket = FakeMdnsSocket()
          val scanner = MdnsScanner(socket, logger, backgroundScope, Dispatchers.Unconfined) { currentTime }
          scanner.start()
          advanceTimeBy(1_000)
          socket.emit(ByteArray(3))                             // malformed
          socket.emit(phonePacket)
          advanceTimeBy(1_000)
          assertEquals(1, scanner.services.value.size)
          scanner.stop()
      }
  }
  ```

  （`Logger` 接口的方法签名以 `core/src/main/kotlin/com/adbgui/core/log/Logger.kt` 实际为准——先读文件再对齐。）

- [ ] **Step 2: 验证失败**

  Run: `./gradlew :core:test --tests "com.adbgui.core.mdns.MdnsScannerTest"` → 编译失败

- [ ] **Step 3: 实现**

  ```kotlin
  package com.adbgui.core.mdns

  import com.adbgui.core.domain.MdnsService
  import com.adbgui.core.log.Logger
  import kotlinx.coroutines.CoroutineDispatcher
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.Job
  import kotlinx.coroutines.delay
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.asStateFlow
  import kotlinx.coroutines.isActive
  import kotlinx.coroutines.launch

  /** 周期 mDNS 探测（spec §4.1）：每 10s 查询 _adb-tls-connect._tcp 与 _adb._tcp，持续收包，
   *  服务 30s 未刷新即过期。独立于 adb 的 mdns 后端（实证它在本机是坏的，spec §2）。 */
  class MdnsScanner(
      private val socket: MdnsSocket,
      private val logger: Logger,
      private val scope: CoroutineScope,
      private val io: CoroutineDispatcher = Dispatchers.IO,
      private val clock: () -> Long = System::currentTimeMillis,
  ) {
      private val _services = MutableStateFlow<List<MdnsService>>(emptyList())
      val services: StateFlow<List<MdnsService>> = _services.asStateFlow()

      private var job: Job? = null

      // 只在 scanner 协程（单一协程）内读写，无需跨线程保护
      private val seen = LinkedHashMap<String, Pair<MdnsService, Long>>() // key: "$instanceName.$serviceType"

      fun start() {
          if (job?.isActive == true) return
          job = scope.launch { runLoop() }
      }

      fun stop() {
          job?.cancel(); job = null
      }

      private suspend fun runLoop() {
          var nextQueryAt = 0L
          while (scope.isActive) {
              val now = clock()
              if (now >= nextQueryAt) {
                  socket.sendQuery(SERVICE_TLS)
                  socket.sendQuery(SERVICE_LEGACY)
                  nextQueryAt = now + QUERY_INTERVAL_MS
              }
              val pkt = socket.receive(RECEIVE_TIMEOUT_MS) ?: continue
              val parsed = MdnsResponseParser.parse(pkt)
              if (parsed.isEmpty() && pkt.size >= 12) {
                  // 解析出空但报文完整：malformed 或无 SRV。保留摘要兜底（不静默吞）。
                  logger.debug("mdns: unparseable packet (${pkt.size} bytes, head=${pkt.take(16).joinToString(",") { (it.toInt() and 0xFF).toString(16) }})")
              }
              parsed.forEach { svc ->
                  seen["$${svc.instanceName}.${svc.serviceType}"] = svc to now
              }
              val alive = seen.filterValues { now - it.second <= EXPIRY_MS }.mapValues { it.value.first }
              if (alive != seen.filterValues { true }.mapValues { it.value.first } || _services.value.toSet() != alive.values.toSet()) {
                  _services.value = alive.values.filter { it.port > 0 }.sortedBy { it.instanceName }
              }
          }
      }

      companion object {
          const val SERVICE_TLS = "_adb-tls-connect._tcp"
          const val SERVICE_LEGACY = "_adb._tcp"
          private const val QUERY_INTERVAL_MS = 10_000L
          private const val RECEIVE_TIMEOUT_MS = 1_000
          private const val EXPIRY_MS = 30_000L
      }
  }
  ```

  说明：diff 判断那行写复杂了会难读——落码时简化成"每次都重算并赋值"也可以（`StateFlow` 会合并相等值，代价是多一次引用赋值，无碍）。唯一硬约束：**过滤 `port > 0` 与 30s 过期**。

  另外 `runTest` 下 `while (scope.isActive)` + `delay` 的推进：receive 返回 null 后立即 continue 会自旋。真实循环里 receive(1000) 是阻塞超时（真实时间）；虚拟时间下 FakeMdnsSocket 立即返回 null → 自旋空转烧 CPU。修正：receive 返回 null 时 `delay(RECEIVE_TIMEOUT_MS)` —— 虚拟时间可推进、真实时间也大致对齐。**落码时必须加这句，否则测试卡死。**

- [ ] **Step 4: 验证通过**

  Run: `./gradlew :core:test --tests "com.adbgui.core.mdns.MdnsScannerTest"` → PASS

- [ ] **Step 5: Commit**

  ```bash
  git add core/src/main/kotlin/com/adbgui/core/mdns/MdnsScanner.kt core/src/test/kotlin/com/adbgui/core/mdns/MdnsScannerTest.kt
  git commit -m "feat(core): MdnsScanner periodic mDNS discovery with expiry"
  ```

---

### Task 4: `DeviceHistoryStore` — `deviceId` 字段 + `mergeOnConnect` 原子合并

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/device/DeviceHistoryStore.kt`
- Test: `core/src/test/kotlin/com/adbgui/core/device/DeviceHistoryStoreTest.kt`（追加）

**Interfaces:**
- Produces:
  - `DeviceHistoryEntry` 新增 `deviceId: String? = null`（JSON 向后兼容：`ignoreUnknownKeys` + 默认值）
  - `suspend fun mergeOnConnect(deviceId: String, serial: String, type: DeviceType, wirelessIp: String?, wirelessPort: Int?): List<DeviceHistoryEntry>`——返回被合并删除的旧条目（供调用方 INFO 记账）。合并规则见 spec §4.4：
    1. `deviceId` 相同的条目合并为一条；
    2. `serial == deviceId` 的 USB 条目并入（同一台设备插线+无线）；
    3. 同 `wirelessIp`、`deviceId == null`、serial 形如 `ip:port` 的旧无线条目并入（清理现存重复），**但 alias 非空且不同于合并结果 alias 的条目保留不动**；
    4. alias/tag 取组内非空值（当前 serial 条目优先），type 有无线则 WIRELESS，`lastConnectedAt = clock()`。

- [ ] **Step 1: 写失败测试（追加到 DeviceHistoryStoreTest）**

  ```kotlin
  @Test
  fun mergeOnConnect_merges_entries_with_same_deviceId_and_keeps_alias() = runTest {
      val store = DeviceHistoryStore(dir(), clock = { 1000L }, io = Dispatchers.Unconfined)
      store.upsert("10.0.5.221:38897", DeviceType.WIRELESS, "10.0.5.221", 38897)
      store.setAlias("10.0.5.221:38897", "我的手机")
      store.upsert("10.0.5.221:37751", DeviceType.WIRELESS, "10.0.5.221", 37751) // 端口漂移产生的重复

      val removed = store.mergeOnConnect("af199bb4", "10.0.5.221:37751", DeviceType.WIRELESS, "10.0.5.221", 37751)

      val entries = store.load()
      assertEquals(1, entries.size)
      assertEquals("10.0.5.221:37751", entries[0].serial)
      assertEquals("af199bb4", entries[0].deviceId)
      assertEquals("我的手机", entries[0].alias)
      assertEquals(37751, entries[0].wirelessPort)
      assertEquals(listOf("10.0.5.221:38897"), removed.map { it.serial })
  }

  @Test
  fun mergeOnConnect_merges_usb_entry_with_same_serial_as_deviceId() = runTest {
      val store = DeviceHistoryStore(dir(), clock = { 1000L }, io = Dispatchers.Unconfined)
      store.upsert("af199bb4", DeviceType.USB, null, null)                     // 插线时 touchLastUsed 产生的 USB 条目
      store.upsert("10.0.5.221:37751", DeviceType.WIRELESS, "10.0.5.221", 37751)

      store.mergeOnConnect("af199bb4", "10.0.5.221:37751", DeviceType.WIRELESS, "10.0.5.221", 37751)

      val entries = store.load()
      assertEquals(1, entries.size)                                            // USB + 无线合一
      assertEquals("10.0.5.221:37751", entries[0].serial)
      assertEquals(DeviceType.WIRELESS, entries[0].type)
      assertEquals("af199bb4", entries[0].deviceId)
  }

  @Test
  fun mergeOnConnect_keeps_stale_same_ip_entry_with_different_alias() = runTest {
      val store = DeviceHistoryStore(dir(), clock = { 1000L }, io = Dispatchers.Unconfined)
      store.upsert("10.0.5.221:38897", DeviceType.WIRELESS, "10.0.5.221", 38897)
      store.setAlias("10.0.5.221:38897", "另一个设备的别名")                    // 带非空别名 → 不自动并
      store.upsert("10.0.5.221:37751", DeviceType.WIRELESS, "10.0.5.221", 37751)

      store.mergeOnConnect("af199bb4", "10.0.5.221:37751", DeviceType.WIRELESS, "10.0.5.221", 37751)

      assertEquals(2, store.load().size)                                       // 别名条目保留
  }

  @Test
  fun mergeOnConnect_creates_entry_when_group_empty() = runTest {
      val store = DeviceHistoryStore(dir(), clock = { 1000L }, io = Dispatchers.Unconfined)
      store.mergeOnConnect("af199bb4", "10.0.5.221:37751", DeviceType.WIRELESS, "10.0.5.221", 37751)
      val e = store.load().single()
      assertEquals("af199bb4", e.deviceId)
      assertEquals(1000L, e.lastConnectedAt)
  }
  ```

- [ ] **Step 2: 验证失败** → Run: `./gradlew :core:test --tests "com.adbgui.core.device.DeviceHistoryStoreTest"` → 编译失败（无 `deviceId`/`mergeOnConnect`）

- [ ] **Step 3: 实现**

  `DeviceHistoryEntry` 加字段：

  ```kotlin
  @Serializable
  data class DeviceHistoryEntry(
      val serial: String,
      val alias: String? = null,
      val type: DeviceType? = null,
      val wirelessIp: String? = null,
      val wirelessPort: Int? = null,
      val lastConnectedAt: Long? = null,
      val lastUsedAt: Long? = null,
      val tag: String? = null,
      /** 设备真实 serial（getprop ro.serialno），跨端口/USB/无线的稳定身份。spec 2026-10-23 §4.4。 */
      val deviceId: String? = null,
  )
  ```

  `mergeOnConnect`（与现有 `mutate` 同模式，单次读改写原子）：

  ```kotlin
  /** spec §4.4 合并规则：deviceId 相同 / serial==deviceId / 同 IP 无 deviceId 残留 → 并为一条。
   *  返回被移除的旧条目（调用方 INFO 记账）。带非空且不同 alias 的同 IP 条目保留不动。 */
  suspend fun mergeOnConnect(
      deviceId: String,
      serial: String,
      type: DeviceType,
      wirelessIp: String?,
      wirelessPort: Int?,
  ): List<DeviceHistoryEntry> {
      var removed: List<DeviceHistoryEntry> = emptyList()
      mutate { entries ->
          val group = entries.filter { it.deviceId == deviceId || it.serial == serial || it.serial == deviceId }
          // 同 IP 残留：无 deviceId、形如 "ip:port"、alias 为空的旧无线条目
          val staleSameIp = wirelessIp?.let { ip ->
              entries.filter { it.deviceId == null && it.serial != serial && it.serial.startsWith("$ip:") && it.alias == null }
          } ?: emptyList()
          removed = group.filter { it.serial != serial } + staleSameIp
          val kept = entries.filterNot { it in group || it in staleSameIp }
          if (group.isEmpty() && staleSameIp.isEmpty()) {
              kept + DeviceHistoryEntry(
                  serial = serial, type = type, wirelessIp = wirelessIp, wirelessPort = wirelessPort,
                  deviceId = deviceId, lastConnectedAt = clock(), lastUsedAt = clock(),
              )
          } else {
              val current = group.firstOrNull { it.serial == serial } ?: staleSameIp.firstOrNull()
              val alias = current?.alias ?: group.firstNotNullOfOrNull { it.alias } ?: staleSameIp.firstNotNullOfOrNull { it.alias }
              val tag = current?.tag ?: group.firstNotNullOfOrNull { it.tag } ?: staleSameIp.firstNotNullOfOrNull { it.tag }
              val anyWireless = (group + staleSameIp).any { it.type == DeviceType.WIRELESS } || type == DeviceType.WIRELESS
              kept + DeviceHistoryEntry(
                  serial = serial,
                  alias = alias,
                  type = if (anyWireless) DeviceType.WIRELESS else type,
                  wirelessIp = wirelessIp,
                  wirelessPort = wirelessPort,
                  lastConnectedAt = clock(),
                  lastUsedAt = clock(),
                  tag = tag,
                  deviceId = deviceId,
              )
          }
      }
      return removed
  }
  ```

  `mutate` 的 transform 是同步 lambda，捕获 `removed` 赋值可行（mutate 内部串行执行）。确认 `mutate` 签名一致后再落码。

- [ ] **Step 4: 验证通过** → Run: `./gradlew :core:test --tests "com.adbgui.core.device.DeviceHistoryStoreTest"` → PASS

- [ ] **Step 5: Commit**

  ```bash
  git add core/src/main/kotlin/com/adbgui/core/device/DeviceHistoryStore.kt core/src/test/kotlin/com/adbgui/core/device/DeviceHistoryStoreTest.kt
  git commit -m "feat(core): deviceId field + atomic mergeOnConnect in DeviceHistoryStore"
  ```

---

### Task 5: `DeviceMerge` 纯函数 — 视图按 deviceId 折叠

**Files:**
- Create: `core/src/main/kotlin/com/adbgui/core/device/DeviceMerge.kt`
- Modify: `core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt`（`recompute` 改用 `DeviceMerge.merge`）
- Test: `core/src/test/kotlin/com/adbgui/core/device/DeviceMergeTest.kt`

**Interfaces:**
- Consumes: `DeviceHistoryEntry`（Task 4 的 `deviceId`）、`DeviceSnapshot`、`DeviceView`
- Produces: `object DeviceMerge { fun merge(live: List<DeviceSnapshot>, hist: List<DeviceHistoryEntry>): List<DeviceView> }`

- [ ] **Step 1: 写失败测试**

  ```kotlin
  package com.adbgui.core.device

  import com.adbgui.core.domain.DeviceSnapshot
  import com.adbgui.core.domain.DeviceStatus
  import com.adbgui.core.domain.DeviceType
  import com.adbgui.core.domain.DeviceView
  import kotlin.test.Test
  import kotlin.test.assertEquals

  class DeviceMergeTest {

      @Test
      fun folds_history_entries_sharing_deviceId_into_one_view() {
          val hist = listOf(
              DeviceHistoryEntry(serial = "10.0.5.221:38897", type = DeviceType.WIRELESS, wirelessIp = "10.0.5.221", wirelessPort = 38897, alias = "我的手机", deviceId = "af199bb4", lastConnectedAt = 100),
              DeviceHistoryEntry(serial = "10.0.5.221:37751", type = DeviceType.WIRELESS, wirelessIp = "10.0.5.221", wirelessPort = 37751, deviceId = "af199bb4", lastConnectedAt = 200),
          )
          val live = listOf(DeviceSnapshot("10.0.5.221:37751", DeviceStatus.ONLINE))
          val views = DeviceMerge.merge(live, hist)
          assertEquals(1, views.size)
          assertEquals("10.0.5.221:37751", views[0].serial)      // 代表取在线的那条（命令要用它）
          assertEquals("我的手机", views[0].alias)
          assertEquals(DeviceStatus.ONLINE, views[0].status)
          assertEquals(200, views[0].lastConnectedAt)
      }

      @Test
      fun usb_and_wireless_entries_with_same_deviceId_fold_to_wireless_view() {
          val hist = listOf(
              DeviceHistoryEntry(serial = "af199bb4", type = DeviceType.USB, deviceId = "af199bb4"),
              DeviceHistoryEntry(serial = "10.0.5.221:37751", type = DeviceType.WIRELESS, wirelessIp = "10.0.5.221", wirelessPort = 37751, deviceId = "af199bb4"),
          )
          val views = DeviceMerge.merge(emptyList(), hist)
          assertEquals(1, views.size)
          assertEquals(DeviceType.WIRELESS, views[0].type)
      }

      @Test
      fun live_only_device_without_history_gets_view() {
          val views = DeviceMerge.merge(listOf(DeviceSnapshot("serialX", DeviceStatus.ONLINE)), emptyList())
          assertEquals(1, views.size)
          assertEquals(DeviceStatus.ONLINE, views[0].status)
      }

      @Test
      fun entries_without_deviceId_stay_separate() {
          val hist = listOf(
              DeviceHistoryEntry(serial = "a:1", type = DeviceType.WIRELESS, wirelessIp = "a", wirelessPort = 1),
              DeviceHistoryEntry(serial = "a:2", type = DeviceType.WIRELESS, wirelessIp = "a", wirelessPort = 2),
          )
          assertEquals(2, DeviceMerge.merge(emptyList(), hist).size)   // 无 deviceId → 保守不并
      }
  }
  ```

- [ ] **Step 2: 验证失败** → Run: `./gradlew :core:test --tests "com.adbgui.core.device.DeviceMergeTest"` → 编译失败

- [ ] **Step 3: 实现**

  ```kotlin
  package com.adbgui.core.device

  import com.adbgui.core.domain.DeviceSnapshot
  import com.adbgui.core.domain.DeviceStatus
  import com.adbgui.core.domain.DeviceType
  import com.adbgui.core.domain.DeviceView

  /** 纯函数：tracker 实时列表 + 历史条目 → 展示视图。spec §4.4：以 deviceId ?: serial 为身份分组，
   *  同组折叠为一条（代表条目 = 在线 serial 优先，否则最新 lastUsedAt/lastConnectedAt）。 */
  object DeviceMerge {

      fun merge(live: List<DeviceSnapshot>, hist: List<DeviceHistoryEntry>): List<DeviceView> {
          data class Group(val entries: List<DeviceHistoryEntry>)

          val groups = linkedMapOf<String, Group>()          // 保持首现顺序（现 MRU 语义靠 sort 层）
          hist.forEach { e ->
              val key = e.deviceId ?: e.serial
              groups.getOrPut(key) { Group(mutableListOf()) }.entries.let { (it as MutableList).add(e) }
          }
          val liveCovered = mutableSetOf<String>()
          val views = groups.map { (_, g) ->
              val entries = g.entries
              val onlineEntry = entries.firstOrNull { e -> live.any { it.serial == e.serial && it.status == DeviceStatus.ONLINE } }
              val rep = onlineEntry ?: entries.maxByOrNull { it.lastUsedAt ?: it.lastConnectedAt ?: 0L } ?: entries.first()
              entries.forEach { liveCovered += it.serial }
              DeviceView(
                  serial = rep.serial,
                  status = live.firstOrNull { it.serial == rep.serial }?.status
                      ?: if (onlineEntry != null) DeviceStatus.ONLINE else DeviceStatus.OFFLINE,
                  alias = entries.firstNotNullOfOrNull { it.alias },
                  type = if (entries.any { it.type == DeviceType.WIRELESS }) DeviceType.WIRELESS else entries.firstOrNull { it.type != null }?.type,
                  wirelessIp = rep.wirelessIp,
                  wirelessPort = rep.wirelessPort,
                  lastConnectedAt = entries.maxOfOrNull { it.lastConnectedAt ?: 0L }?.takeIf { it > 0L },
                  lastUsedAt = entries.maxOfOrNull { it.lastUsedAt ?: 0L }?.takeIf { it > 0L },
                  tag = entries.firstNotNullOfOrNull { it.tag },
              )
          }
          // live 里有、历史里没有的（首次出现的 USB / adb 自动连上的）追加视图
          val extra = live.filter { it.serial !in liveCovered }.map { snap ->
              DeviceView(serial = snap.serial, status = snap.status)
          }
          return views + extra
      }
  }
  ```

  注意：`Group(entries)` 里强转 MutableList 的写法难看——直接用 `mutableMapOf<String, MutableList<DeviceHistoryEntry>>()` 落码。`status` 逻辑再核对：组内任一 serial 在线 → 该组视图 ONLINE（代表 serial 即使不是在线那条也显示 ONLINE？不——**代表 serial 必须可下发命令**：`rep = onlineEntry` 已保证在线时取在线条目；组内在线但 rep 是另一条的情况不存在，因为 onlineEntry 优先）。`status` 简化为 `live.firstOrNull { it.serial == rep.serial }?.status ?: DeviceStatus.OFFLINE`。

  然后 `DeviceRepository.recompute` 中原来的 `(live.map{it.serial} + hist.keys).distinct().map{...}` 整段替换为：

  ```kotlin
  val hist = history.load()
  val merged = DeviceMerge.merge(live, hist)
  _devices.value = merged
  ```

  `hist.keys` 的 associateBy 也不需要了。跑既有 `DeviceRepositoryTest` 全量（无 deviceId 的场景行为必须不变——这是回归红线）。

- [ ] **Step 4: 验证通过** → Run: `./gradlew :core:test --tests "com.adbgui.core.device.*"` → PASS（含既有回归）

- [ ] **Step 5: Commit**

  ```bash
  git add core/src/main/kotlin/com/adbgui/core/device/DeviceMerge.kt core/src/test/kotlin/com/adbgui/core/device/DeviceMergeTest.kt core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt
  git commit -m "feat(core): DeviceMerge folds device views by deviceId"
  ```

---

### Task 6: `connectWireless` 学习 deviceId 并触发合并

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt`（`connectWireless`）
- Test: `core/src/test/kotlin/com/adbgui/core/device/DeviceRepositoryTest.kt`（追加；先读现有测试的 Fake 装配模式照抄）

**Interfaces:**
- Consumes: `history.mergeOnConnect`（Task 4）、`commands.runShellCmd`
- Produces: `connectWireless(ip, port)` 成功后历史条目带 deviceId、旧重复条目被清（外部签名不变）

- [ ] **Step 1: 写失败测试（追加；Fake 装配照现有 DeviceRepositoryTest 的写法——FakeAdbProcessRunner + 临时目录 history）**

  ```kotlin
  @Test
  fun connectWireless_learns_deviceId_and_merges_stale_same_ip_entries() = runTest {
      // 装配照抄现有测试：FakeAdbProcessRunner runner；runner.whenArgsContains(listOf("connect", "10.0.5.221:37751"),
      //     AdbProcessResult(0, "connected to 10.0.5.221:37751", ""))
      // runner.whenArgsContains(listOf("-s", "10.0.5.221:37751", "shell", "getprop", "ro.serialno"),
      //     AdbProcessResult(0, "af199bb4\r\n", ""))   // pty 会带 \r，实现要 trim
      // 预置历史：旧的 10.0.5.221:38897 条目（无 deviceId）
      // repo.connectWireless("10.0.5.221", 37751) 断言 ConnectResult.success
      // 断言 history.load() 恰 1 条：serial == "10.0.5.221:37751"，deviceId == "af199bb4"
  }

  @Test
  fun connectWireless_serialno_failure_degrades_to_plain_upsert() = runTest {
      // getprop 脚本设为 exitCode 1（AdbCommandException）：连接仍成功，历史仍写入，
      // 但 deviceId 为 null、条目数不变（无合并发生）
  }
  ```

  （伪码注释处落成真实断言；`runShellCmd` 的 sanitize 会剥 `\r`，但实现仍需 `.trim()`。）

- [ ] **Step 2: 验证失败** → Run: `./gradlew :core:test --tests "com.adbgui.core.device.DeviceRepositoryTest"` → FAIL

- [ ] **Step 3: 实现**

  ```kotlin
  suspend fun connectWireless(ip: String, port: Int): ConnectResult {
      val r = commands.connect(ip, port)
      if (r.success) {
          val serial = "$ip:$port"
          val deviceId = learnDeviceId(serial)
          if (deviceId != null) {
              val removed = history.mergeOnConnect(deviceId, serial, DeviceType.WIRELESS, ip, port)
              removed.forEach { logger.info("history: merged duplicate entry ${it.serial} into deviceId=$deviceId") }
          } else {
              history.upsert(serial = serial, type = DeviceType.WIRELESS, wirelessIp = ip, wirelessPort = port)
          }
          history.touchLastUsed(serial)
          recompute(tracker.devices.value)
          namingScope.launch { autoName(serial) }
      }
      return r
  }

  /** getprop ro.serialno；失败（unauthorized/离线竞态）返回 null，不阻塞连接成功路径。 */
  private suspend fun learnDeviceId(serial: String): String? = try {
      val v = commands.runShellCmd(serial, "getprop ro.serialno").trim()
      if (v.isNotEmpty() && !v.contains(' ') && v.length <= 32) v else null
  } catch (e: kotlinx.coroutines.CancellationException) {
      throw e
  } catch (e: Exception) {
      logger.debug("deviceId learn failed for $serial: ${e.message}")
      null
  }
  ```

- [ ] **Step 4: 验证通过** → Run: `./gradlew :core:test --tests "com.adbgui.core.device.DeviceRepositoryTest"` → PASS

- [ ] **Step 5: Commit**

  ```bash
  git add core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt core/src/test/kotlin/com/adbgui/core/device/DeviceRepositoryTest.kt
  git commit -m "feat(core): connectWireless learns deviceId and merges duplicate history entries"
  ```

---

### Task 7: `WirelessReconnector`（Layer 1 端点自动重试）

**Files:**
- Create: `core/src/main/kotlin/com/adbgui/core/device/WirelessReconnector.kt`
- Test: `core/src/test/kotlin/com/adbgui/core/device/WirelessReconnectorTest.kt`

**Interfaces:**
- Consumes: `DeviceRepository.connectWireless / devices`、`DeviceHistoryStore.load`、`Logger`
- Produces:
  - `class WirelessReconnector(repo: DeviceRepository, history: DeviceHistoryStore, logger: Logger, scope: CoroutineScope, autoReconnectEnabled: () -> Boolean = { true }, clock: () -> Long = System::currentTimeMillis)`
  - `fun start() / fun stop()`
  - 语义：每 5s tick；候选 = 无线历史条目、`lastUsedAt` 7 天内、按 lastUsedAt 降序前 10；在线的跳过并重置退避；离线的到点重试 `repo.connectWireless(ip, port)`；失败按设备指数退避 5s→60s 封顶，成功重置。

- [ ] **Step 1: 写失败测试**

  ```kotlin
  package com.adbgui.core.device

  import com.adbgui.core.domain.DeviceType
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.test.advanceTimeBy
  import kotlinx.coroutines.test.runTest
  import kotlin.test.Test

  class WirelessReconnectorTest {
      // 装配：与 DeviceRepositoryTest 相同的 Fake runner + 临时目录 history + TestScope。
      // clock = { testScheduler.currentTime }（lastUsedAt 用 testScheduler 写入，统一虚拟时间）

      @Test
      fun retries_saved_endpoint_until_success_then_stops_retrying() = runTest {
          // history: entry(serial="10.0.5.221:37751", WIRELESS, ip, port, lastUsedAt=currentTime, deviceId=null)
          //   注意 lastUsedAt 要写成 clock 当前值附近，别落在 7 天窗口外
          // runner: connect 脚本前 2 次 exitCode=1（失败），第 3 次 success
          // reconnector.start(); advanceTimeBy(60_000)
          // 断言 runner.runs 中 connect "10.0.5.221:37751" 出现 3 次（5s、10s 退避…按 5/10 排程）
          // 再 advanceTimeBy(120_000)：次数不再增加（在线即停）
      }

      @Test
      fun failure_backoff_caps_at_60s() = runTest {
          // connect 永远失败；advanceTimeBy(600_000)；断言尝试次数 ≈ 10s 内 5/10/20/40/60/60…（允许 ±1 的调度误差，用区间断言）
      }

      @Test
      fun disabled_via_callback_makes_no_attempts() = runTest {
          // autoReconnectEnabled = { false }; advanceTimeBy(60_000); 断言 0 次 connect
      }
  }
  ```

  在线判定依赖 `repo.devices`：Fake runner 让 `adb devices` 返回已连接条目（现有测试装配已有此模式——照抄）。

- [ ] **Step 2: 验证失败** → Run: `./gradlew :core:test --tests "com.adbgui.core.device.WirelessReconnectorTest"` → 编译失败

- [ ] **Step 3: 实现**

  ```kotlin
  package com.adbgui.core.device

  import com.adbgui.core.domain.DeviceType
  import com.adbgui.core.log.Logger
  import kotlinx.coroutines.CoroutineScope
  import kotlinx.coroutines.Job
  import kotlinx.coroutines.delay
  import kotlinx.coroutines.isActive
  import kotlinx.coroutines.launch

  /** Layer 1（spec §3）：对近 7 天用过的无线历史条目周期重试 adb connect ip:port。
   *  端口未变的场景（手机唤醒/回网）几秒内自动回连；端口已变由 Layer 2/3 接管。
   *  状态只在本协程内读写，无需跨线程保护。 */
  class WirelessReconnector(
      private val repo: DeviceRepository,
      private val history: DeviceHistoryStore,
      private val logger: Logger,
      private val scope: CoroutineScope,
      private val autoReconnectEnabled: () -> Boolean = { true },
      private val clock: () -> Long = System::currentTimeMillis,
  ) {
      private var job: Job? = null
      private val backoffMs = mutableMapOf<String, Long>()
      private val nextAttemptAt = mutableMapOf<String, Long>()

      fun start() {
          if (job?.isActive == true) return
          job = scope.launch { loop() }
      }

      fun stop() { job?.cancel(); job = null }

      private suspend fun loop() {
          while (scope.isActive) {
              delay(TICK_MS)
              if (!autoReconnectEnabled()) continue
              val now = clock()
              val candidates = history.load()
                  .filter { it.type == DeviceType.WIRELESS && it.wirelessIp != null && it.wirelessPort != null }
                  .filter { (it.lastUsedAt ?: 0L) > now - SEVEN_DAYS_MS }
                  .sortedByDescending { it.lastUsedAt ?: 0L }
                  .take(MAX_CANDIDATES)
              val onlineSerials = repo.devices.value.filter { it.isLive }.map { it.serial }.toSet()
              for (c in candidates) {
                  if (c.serial in onlineSerials) { backoffMs.remove(c.serial); nextAttemptAt.remove(c.serial); continue }
                  if ((nextAttemptAt[c.serial] ?: 0L) > now) continue
                  val ok = try {
                      repo.connectWireless(c.wirelessIp!!, c.wirelessPort!!).success
                  } catch (e: kotlinx.coroutines.CancellationException) {
                      throw e
                  } catch (e: Exception) {
                      logger.warn("auto-reconnect error for ${c.serial}: ${e.message}")
                      false
                  }
                  if (ok) {
                      logger.info("auto-reconnect: ${c.serial} back online")
                      backoffMs[c.serial] = INITIAL_BACKOFF_MS
                      nextAttemptAt[c.serial] = now + INITIAL_BACKOFF_MS
                  } else {
                      val next = (backoffMs[c.serial] ?: INITIAL_BACKOFF_MS).coerceAtMost(MAX_BACKOFF_MS)
                      backoffMs[c.serial] = (next * 2).coerceAtMost(MAX_BACKOFF_MS)
                      nextAttemptAt[c.serial] = now + next
                      logger.debug("auto-reconnect failed for ${c.serial}; next attempt in ${next}ms")
                  }
              }
          }
      }

      companion object {
          private const val TICK_MS = 5_000L
          private const val INITIAL_BACKOFF_MS = 5_000L
          private const val MAX_BACKOFF_MS = 60_000L
          private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
          private const val MAX_CANDIDATES = 10
      }
  }
  ```

  测试对 connect 调用次数的断言注意：`repo.connectWireless` 内部还会触发 `adb devices` 轮询与 `getprop`（Task 6），Fake runner 的 `runs` 断言要过滤只数 `["connect", ...]` 的条目。

- [ ] **Step 4: 验证通过** → Run: `./gradlew :core:test --tests "com.adbgui.core.device.WirelessReconnectorTest"` → PASS

- [ ] **Step 5: Commit**

  ```bash
  git add core/src/main/kotlin/com/adbgui/core/device/WirelessReconnector.kt core/src/test/kotlin/com/adbgui/core/device/WirelessReconnectorTest.kt
  git commit -m "feat(core): WirelessReconnector retries saved wireless endpoints with backoff"
  ```

---

### Task 8: `Settings.autoReconnect` + 设置页开关

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/settings/SettingsStore.kt`（`Settings` 加字段 + setter，仿 `logcatAutoRecord` 的现有写法——先 grep 它怎么接的）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsScreen.kt`（Boolean 开关行，仿现有开关）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/SettingsViewModel.kt`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt`（zh/en 两处）
- Test: `core/src/test/kotlin/com/adbgui/core/settings/SettingsStoreTest.kt`（追加，仿现有）

**Interfaces:**
- Produces: `Settings.autoReconnect: Boolean = true`；store 的 setter（命名照现有模式，如 `setAutoReconnect(v: Boolean)`）；i18n key `settings_auto_reconnect`（zh："无线设备自动重连"，en："Auto-reconnect wireless devices"）

- [ ] **Step 1: 写失败测试**（追加：默认 true；set 后持久化可读回——照现有 logcatAutoRecord 的测试写法）
- [ ] **Step 2: 验证失败** → `./gradlew :core:test --tests "com.adbgui.core.settings.SettingsStoreTest"`
- [ ] **Step 3: 实现**（Settings 字段 + setter + UI 开关 + i18n；全部照 `logcatAutoRecord` 现有模式逐处对抄，不发明新写法）
- [ ] **Step 4: 验证通过** → `./gradlew :core:test :desktop:test`
- [ ] **Step 5: Commit** → `feat(settings): autoReconnect toggle`

---

### Task 9: `DeviceRepository` 接 mDNS — 连接决策 + `discovered` 列表 + 未知设备懒学习

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt`
- Test: `core/src/test/kotlin/com/adbgui/core/device/DeviceRepositoryTest.kt`（追加）

**Interfaces:**
- Consumes: `MdnsScanner.services`（Task 3）、`connectWireless`（Task 6）、`MdnsService.deviceSerial`
- Produces:
  - 构造函数新增 `mdns: MdnsScanner? = null`（默认 null——既有测试与调用方不动）
  - `val discovered: StateFlow<List<MdnsService>>`（port>0 的全部服务，UI 展示用）
  - 语义（spec §4.5）：`_adb-tls-connect._tcp` 服务中，`deviceSerial` 或 `host(IP)` 命中已知历史（deviceId 或 wirelessIp）→ 自动 `connectWireless(host, port)`；未知 → 只进 discovered 不自动连。`_adb._tcp`（老式）只进 discovered。
  - 未知 serial 上线（adb server 自己连上的）时 `autoName` 路径顺带学习 deviceId（spec §4.3"懒加载"）。

- [ ] **Step 1: 写失败测试（追加）**

  ```kotlin
  @Test
  fun mdns_service_matching_known_deviceId_triggers_connect() = runTest {
      // FakeMdnsScanner 或直接构造 MdnsScanner(FakeMdnsSocket...)——更简单：测 repo 时传一个
      // 自制的 FakeMdnsScanner（实现 services StateFlow 可手动赋值）。MdnsScanner 没接口，
      // 给 repo 传的参数类型用接口还是类？——决策：repo 参数类型用现有类 MdnsScanner，
      // 测试用真 MdnsScanner + FakeMdnsSocket（Task 3 已具备，零新假件）。
      // runner 预设：connect "10.0.5.221:37751" 成功 + getprop ro.serialno -> af199bb4
      // history 预置：entry(deviceId="af199bb4", serial="10.0.5.221:38897", wirelessIp=10.0.5.221)
      // scanner.services.value = [MdnsService("adb-af199bb4-Q9dhj", "_adb-tls-connect._tcp", "10.0.5.221", 37751, emptyMap())]
      // 断言：repo.devices.value 出现 "10.0.5.221:37751" 在线视图；历史更新为新端口
  }

  @Test
  fun mdns_service_unknown_goes_to_discovered_only() = runTest {
      // history 空；scanner.services 赋未知服务（serial 不命中）
      // 断言 repo.discovered.value 含该服务；runner.runs 无 connect 命令
  }

  @Test
  fun unknown_wireless_serial_coming_online_gets_deviceId_learned() = runTest {
      // runner: "devices" 返回 "10.0.5.221:37751 device"；getprop 模型/serialno 脚本齐
      // tracker 模拟该设备上线（现有测试已有"设备上线→autoName"装配，照抄）
      // 断言 history 出现 deviceId="af199bb4" 的条目
  }
  ```

- [ ] **Step 2: 验证失败** → Run: `./gradlew :core:test --tests "com.adbgui.core.device.DeviceRepositoryTest"`

- [ ] **Step 3: 实现**

  `DeviceRepository` 增补（构造参数 + init 收集 + autoName 懒学习）：

  ```kotlin
  class DeviceRepository(
      private val tracker: IDeviceTracker,
      private val history: DeviceHistoryStore,
      private val commands: CommandRunner,
      private val logger: Logger,
      private val scope: CoroutineScope,
      private val mdns: MdnsScanner? = null,
  ) {
      // ... 现有成员 ...

      private val _discovered = MutableStateFlow<List<MdnsService>>(emptyList())
      val discovered: StateFlow<List<MdnsService>> = _discovered.asStateFlow()

      // 自动连接去重：避免 scanner 每 10s 重发同服务重复 connect（在 connect 往返期间）
      private val mdnsInFlight = mutableSetOf<String>()     // 只在 scope 协程读，Mutex 保护
      private val mdnsMutex = Mutex()

      init {
          // ... 现有 init ...
          mdns?.let { scanner ->
              scope.launch {
                  scanner.services.collect { handleMdnsServices(it) }
              }
          }
      }

      private suspend fun handleMdnsServices(services: List<MdnsService>) {
          _discovered.value = services
          val hist = history.load()
          val knownDeviceIds = hist.mapNotNull { it.deviceId }.toSet()
          val knownIps = hist.mapNotNull { it.wirelessIp }.toSet()
          for (svc in services) {
              if (svc.serviceType != MdnsScanner.SERVICE_TLS) continue      // 老式只展示（spec §8）
              val key = "${svc.host}:${svc.port}"
              val known = (svc.deviceSerial?.let { it in knownDeviceIds } == true) || svc.host in knownIps
              if (!known) continue
              val inFlight = mdnsMutex.withLock { if (key in mdnsInFlight) true else { mdnsInFlight += key; false } }
              if (inFlight) continue
              scope.launch {
                  try {
                      val r = connectWireless(svc.host, svc.port)
                      if (r.success) logger.info("mdns auto-connect: ${svc.instanceName} -> $key")
                  } finally {
                      mdnsMutex.withLock { mdnsInFlight -= key }
                  }
              }
          }
      }
  ```

  autoName 懒学习（在 `autoName` 成功设别名后追加）：

  ```kotlin
  // autoName 内 setAlias 成功分支之后：
  val entry = history.load().firstOrNull { it.serial == serial }
  if (entry?.deviceId == null && serial.contains(':')) {          // 无线 serial 形如 ip:port
      learnDeviceId(serial)?.let { did ->
          val removed = history.mergeOnConnect(did, serial, DeviceType.WIRELESS, entry?.wirelessIp, entry?.wirelessPort)
          removed.forEach { logger.info("history: merged duplicate entry ${it.serial} into deviceId=$did") }
          recompute(tracker.devices.value)
      }
  }
  ```

  注意 `mdnsMutex` 与已有 `namingMutex` 并存但用途独立，注释写清。repo 文件已超 300 行——本 Task 只加 ~50 行；若加完明显超限，把 `handleMdnsServices` 抽成 `MdnsAutoConnect.kt`（同包，持 repo 方法引用），执行者自行判断，但**别把合并逻辑挪进 UI**。

- [ ] **Step 4: 验证通过** → Run: `./gradlew :core:test`（全量，确认无回归）
- [ ] **Step 5: Commit** → `feat(core): mDNS-driven auto-connect + discovered services + lazy deviceId learning`

---

### Task 10: CompositionRoot 装配

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/main/CompositionRoot.kt`

**Interfaces:**
- Consumes: 全部前序产物
- Produces: 应用启动时 scanner/reconnector 随 tracker 一起 start，退出时 stop

- [ ] **Step 1: 实现**（照现有 `tracker` 的构造与 `tracker.start()` 位置，附近追加；设置读取方式照 `logcatAutoRecord` 在 CompositionRoot/消费方的现有取法——先 grep）：

  ```kotlin
  val mdnsSocket = JvmMdnsSocket()     // 绑定失败抛 IllegalStateException：包 runCatching，失败仅 log.warn 后置 null（功能降级不崩应用）
  val mdnsScanner = mdnsSocket?.let { MdnsScanner(it, logger, scope) }
  val repository = DeviceRepository(tracker, history, commands, logger, scope, mdnsScanner)
  // settingsStore 的实际字段名/访问方式以现有代码为准（如 settingsStore.settings.value.autoReconnect）
  val reconnector = WirelessReconnector(repository, history, logger, scope) { settingsStore.settings.value.autoReconnect }
  // tracker.start() 附近：mdnsScanner?.start(); reconnector.start()
  ```

  对应的 stop：找现有 `tracker.stop()` / app 退出的清理点，同处 `mdnsScanner?.stop(); reconnector.stop()`，socket close（`mdnsSocket?.close()`，若 scanner 无 close 语义就 socket 显式 close——保证 MdnsSocket.close 有调用方，不留死代码）。

- [ ] **Step 2: 编译 + 全量测试** → `./gradlew :core:test :desktop:test` → PASS
- [ ] **Step 3: 冒烟运行** → 按用户惯例（记忆：改完主动杀旧 MainKt JVM 后 `./gradlew :desktop:run`）。观察：应用启动无异常；日志出现 mdns 查询（DEBUG）；TV 广播被发现时 `discovered` 有内容（UI Task 11 之前可从日志确认 auto-connect 尝试）。
- [ ] **Step 4: Commit** → `feat(desktop): wire MdnsScanner + WirelessReconnector into composition root`

---

### Task 11: ConnectDialog — 整串粘贴 + 「发现的设备」列表

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/ConnectDialog.kt`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceListViewModel.kt`（暴露 `discovered`）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt`
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/ui/DeviceListViewModelTest.kt`（追加，如无此文件则 grep VM 测试实际位置）

**Interfaces:**
- Consumes: `repo.discovered`（Task 9）、`vm.connect`
- Produces:
  - VM：`val discovered: StateFlow<List<MdnsService>> = repo.discovered`
  - Dialog：IP 框整串粘贴 `10.0.5.221:37751` 自动拆分；发现列表一屏展示 + 一键连接
  - i18n：`connect_discovered_title`（zh "发现的设备" / en "Discovered devices"）、`connect_discovered_connect`（zh "连接" / en "Connect"）

- [ ] **Step 1: 写失败测试**（VM 层：`discovered` 透传 repo StateFlow——repo 用 Task 9 测试同款装配或既有 fake repo 模式）
- [ ] **Step 2: 验证失败**
- [ ] **Step 3: 实现**

  VM 追加：

  ```kotlin
  val discovered: StateFlow<List<MdnsService>> = repo.discovered
  ```

  ConnectDialog 的 IP 输入框 `onValueChange` 改为：

  ```kotlin
  onValueChange = { raw ->
      val t = raw.trim()
      if (t.count { it == ':' } == 1 && t.substringAfter(':').all { it.isDigit() }) {
          // 整串粘贴 "10.0.5.221:37751"：拆进两个字段
          ip = t.substringBefore(':')
          port = t.substringAfter(':').take(5)
      } else {
          ip = raw
      }
  }
  ```

  发现列表（dialog `text` 的 Column 尾部追加，空列表不渲染）：

  ```kotlin
  val discovered by vm.discovered.collectAsState()
  if (discovered.isNotEmpty()) {
      Spacer(Modifier.padding(8.dp))
      Text(Strings.t("connect_discovered_title"), style = MaterialTheme.typography.labelMedium)
      discovered.forEach { svc ->
          Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
          ) {
              Text("${svc.host}:${svc.port}", style = MaterialTheme.typography.bodySmall)
              TextButton(
                  onClick = { if (!busy) vm.connect(svc.host, svc.port) },
                  enabled = !busy,
              ) { Text(Strings.t("connect_discovered_connect")) }
          }
      }
  }
  ```

- [ ] **Step 4: 验证通过** → `./gradlew :desktop:test`（VM 测试）+ 冒烟看 UI
- [ ] **Step 5: Commit** → `feat(desktop): connect dialog shows mDNS-discovered devices and accepts pasted ip:port`

---

### Task 12: 设备行内联端口更新（Layer 3 UI）

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceListViewModel.kt`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/DeviceListPane.kt`（`DeviceRow` 内追加内联子行；`DeviceListPane` 传新回调）
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt`
- Test: `desktop/src/test/kotlin/com/adbgui/desktop/ui/DeviceListViewModelTest.kt`（追加）

**Interfaces:**
- Produces:
  - VM：`data class PortUpdate(val ip: String, val stalePort: Int, val deviceSerial: String)`；`val portUpdate: StateFlow<PortUpdate?>`；`fun submitPortUpdate(newPort: Int)`；`fun cancelPortUpdate()`
  - 语义：`reconnect(ip, port)` 失败且 `r.reason == ConnectFailureReason.PORT_STALE` → 设 `portUpdate`；`submitPortUpdate` 清状态并 `connect(ip, newPort)`
  - i18n：`port_stale_hint`（zh "端口已变？打开手机「无线调试」查看新端口" / en "Port changed? Check the new port in the phone's Wireless debugging screen"）、`port_stale_new_port`（zh "新端口" / en "New port"）、`port_stale_reconnect`（zh "重连" / en "Reconnect"）

- [ ] **Step 1: 写失败测试**（VM）：

  ```kotlin
  // fake repo 的 connectWireless 返回 ConnectResult(serial=null, success=false, message="...", reason=PORT_STALE)
  // vm.reconnect("10.0.5.221", 38897)
  // 断言 portUpdate.value == PortUpdate("10.0.5.221", 38897, ...)
  // vm.submitPortUpdate(37751)
  // 断言 portUpdate.value == null 且 vm 发起过 connect("10.0.5.221", 37751)
  // 非 PORT_STALE 失败（UNREACHABLE）不触发 portUpdate
  ```

- [ ] **Step 2: 验证失败**
- [ ] **Step 3: 实现**

  VM 追加：

  ```kotlin
  data class PortUpdate(val ip: String, val stalePort: Int, val deviceSerial: String)

  private val _portUpdate = MutableStateFlow<PortUpdate?>(null)
  val portUpdate: StateFlow<PortUpdate?> = _portUpdate.asStateFlow()

  fun reconnect(ip: String, port: Int) {
      scope.launch {
          _busy.value = true
          try {
              val r = repo.connectWireless(ip, port)
              if (r.success) {
                  _scrollToSerial.tryEmit("$ip:$port")
              } else {
                  _error.value = formatConnectError(r, ip, port)
                  if (r.reason == ConnectFailureReason.PORT_STALE) {
                      _portUpdate.value = PortUpdate(ip, port, "$ip:$port")
                  }
              }
          } finally { _busy.value = false }
      }
  }

  fun submitPortUpdate(newPort: Int) {
      val u = _portUpdate.value ?: return
      _portUpdate.value = null
      _error.value = null
      connect(u.ip, newPort)
  }

  fun cancelPortUpdate() { _portUpdate.value = null }
  ```

  `DeviceRow`（`DeviceListPane.kt`）：行内容下方、当 `vm.portUpdate` 的 `deviceSerial == device.serial` 时渲染内联区（沿用文件现有的排版/间距习惯，无模态）：

  ```kotlin
  // DeviceRow 新增参数：onSubmitPortUpdate: (Int) -> Unit, onCancelPortUpdate: () -> Unit
  val upd by portUpdate.collectAsState()   // portUpdate 由 DeviceListPane 往下传（StateFlow 参数或整 vm 传递，照现有传参风格）
  if (upd?.deviceSerial == device.serial) {
      var newPort by remember(upd) { mutableStateOf("") }
      Column(Modifier.padding(start = 32.dp, bottom = 4.dp)) {
          Text(Strings.t("port_stale_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
          Row(verticalAlignment = Alignment.CenterVertically) {
              OutlinedTextField(
                  value = newPort,
                  onValueChange = { newPort = it.filter { c -> c.isDigit() }.take(5) },
                  label = { Text(Strings.t("port_stale_new_port")) },
                  singleLine = true,
                  modifier = Modifier.width(120.dp),
              )
              Spacer(Modifier.width(8.dp))
              Button(onClick = { newPort.toIntOrNull()?.let(onSubmitPortUpdate) }, enabled = newPort.length == 5) {
                  Text(Strings.t("port_stale_reconnect"))
              }
          }
      }
  }
  ```

  传参走现有 `DeviceListPane` → `DeviceRow` 的参数链（`onReconnect` 同款路径），不新建全局状态。

- [ ] **Step 4: 验证通过** → `./gradlew :desktop:test` + 冒烟（连上手机 → 改历史端口为错值 → 点重连 → 出现内联提示 → 输入手机屏上真实端口 → 重连成功）
- [ ] **Step 5: Commit** → `feat(desktop): inline port-update row on stale-port reconnect failure`

---

### Task 13: 收尾 — CHANGELOG + 全量验证 + 手动冒烟

**Files:**
- Modify: `CHANGELOG.md`（新 v2 小节，照现有条目风格）

- [ ] **Step 1: CHANGELOG 小节**（要点：四层机制、实证结论引用 spec、迁移行为、`_adb-tls-connect` 真实 fixture 待办如未录到）
- [ ] **Step 2: 全量测试** → `./gradlew :core:test :desktop:test` → 全绿
- [ ] **Step 3: 端到端冒烟**（真机）：
  1. `./gradlew :desktop:run`，确认启动无异常；
  2. 手机无线调试开着端口不变 → 30s 内历史设备自动回连（Layer 1）；
  3. 重开无线调试（换端口）→ 手机屏看新端口 → 历史设备行点重连 → 内联端口更新 → 一键回连（Layer 3 + 4：旧条目被合并，列表无重复）；
  4. TCL TV 开机时观察发现列表（Layer 2，`_adb._tcp` 只展示）。
- [ ] **Step 4: Commit** → `docs: changelog for wireless auto-reconnect feature`

---

## Self-Review 记录

- **Spec 覆盖**：spec §3 四层 → Task 7（L1）/Task 1-3+9（L2）/Task 12（L3）/Task 4-6（L4）；§4.1→Task 2/3；§4.2→Task 1；§4.3→Task 6/9（懒学习）；§4.4→Task 4/5；§4.5→Task 9（**spec 两处表述的矛盾——"扫描到服务即 connect" vs "未知不自动连"——以 §4.5 后半为准：已知 deviceId/IP 才自动连，未知只进发现列表**，已在 Task 9 固化）；§5→Task 11/12/8；§6→各 Task 日志要求；§7→Task 1 fixture + 待办；§8 边界未越。
- **类型一致性**：`MdnsService`/`MdnsSocket`/`MdnsScanner.services`/`mergeOnConnect`/`DeviceMerge.merge`/`PortUpdate` 各 Task 间签名已互检。
- **风险点**（执行者注意）：Task 1 builder 的压缩指针 offset 记录位置；Task 2 `reuseAddress` 必须在 bind 前；Task 3 receive 空转必须 `delay`；Task 5 既有 `DeviceRepositoryTest` 回归红线；Task 9 repo 文件长度阈值。

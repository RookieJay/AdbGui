package com.adbgui.core.mdns

import com.adbgui.core.domain.MdnsService
import com.adbgui.core.log.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 周期 mDNS 探测（spec §4.1）：每 10s 查询 _adb-tls-connect._tcp 与 _adb._tcp，持续收包，
 *  服务 30s 未刷新即过期。独立于 adb 的 mdns 后端（实证它在本机是坏的，spec §2）。
 *
 *  双通道发现：组播（同子网）+ 对 [knownHosts] 提供的历史无线设备 IP 逐个 legacy unicast
 *  探测（跨子网组播不过路由，2026-11-02 实测唯一可达通道）。
 *
 *  @param io 解析报文所用的 dispatcher（真机报文最大 ~9KB，避免占主线程）；
 *            测试传 [kotlinx.coroutines.Dispatchers.Unconfined]。
 *  @param knownHosts 已知无线设备 IP 集合（历史记录）；unicast 探测目标。测试注入脚本。 */
class MdnsScanner(
    private val socket: MdnsSocket,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val knownHosts: suspend () -> Set<String> = { emptySet() },
) {
    private val _services = MutableStateFlow<List<MdnsService>>(emptyList())
    val services: StateFlow<List<MdnsService>> = _services.asStateFlow()

    private var job: Job? = null
    private var probeJob: Job? = null

    // runLoop 协程与 probeLoop 协程都会写 seen —— Mutex 保护（跨线程可变状态规范）
    private val seenMutex = Mutex()
    private val seen = LinkedHashMap<String, Pair<MdnsService, Long>>() // key: "instanceName.serviceType"

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
        if (probeJob?.isActive != true) probeJob = scope.launch { probeLoop() }
    }

    fun stop() {
        job?.cancel(); job = null
        probeJob?.cancel(); probeJob = null
    }

    private suspend fun runLoop() {
        var nextQueryAt = 0L
        while (currentCoroutineContext().isActive) {
            val now = clock()
            // 每轮开头重算（用最新 now）：处理 30s 过期（StateFlow 合并相等值）
            val alive = seenMutex.withLock {
                seen.filterValues { now - it.second <= EXPIRY_MS }
                    .mapValues { it.value.first }
                    .values
                    .filter { it.port > 0 }
                    .sortedBy { it.instanceName }
            }
            _services.value = alive

            if (now >= nextQueryAt) {
                socket.sendQuery(SERVICE_TLS)
                socket.sendQuery(SERVICE_LEGACY)
                nextQueryAt = now + QUERY_INTERVAL_MS
            }
            val pkt = socket.receive(RECEIVE_TIMEOUT_MS)
            if (pkt != null) {
                val parsed = try {
                    withContext(io) { MdnsResponseParser.parse(pkt) }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce                                                 // 协程取消必须传播
                } catch (t: Throwable) {
                    // Belt-and-braces: parser 已做 bounds 检查，但任何未预见的报文缺陷
                    // 不应让 scanner 协程挂掉（否则整个 mDNS 发现永久停摆）。
                    logger.debug("mdns: parser threw on ${pkt.size}-byte packet (${t::class.simpleName}: ${t.message})")
                    emptyList()
                }
                if (parsed.isEmpty() && pkt.size >= 12) {
                    logger.debug(
                        "mdns: unparseable packet (${pkt.size} bytes, head=${
                            pkt.take(16).joinToString(",") { (it.toInt() and 0xFF).toString(16) }
                        })"
                    )
                }
                seenMutex.withLock { parsed.forEach { seen["${it.instanceName}.${it.serviceType}"] = it to now } }
            } else {
                // receive 返回 null 时 delay —— 否则 FakeMdnsSocket 立即返回 null 会自旋，runTest 卡死
                delay(RECEIVE_TIMEOUT_MS.toLong())
            }
        }
    }

    /** 对已知设备 IP 逐个 legacy unicast 探测（每 10s 一轮）。超时静默（DEBUG 级记录）——
     *  设备离线是常态，不是错误。 */
    private suspend fun probeLoop() {
        while (currentCoroutineContext().isActive) {
            val hosts = try { knownHosts() } catch (t: Throwable) {
                logger.debug("mdns: knownHosts threw (${t::class.simpleName}: ${t.message}); skip round")
                emptySet()
            }
            hosts.forEach { host ->
                val pkt = socket.probeUnicast(SERVICE_TLS, host, PROBE_TIMEOUT_MS)
                if (pkt == null) {
                    logger.debug("mdns: unicast probe $host timed out")
                } else {
                    val parsed = try {
                        withContext(io) { MdnsResponseParser.parse(pkt) }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        logger.debug("mdns: parser threw on unicast ${pkt.size}-byte packet from $host (${t.message})")
                        emptyList()
                    }
                    if (parsed.isEmpty()) {
                        logger.debug("mdns: unicast probe $host got unparseable ${pkt.size}-byte packet")
                    } else {
                        val now = clock()
                        seenMutex.withLock {
                            parsed.forEach { seen["${it.instanceName}.${it.serviceType}"] = it to now }
                        }
                        logger.debug("mdns: unicast probe $host -> ${parsed.size} service(s): " +
                            parsed.joinToString { "${it.instanceName} ${it.host}:${it.port}" })
                    }
                }
            }
            delay(QUERY_INTERVAL_MS)
        }
    }

    companion object {
        const val SERVICE_TLS = "_adb-tls-connect._tcp"
        const val SERVICE_LEGACY = "_adb._tcp"
        private const val QUERY_INTERVAL_MS = 10_000L
        private const val RECEIVE_TIMEOUT_MS = 1_000
        private const val PROBE_TIMEOUT_MS = 600
        private const val EXPIRY_MS = 30_000L
    }
}

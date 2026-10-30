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
import kotlinx.coroutines.withContext

/** 周期 mDNS 探测（spec §4.1）：每 10s 查询 _adb-tls-connect._tcp 与 _adb._tcp，持续收包，
 *  服务 30s 未刷新即过期。独立于 adb 的 mdns 后端（实证它在本机是坏的，spec §2）。
 *
 *  @param io 解析报文所用的 dispatcher（真机报文最大 ~9KB，避免占主线程）；
 *            测试传 [kotlinx.coroutines.Dispatchers.Unconfined]。 */
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
    private val seen = LinkedHashMap<String, Pair<MdnsService, Long>>() // key: "instanceName.serviceType"

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel(); job = null
    }

    private suspend fun runLoop() {
        var nextQueryAt = 0L
        while (currentCoroutineContext().isActive) {
            val now = clock()
            // 每轮开头重算（用最新 now）：处理 30s 过期（StateFlow 合并相等值）
            val alive = seen.filterValues { now - it.second <= EXPIRY_MS }
                .mapValues { it.value.first }
                .values
                .filter { it.port > 0 }
                .sortedBy { it.instanceName }
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
                parsed.forEach { svc ->
                    seen["${svc.instanceName}.${svc.serviceType}"] = svc to now
                }
            } else {
                // receive 返回 null 时 delay —— 否则 FakeMdnsSocket 立即返回 null 会自旋，runTest 卡死
                delay(RECEIVE_TIMEOUT_MS.toLong())
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

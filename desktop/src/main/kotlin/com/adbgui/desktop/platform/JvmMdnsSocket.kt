package com.adbgui.desktop.platform

import com.adbgui.core.mdns.MdnsQueryPacket
import com.adbgui.core.mdns.MdnsSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** 真实 mDNS socket：绑 0.0.0.0:5353（ReuseAddress 与 adb server 共存），逐接口 join 224.0.0.251，
 *  查询按接口逐个发。绑定失败抛 IllegalStateException（调用方决定降级为不扫描）。 */
class JvmMdnsSocket : MdnsSocket {
    private val group: InetAddress = InetAddress.getByName("224.0.0.251")
    private val socket: MulticastSocket = MulticastSocket(null).apply {
        // 关键：reuseAddress 必须在 bind 之前，否则 adb server 占着 5353 时绑定失败
        reuseAddress = true
        bind(InetSocketAddress(5353))
    }
    private val interfaces: List<NetworkInterface> =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && it.supportsMulticast() && !it.isLoopback }
            .filter { nif -> nif.inetAddresses.toList().any { it is Inet4Address && !it.isLoopbackAddress } }

    init {
        interfaces.forEach { nif ->
            nif.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress }.forEach { addr ->
                runCatching {
                    socket.joinGroup(InetSocketAddress(group, 5353), NetworkInterface.getByInetAddress(addr))
                }
            }
        }
    }

    override suspend fun sendQuery(serviceType: String) = withContext(Dispatchers.IO) {
        // QNAME label 编码在 core（MdnsQueryPacket，有单测）；这里只管按接口发送
        val q = MdnsQueryPacket.build(serviceType)
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
            // mDNS with EDNS0 can reach ~9000 bytes; 9216 covers it with headroom.
            val buf = ByteArray(9216)
            val p = DatagramPacket(buf, buf.size)
            try {
                socket.receive(p)
                buf.copyOf(p.length)
            } catch (_: SocketTimeoutException) {
                null
            }
        }
    }

    /** legacy unicast 查询（RFC 6762 §18.1/§6.7）：临时源端口 + 非零 QID 直发 host:5353，
     *  responder 单播应答回该端口。跨子网组播不可达时的发现通道（2026-11-02 实测）。 */
    override suspend fun probeUnicast(serviceType: String, host: String, timeoutMs: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            runInterruptible {
                DatagramSocket().use { probe ->
                    probe.soTimeout = timeoutMs
                    val q = MdnsQueryPacket.build(serviceType, qid = 0x5ADB)
                    probe.send(DatagramPacket(q, q.size, InetAddress.getByName(host), 5353))
                    val buf = ByteArray(9216)
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        probe.receive(p)
                        buf.copyOf(p.length)
                    } catch (_: SocketTimeoutException) {
                        null
                    }
                }
            }
        }

    override fun close() { socket.close() }
}

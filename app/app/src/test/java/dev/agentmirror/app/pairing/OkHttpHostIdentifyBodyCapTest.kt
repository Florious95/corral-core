package dev.agentmirror.app.pairing

import dev.agentmirror.app.tsnet.ConnectionPath
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unfixed OkHttpHostHttpTransport used source.readByteArray(1025), which throws
 * EOFException on the real identify JSON (≪ 1025 bytes). The App then saw http_code=599
 * and never Proven/create. This test talks to a real loop of OkHttp + a tiny HTTP
 * server on a literal LAN IPv4 (loopback is rejected by HostRouter).
 */
class OkHttpHostIdentifyBodyCapTest {
    @Test
    fun shortIdentifyJsonIsHttp200NotEof599() {
        val ip = lanIpv4()
        val server = ServerSocket(0, 1, InetAddress.getByName(ip))
        val port = server.localPort
        val body =
            "{\"v\":1,\"host_id\":\"host-1234\",\"name\":\"box\",\"bound\":\"$ip:$port\"," +
                "\"mac\":\"${"ab".repeat(32)}\"}"
        val exec = Executors.newSingleThreadExecutor()
        exec.execute {
            server.use { listener ->
                listener.accept().use { sock ->
                    val inBuf = sock.getInputStream()
                    val buf = ByteArray(4096)
                    var n = 0
                    while (n < buf.size) {
                        val r = inBuf.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                        val soFar = buf.decodeToString(0, n)
                        if (soFar.contains("\r\n\r\n")) break
                    }
                    val raw = body.toByteArray(Charsets.UTF_8)
                    val resp =
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${raw.size}\r\nConnection: close\r\n\r\n"
                    sock.getOutputStream().write(resp.toByteArray(Charsets.UTF_8) + raw)
                }
            }
        }
        try {
            val transport = OkHttpHostHttpTransport()
            val endpoint = HostEndpoint(ip, port, ConnectionPath.LAN, HostEndpointSource.SCANNED_PRIMARY)
            val resp = transport.identify(
                endpoint,
                IdentifyRequest("host-1234", "00112233445566778899aabbccddeeff", ip),
            )
            assertEquals("short identify body must not become 599/EOF", 200, resp.code)
            assertTrue(resp.body.contains("host_id"))
            assertFalse(resp.body.length > 1024)
        } finally {
            runCatching { server.close() }
            exec.shutdownNow()
            exec.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private fun lanIpv4(): String {
        val nics = NetworkInterface.getNetworkInterfaces() ?: error("no nics")
        for (nic in nics) {
            val addrs = nic.inetAddresses ?: continue
            for (addr in addrs) {
                if (addr is Inet4Address && HostRouter.isLiteralIpv4(addr.hostAddress)) {
                    return addr.hostAddress
                }
            }
        }
        error("no literal LAN IPv4 for HostEndpoint")
    }
}

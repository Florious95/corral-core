package dev.agentmirror.app.pairing

import dev.agentmirror.app.tsnet.ConnectionPath
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unfixed OkHttpHostHttpTransport used source.readByteArray(1025), which throws
 * EOFException on the real identify JSON (≪ 1025 bytes). The App then saw http_code=599
 * and never Proven/create. The proxy-backed loopback fixture keeps the logical destination a
 * literal LAN IPv4 while avoiding platform-specific LAN self-connect behavior in JVM tests.
 */
class OkHttpHostIdentifyBodyCapTest {
    @Test
    fun shortIdentifyJsonIsHttp200NotEof599() {
        val ip = lanIpv4()
        val server = MockWebServer()
        server.start()
        val port = server.port
        val body =
            "{\"v\":1,\"host_id\":\"host-1234\",\"name\":\"box\",\"bound\":\"$ip:$port\"," +
                "\"mac\":\"${"ab".repeat(32)}\"}"
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        try {
            val transport = OkHttpHostHttpTransport(
                OkHttpClient.Builder()
                    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(server.hostName, server.port)))
                    .build(),
            )
            val endpoint = HostEndpoint(ip, port, ConnectionPath.LAN, HostEndpointSource.SCANNED_PRIMARY)
            val resp = transport.identify(
                endpoint,
                IdentifyRequest("host-1234", "00112233445566778899aabbccddeeff", ip),
            )
            assertEquals("short identify body must not become 599/EOF", 200, resp.code)
            assertTrue(resp.body.contains("host_id"))
            assertFalse(resp.body.length > 1024)
        } finally {
            server.shutdown()
        }
    }

    private fun lanIpv4(): String {
        val nics = NetworkInterface.getNetworkInterfaces() ?: error("no nics")
        for (nic in nics) {
            val addrs = nic.inetAddresses ?: continue
            for (addr in addrs) {
                if (addr is Inet4Address && HostRouter.classify(addr.hostAddress) == ConnectionPath.LAN) {
                    return addr.hostAddress
                }
            }
        }
        error("no literal LAN IPv4 for HostEndpoint")
    }
}

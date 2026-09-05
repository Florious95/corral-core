package dev.agentmirror.app.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.agentmirror.app.tsnet.ConnectionPath

class HostIdentifyClientTest {
    private val endpoint = HostEndpoint("192.0.2.10", 9900, ConnectionPath.LAN, HostEndpointSource.SCANNED_PRIMARY)
    private val token = "host-token"
    private val clientHolder = arrayOfNulls<HostIdentifyClient>(1)

    @Test
    fun identifySendsNoTokenAndAcceptsOnlyBoundMac() {
        val transport = object : HostHttpTransport {
            override fun whoami(endpoint: HostEndpoint) = HostHttpResponse(200, "{\"host_id\":\"host-1234\",\"name\":\"box\",\"port\":9900}")
            override fun identify(endpoint: HostEndpoint, request: IdentifyRequest): HostHttpResponse {
                val mac = clientHolder[0]!!.mac(token, "host-1234", request.nonceHex, endpoint.address, endpoint.port)
                return HostHttpResponse(200, "{\"v\":1,\"host_id\":\"host-1234\",\"name\":\"box\",\"bound\":\"192.0.2.10:9900\",\"mac\":\"$mac\"}")
            }
        }
        val client = HostIdentifyClient(transport) { ByteArray(16) { it.toByte() } }
        clientHolder[0] = client
        val result = client.identify(endpoint, "host-1234", token)
        assertTrue(result is HostIdentifyResult.Proven)
    }

    @Test
    fun redirectsAndDnsNamesFailClosedBeforeTransport() {
        var calls = 0
        val client = HostIdentifyClient(object : HostHttpTransport {
            override fun whoami(endpoint: HostEndpoint): HostHttpResponse { calls++; return HostHttpResponse(307, location = "http://192.0.2.11") }
            override fun identify(endpoint: HostEndpoint, request: IdentifyRequest): HostHttpResponse { calls++; return HostHttpResponse(307, location = "http://192.0.2.11") }
        })
        val endpoint = HostEndpoint("192.0.2.10", 9900, ConnectionPath.LAN, HostEndpointSource.SCANNED_PRIMARY)
        val result = client.identify(endpoint, null, token)
        assertEquals(1, calls)
        assertTrue(result is HostIdentifyResult.Rejected)
    }

    @Test
    fun legacy404IsLimitedToScannedPrimary() {
        val client = HostIdentifyClient(object : HostHttpTransport {
            override fun whoami(endpoint: HostEndpoint) = HostHttpResponse(404)
            override fun identify(endpoint: HostEndpoint, request: IdentifyRequest) = HostHttpResponse(404)
        }) { ByteArray(16) }
        val allowed = client.identify(endpoint, null, "token", "ws://192.0.2.10:9900/ws")
        assertTrue(allowed is HostIdentifyResult.Legacy404)
        val discovered = client.identify(endpoint.copy(source = HostEndpointSource.LAST_GOOD), null, "token", "ws://192.0.2.10:9900/ws")
        assertTrue(discovered is HostIdentifyResult.Rejected)
    }
}

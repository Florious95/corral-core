package dev.agentmirror.app.pairing

import dev.agentmirror.app.service.NoopTransportFactory
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.tsnet.TsPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.Executor

/** End-to-end discovery seam: enumerate, prove whoami, then create one token-bound WS attempt. */
class HostDiscoveryFlowTest {
    private class Store : PairingConfigStore {
        var saved: PairingConfig? = null
        override fun load(): PairingConfig? = saved
        override fun save(config: PairingConfig) { saved = config }
        override fun clear() { saved = null }
    }

    @Test
    fun tsPeerIsEnumeratedAndUnverifiedRowsNeverCreateWs() {
        val token = "host-token"
        val endpoint = HostEndpoint("100.101.2.3", 9911, ConnectionPath.TAILNET, HostEndpointSource.PEER)
        val requests = mutableListOf<IdentifyRequest>()
        lateinit var client: HostIdentifyClient
        val transport = object : HostHttpTransport {
            override fun whoami(endpoint: HostEndpoint) =
                HostHttpResponse(200, "{\"host_id\":\"host-1234\",\"name\":\"box\",\"port\":9911}")

            override fun identify(endpoint: HostEndpoint, request: IdentifyRequest): HostHttpResponse {
                requests += request
                val mac = client.mac(token, "host-1234", request.nonceHex, endpoint.address, endpoint.port)
                return HostHttpResponse(
                    200,
                    "{\"v\":1,\"host_id\":\"host-1234\",\"name\":\"box\",\"bound\":\"${endpoint.authority}\",\"mac\":\"$mac\"}",
                )
            }
        }
        client = HostIdentifyClient(transport) { ByteArray(16) { it.toByte() } }
        var wsCreations = 0
        val vm = PairingViewModel(
            configStore = Store(),
            connectionFactory = { cfg ->
                wsCreations++
                dev.agentmirror.app.conn.ConnectionManager(cfg, NoopTransportFactory)
            },
            identifyClient = client,
            discoveryExecutor = Executor { it.run() },
        )

        vm.discoverHosts(listOf(TsPeer("peer-1", true, listOf("100.101.2.3"), "peer")))

        assertEquals(1, vm.discoveredHosts.size)
        assertEquals("host-1234", vm.discoveredHosts.single().hostId)
        assertEquals(endpoint.authority, vm.discoveredHosts.single().endpoints.single().authority)
        assertEquals("TS discovery must only prove a row, not open a socket", 0, wsCreations)

        vm.selectHost("host-1234")
        vm.hostToken = token
        vm.submitHostToken()

        assertEquals(1, requests.size)
        assertEquals("host-1234", requests.single().hostId)
        assertEquals(endpoint.address, requests.single().destIp)
        assertEquals("identity proof precedes exactly one WS attempt", 1, wsCreations)
        assertNotNull(vm.pairingStatus)
    }
}

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
    fun lanDiscoveryProbesEmulatorGatewayWithoutManualIp() {
        val endpoint = HostEndpoint("10.0.2.2", 9900, ConnectionPath.LAN, HostEndpointSource.SCANNED_PRIMARY)
        val transport = object : HostHttpTransport {
            override fun whoami(actual: HostEndpoint): HostHttpResponse {
                assertEquals(endpoint.authority, actual.authority)
                return HostHttpResponse(
                    200,
                    "{\"host_id\":\"gateway-host-1234\",\"name\":\"MacBook-Pro.local\",\"port\":9900," +
                        "\"addresses\":[\"192.168.31.116\"]}",
                )
            }

            override fun identify(endpoint: HostEndpoint, request: IdentifyRequest) = HostHttpResponse(500)
        }
        val vm = PairingViewModel(
            configStore = Store(),
            connectionFactory = { cfg -> dev.agentmirror.app.conn.ConnectionManager(cfg, NoopTransportFactory) },
            identifyClient = HostIdentifyClient(transport),
            discoveryExecutor = Executor { it.run() },
            localProbeTargets = { listOf(endpoint) },
        )

        vm.beginLanDiscovery()

        assertEquals("MacBook-Pro.local", vm.discoveredHosts.single().name)
        assertEquals(
            listOf("10.0.2.2:9900", "192.168.31.116:9900"),
            vm.discoveredHosts.single().endpoints.map { it.authority },
        )
        assertEquals(
            "192.168.31.116:9900",
            HostRouter.prioritize(vm.discoveredHosts.single().endpoints).first().authority,
        )
    }

    @Test
    fun whoamiAdvertisedAddressesReplaceNatAliasForIdentityAndKeepTailnet() {
        val alias = HostEndpoint("10.0.2.2", 9900, ConnectionPath.LAN, HostEndpointSource.SCANNED_PRIMARY)
        val transport = object : HostHttpTransport {
            override fun whoami(endpoint: HostEndpoint) = HostHttpResponse(
                200,
                "{\"host_id\":\"host-1234\",\"name\":\"MacBook-Pro.local\",\"port\":9900," +
                    "\"addresses\":[\"192.168.31.116\",\"100.75.207.88\",\"192.168.31.116\"]}",
            )

            override fun identify(endpoint: HostEndpoint, request: IdentifyRequest) = HostHttpResponse(500)
        }
        val candidate = HostIdentifyClient(transport).whoami(alias) ?: error("whoami candidate missing")

        assertEquals(
            listOf("10.0.2.2:9900", "192.168.31.116:9900", "100.75.207.88:9900"),
            candidate.endpoints.map { it.authority },
        )
        assertEquals("100.75.207.88:9900", HostRouter.prioritize(candidate.endpoints).first().authority)
    }

    @Test
    fun hostTokenFallsBackToLanWhenTailnetIdentityIsUnavailable() {
        val token = "host-token"
        val hostId = "host-1234"
        val ts = HostEndpoint("100.101.2.3", 9900, ConnectionPath.TAILNET, HostEndpointSource.HOST_RECORD)
        val lan = HostEndpoint("192.168.31.116", 9900, ConnectionPath.LAN, HostEndpointSource.HOST_RECORD)
        val requests = mutableListOf<HostEndpoint>()
        val verifier = object : HostIdentityVerifier {
            override fun whoami(endpoint: HostEndpoint): HostCandidate? = null

            override fun identify(
                endpoint: HostEndpoint,
                hostId: String?,
                token: String,
                legacyUrl: String?,
            ): HostIdentifyResult {
                requests += endpoint
                return if (endpoint.authority == ts.authority) {
                    HostIdentifyResult.Rejected("identify unavailable")
                } else {
                    HostIdentifyResult.Proven(
                        HostIdentity(hostId.orEmpty(), "MacBook-Pro.local", endpoint, endpoint.authority),
                    )
                }
            }
        }
        val vm = PairingViewModel(
            configStore = Store(),
            connectionFactory = { cfg -> dev.agentmirror.app.conn.ConnectionManager(cfg, NoopTransportFactory) },
            identifyClient = verifier,
            discoveryExecutor = Executor { it.run() },
        )
        vm.addDiscoveredHost(HostCandidate(hostId, "MacBook-Pro.local", listOf(lan, ts)))
        assertEquals(listOf(ts.authority, lan.authority), HostRouter.prioritize(vm.discoveredHosts.single().endpoints).map { it.authority })
        vm.selectHost(hostId)
        vm.hostToken = token
        vm.submitHostToken()

        assertEquals(listOf(ts.authority, lan.authority), requests.map { it.authority })
    }

    @Test
    fun lanDiscoveryUpgradesNsdPlaceholderWithoutTsToken() {
        val endpoint = HostEndpoint("192.0.2.3", 9900, ConnectionPath.LAN, HostEndpointSource.NSD)
        val transport = object : HostHttpTransport {
            override fun whoami(endpoint: HostEndpoint) = HostHttpResponse(
                200,
                "{\"host_id\":\"host-1234\",\"name\":\"MacBook Pro\",\"port\":9900}",
            )

            override fun identify(endpoint: HostEndpoint, request: IdentifyRequest) = HostHttpResponse(500)
        }
        val vm = PairingViewModel(
            configStore = Store(),
            connectionFactory = { cfg -> dev.agentmirror.app.conn.ConnectionManager(cfg, NoopTransportFactory) },
            identifyClient = HostIdentifyClient(transport) { ByteArray(16) },
            discoveryExecutor = Executor { it.run() },
        )

        vm.addDiscoveredHost(HostCandidate("host-1234", "host-1234", listOf(endpoint)))

        assertEquals("MacBook Pro", vm.discoveredHosts.single().name)
        assertEquals(endpoint.authority, vm.discoveredHosts.single().endpoints.single().authority)
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

package dev.agentmirror.app.pairing

import dev.agentmirror.app.tsnet.ConnectionPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRouterTest {
    @Test
    fun tsOnlyUnknownPortUses9900WithoutPortScan() {
        val peers = listOf(
            dev.agentmirror.app.tsnet.TsPeer("peer-1", true, listOf("100.101.2.3", "not-an-ip"), "box"),
            dev.agentmirror.app.tsnet.TsPeer("peer-2", false, listOf("100.101.2.4"), "other"),
        )
        val targets = HostRouter.peerTargets(peers)
        assertEquals(listOf("100.101.2.3:9900", "100.101.2.4:9900"), targets.map { it.authority })
        assertTrue(targets.all { it.path == ConnectionPath.TAILNET })
    }

    @Test
    fun sameHostAddressesMergeAndNameDoesNotIdentify() {
        val lan = HostEndpoint("192.0.2.2", 9900, ConnectionPath.LAN, HostEndpointSource.NSD)
        val ts = HostEndpoint("100.101.2.2", 9900, ConnectionPath.TAILNET, HostEndpointSource.PEER)
        val merged = HostRouter.merge(
            listOf(
                HostCandidate("host-1234", "display-a", listOf(lan)),
                HostCandidate("host-1234", "display-b", listOf(ts)),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals(2, merged.single().endpoints.size)
        assertEquals("display-a", merged.single().name)
    }

    @Test
    fun neverAcceptsDnsOrLoopbackAsDialTargets() {
        assertFalse(HostRouter.isLiteralIpv4("server.example"))
        assertFalse(HostRouter.isLiteralIpv4("127.0.0.1"))
        assertEquals(ConnectionPath.TAILNET, HostRouter.classify("100.101.2.3"))
    }
}

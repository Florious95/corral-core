package dev.agentmirror.app.pairing

import dev.agentmirror.app.tsnet.ConnectionPath
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Test

class HostDialCoordinatorTest {
    @Test
    fun emitsOneProvenTargetAtATimeAndAdvancesAfterFailure() {
        val token = "host-token"
        val hostId = "host-1234"
        lateinit var identifyClient: HostIdentifyClient
        val transport = object : HostHttpTransport {
            override fun whoami(endpoint: HostEndpoint): HostHttpResponse = HostHttpResponse(404)

            override fun identify(endpoint: HostEndpoint, request: IdentifyRequest): HostHttpResponse {
                val message = "agentmirror-identify-v1\u001f$hostId\u001f${request.nonceHex}" +
                    "\u001f${endpoint.address}\u001f${endpoint.port}"
                val mac = Mac.getInstance("HmacSHA256").apply {
                    init(SecretKeySpec(token.toByteArray(), "HmacSHA256"))
                }.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
                return HostHttpResponse(
                    code = 200,
                    body = "{\"host_id\":\"$hostId\",\"name\":\"host\",\"bound\":\"${endpoint.authority}\",\"mac\":\"$mac\"}",
                )
            }
        }
        identifyClient = HostIdentifyClient(transport) { ByteArray(16) { 7 } }
        val scheduler = ScheduledThreadPoolExecutor(1)
        val targets = mutableListOf<String>()
        val coordinator = HostDialCoordinator(
            endpointSource = {
                listOf(
                    HostEndpoint("100.101.2.3", 9900, ConnectionPath.TAILNET, HostEndpointSource.PEER),
                    HostEndpoint("192.0.2.3", 9900, ConnectionPath.LAN, HostEndpointSource.NSD),
                )
            },
            hostId = hostId,
            token = token,
            identifyClient = identifyClient,
            executor = Executor { it.run() },
            scheduler = scheduler,
        )
        coordinator.begin(
            generation = 1,
            onTarget = { target ->
                targets += target.url
                if (targets.size == 1) coordinator.onTargetFailed(1, target.url, "closed")
            },
            onExhausted = {},
        )

        assertEquals(
            listOf("ws://100.101.2.3:9900/ws", "ws://192.0.2.3:9900/ws"),
            targets,
        )
        coordinator.cancel(1)
        scheduler.shutdownNow()
    }
}

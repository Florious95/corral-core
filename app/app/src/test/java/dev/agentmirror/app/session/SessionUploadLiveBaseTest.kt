package dev.agentmirror.app.session

import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.service.NoopTransportFactory
import org.junit.Assert.assertEquals
import org.junit.Test

/** A single session must resolve upload routing from the endpoint currently marked live. */
class SessionUploadLiveBaseTest {
    @Test
    fun sameSessionUploadFollowsLanToTailnetEndpointSwitch() {
        var liveBase = "http://192.168.1.20:9900"
        val bases = mutableListOf<String>()
        val uploader = object : AttachmentUploader {
            override fun upload(baseUrl: String, attachment: Attachment): UploadOutcome {
                bases += baseUrl
                return UploadOutcome.Success("/host/uploads/${attachment.name}")
            }
        }
        val manager = ConnectionManager(
            ConnectionConfig("ws://192.168.1.20:9900/ws", "token"),
            NoopTransportFactory,
        )
        val vm = SessionViewModel(
            manager = manager,
            uploader = uploader,
            baseUrl = "http://192.168.1.20:9900",
            ref = "socket\u001fpane",
            initialRows = 24,
            initialCols = 80,
            uploadToken = "token",
            liveBaseUrl = { liveBase },
        )

        vm.uploadAttachment(Attachment("lan.png", "image/png", byteArrayOf(1)))
        liveBase = "http://100.101.2.3:9900"
        vm.uploadAttachment(Attachment("tailnet.png", "image/png", byteArrayOf(2)))

        assertEquals(
            listOf("http://192.168.1.20:9900", "http://100.101.2.3:9900"),
            bases,
        )
    }
}

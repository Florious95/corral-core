package dev.agentmirror.app.session

import dev.agentmirror.app.conn.TransportListener
import dev.agentmirror.app.conn.WebSocketTransport

/** Test-only transport seam capturing real ConnectionManager writes. */
class SessionUiTestTransport : WebSocketTransport {
    private var listener: TransportListener? = null
    override var isOpen: Boolean = false
        private set
    val sentText = mutableListOf<String>()
    val sentBinary = mutableListOf<ByteArray>()
    override fun start(listener: TransportListener) { this.listener = listener; isOpen = true; listener.onOpen() }
    override fun sendText(text: String): Boolean { if (!isOpen) return false; sentText += text; return true }
    override fun sendBinary(bytes: ByteArray): Boolean { if (!isOpen) return false; sentBinary += bytes; return true }
    override fun close(reason: String) { isOpen = false; listener?.onClosed(1000, reason) }
}

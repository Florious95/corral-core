package dev.agentmirror.app.session

/** Test-only recorder for typed callback/protocol operands; no production transport changes. */
class SessionUiTestTransport {
    val actions = mutableListOf<String>()
    fun record(action: String) { actions += action }
}

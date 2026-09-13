package dev.agentmirror.app.workspace

import dev.agentmirror.app.conn.FrameCodec
import org.junit.Assert.assertEquals
import org.junit.Test

/** Real accepted-service frames: nullable session_name must not discard workspace/listing data. */
class IndependentAcceptedListingWireTest {
    @Test
    fun listingWithNullNativeMetadataReachesWorkspaceUi() {
        val vm = WorkspaceViewModel(initialConnection = ConnectionUi.READY)
        vm.onFrame(FrameCodec.decode(frame("listing", "")))
        assertEquals(listOf("A", "B"), vm.uiState.value.workspaces.map { it.cwd.substringAfterLast('/') })
        assertEquals(listOf(1, 1), vm.uiState.value.workspaces.map { it.sessionCount })
    }

    @Test
    fun level2WithNullNativeMetadataKeepsServerName() {
        val workspace = "/runtime/A"
        val vm = WorkspaceViewModel(initialConnection = ConnectionUi.READY)
        vm.enterLevel2(workspace)
        vm.onFrame(FrameCodec.decode(frame("level2_frame", workspace)))
        assertEquals("TITLE_A | A", vm.level2.value.sessions.single().name)
    }

    private fun frame(type: String, workspace: String): String = when (type) {
        "listing" -> """
            {"v":1,"type":"listing","payload":{"req_id":1,"seq":2,"workspaces":[
              {"cwd":"/runtime/A","session_count":1,"sessions":[${session("A")}]},
              {"cwd":"/runtime/B","session_count":1,"sessions":[${session("B")}]}
            ]}}
        """.trimIndent()
        else -> """
            {"v":1,"type":"level2_frame","payload":{"workspace":"$workspace","seq":3,"sessions":[${session("A")}]}}
        """.trimIndent()
    }

    private fun session(project: String): String = """
        {"ref":"/tmp/tmux/default\u001f%0","name":"TITLE_$project | $project",
         "window_name":"WINDOW_$project","window_index":"0","cwd":"/runtime/$project",
         "title":"TITLE_$project | $project","provider":"codex","activity":"idle",
         "session_name":null,"health":"unknown","status":"idle","rows":35,"cols":100}
    """.trimIndent().replace("\n", "")
}

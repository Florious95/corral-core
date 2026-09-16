package dev.agentmirror.app.session

import android.content.Context
import androidx.compose.ui.text.input.TextFieldValue
import dev.agentmirror.app.conn.ConnectionConfig
import dev.agentmirror.app.conn.ConnectionManager
import dev.agentmirror.app.conn.FakeClock
import dev.agentmirror.app.conn.FakeWebSocketTransport
import dev.agentmirror.app.conn.FrameCodec
import dev.agentmirror.app.conn.InputFrame
import dev.agentmirror.app.conn.TransportFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Independent protocol oracle for the setting's two input modes.
 *
 * Reflection is used only at the feature seam so this test also compiles against
 * the pre-feature baseline; the assertions below are on persisted values and
 * emitted protocol frames, not implementation internals.
 */
@RunWith(RobolectricTestRunner::class)
class InputSyncBehaviorIndependentRedTest {

    @Test
    fun inputSyncStoreDefaultsEnabledAndPersistsAcrossInstances() {
        val context = RuntimeEnvironment.getApplication()
        val storeClass = findStoreClass()
        assertNotNull(
            "InputSyncStore implementation is required for the settings contract",
            storeClass,
        )
        val ctor = storeClass!!.constructors.firstOrNull { it.parameterTypes.contentEquals(arrayOf(Context::class.java)) }
            ?: error("SharedPreferencesInputSyncStore(Context) constructor is required")
        val first = ctor.newInstance(context)
        val load = storeClass.getMethod("load")
        val save = storeClass.getMethod("save", Boolean::class.javaPrimitiveType)

        // Existing installs preserve the old live-sync behavior by default.
        context.getSharedPreferences("input_sync", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue("default input sync must be enabled", load.invoke(first) as Boolean)

        save.invoke(first, false)
        val second = ctor.newInstance(context)
        assertFalse("disabled input sync must survive recreation", load.invoke(second) as Boolean)

        save.invoke(second, true)
        assertTrue("re-enabling input sync must persist", load.invoke(ctor.newInstance(context)) as Boolean)
    }

    @Test
    fun disabledSyncDoesNotEmitDraftFrameAndSendDraftEmitsWholeTextFrame() {
        val h = Harness(inputSyncEnabled = false)
        val beforeTyping = h.inputFrames().size
        h.vm.onPassthroughInput(TextFieldValue(""), TextFieldValue("echo 独立测试"))

        // Draft edits stay local: no text, key, or backspace input frame is allowed.
        assertEquals(beforeTyping, h.inputFrames().size)

        val send = SessionViewModel::class.java.methods.firstOrNull {
            it.name == "sendDraft" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
        } ?: error("sendDraft(String) is required for disabled-sync submission")
        send.invoke(h.vm, "echo 独立测试")
        val submitted = h.inputFrames().drop(beforeTyping)
        assertEquals("one complete text submission is expected", 1, submitted.size)
        assertEquals("echo 独立测试", submitted.single().text)
        assertTrue("submission must be a text frame, not a key frame", submitted.single().keys.isEmpty())
    }

    private fun findStoreClass(): Class<*>? = listOf(
        "dev.agentmirror.app.session.SharedPreferencesInputSyncStore",
        "dev.agentmirror.app.SharedPreferencesInputSyncStore",
        "dev.agentmirror.app.ui.SharedPreferencesInputSyncStore",
        "dev.agentmirror.app.ui.screens.SharedPreferencesInputSyncStore",
    ).firstNotNullOfOrNull { name -> runCatching { Class.forName(name) }.getOrNull() }

    private class Harness(inputSyncEnabled: Boolean) {
        val clock = FakeClock()
        val transport = FakeWebSocketTransport()
        val manager = ConnectionManager(
            config = ConnectionConfig(url = "ws://host:0/ws", token = "tok"),
            transportFactory = TransportFactory { transport },
            clock = clock,
        )
        val vm: SessionViewModel

        init {
            manager.start()
            transport.deliverText("""{"v":1,"type":"auth_ack","payload":{"ok":true}}""")
            val ctor = SessionViewModel::class.java.declaredConstructors.firstOrNull { constructor ->
                constructor.parameterTypes.any { it == Boolean::class.javaPrimitiveType } &&
                    constructor.parameterTypes.none { it.name.contains("DefaultConstructorMarker") }
            } ?: error("SessionViewModel must expose inputSyncEnabled")
            ctor.isAccessible = true
            val args = ctor.parameterTypes.mapIndexed { index, type ->
                when {
                    index == 0 -> manager
                    type == AttachmentUploader::class.java -> AttachmentUploader { _, _ -> UploadOutcome.Success("/host/img.png") }
                    index == 2 -> "http://host:0"
                    index == 3 -> "s1"
                    type == Int::class.javaPrimitiveType && index == 4 -> 5
                    type == Int::class.javaPrimitiveType && index == 5 -> 10
                    type == Boolean::class.javaPrimitiveType -> inputSyncEnabled
                    type.name == "kotlin.jvm.functions.Function0" -> ({ "http://host:0" } as kotlin.jvm.functions.Function0<String?>)
                    type == String::class.java -> null
                    else -> null
                }
            }.toTypedArray()
            vm = ctor.newInstance(*args) as SessionViewModel
            manager.setListener(vm)
        }

        fun inputFrames(): List<InputFrame> =
            transport.sentText.mapNotNull { runCatching { FrameCodec.decode(it) }.getOrNull() }
                .filterIsInstance<InputFrame>()
    }
}

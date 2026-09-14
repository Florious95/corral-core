package dev.agentmirror.app.termview

import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TerminalKeyEncoderTest {
    private fun key(code: Int, meta: Int = 0) = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta)
    private fun bytes(code: Int, meta: Int = 0): ByteArray =
        requireNotNull(TerminalKeyEncoder.encode(key(code, meta)))

    @Test
    fun ctrlLetterUsesC0Byte() {
        assertArrayEquals(byteArrayOf(3), bytes(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON))
    }

    @Test
    fun altLetterUsesEscapePrefix() {
        assertArrayEquals(byteArrayOf(0x1b, 'x'.code.toByte()), bytes(KeyEvent.KEYCODE_X, KeyEvent.META_ALT_ON))
    }

    @Test
    fun navigationAndFunctionKeysUseXtermSequences() {
        assertArrayEquals("\u001b[1;5C".toByteArray(), bytes(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_CTRL_ON))
        assertArrayEquals("\u001b[15~".toByteArray(), bytes(KeyEvent.KEYCODE_F5))
        assertArrayEquals("\u001b[3~".toByteArray(), bytes(KeyEvent.KEYCODE_FORWARD_DEL))
        assertArrayEquals("\u001b[Z".toByteArray(), bytes(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON))
    }

    @Test
    fun plainPrintableKeysRemainInComposeEditor() {
        val event = key(KeyEvent.KEYCODE_X)
        assertFalse(TerminalKeyEncoder.shouldIntercept(event))
        assertTrue(TerminalKeyEncoder.encode(event)!!.contentEquals(byteArrayOf('x'.code.toByte())))
    }
}

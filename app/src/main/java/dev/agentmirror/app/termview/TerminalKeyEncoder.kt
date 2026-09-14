/*
 * Android hardware/IME key events to the VT byte stream consumed by a shell or
 * full-screen terminal application. Printable, unmodified keys stay with the
 * Compose editor; terminal-focused views use the same encoder for all keys.
 */
package dev.agentmirror.app.termview

import android.view.KeyEvent

internal object TerminalKeyEncoder {
    private const val ESC = 0x1b

    /** Keys that Compose must keep out of its text editing path. */
    fun shouldIntercept(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return event.isCtrlPressed || event.isAltPressed || event.isMetaPressed ||
            event.keyCode in SPECIAL_KEYS
    }

    fun encode(event: KeyEvent): ByteArray? {
        if (event.action != KeyEvent.ACTION_DOWN) return null
        val keyCode = event.keyCode
        if (keyCode in MODIFIER_KEYS) return null

        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        val shift = event.isShiftPressed
        val modifier = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) +
            (if (ctrl) 4 else 0) + (if (event.isMetaPressed) 8 else 0)

        // C0 controls are the canonical representation of Ctrl+ASCII. Using
        // keyCode first makes this independent of the keyboard's layout.
        if (ctrl && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            return withAlt(byteArrayOf((keyCode - KeyEvent.KEYCODE_A + 1).toByte()), alt)
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> return csiFinal('A', modifier)
            KeyEvent.KEYCODE_DPAD_DOWN -> return csiFinal('B', modifier)
            KeyEvent.KEYCODE_DPAD_RIGHT -> return csiFinal('C', modifier)
            KeyEvent.KEYCODE_DPAD_LEFT -> return csiFinal('D', modifier)
            KeyEvent.KEYCODE_MOVE_HOME -> return csiFinal('H', modifier)
            KeyEvent.KEYCODE_MOVE_END -> return csiFinal('F', modifier)
            KeyEvent.KEYCODE_INSERT -> return tilde(2, modifier)
            KeyEvent.KEYCODE_FORWARD_DEL -> return tilde(3, modifier)
            KeyEvent.KEYCODE_PAGE_UP -> return tilde(5, modifier)
            KeyEvent.KEYCODE_PAGE_DOWN -> return tilde(6, modifier)
            KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4 -> {
                val final = ('P'.code + keyCode - KeyEvent.KEYCODE_F1).toChar()
                return if (modifier == 1) byteArrayOf(ESC.toByte(), final.code.toByte())
                else csiTildeOrFunction(final, modifier)
            }
            in KeyEvent.KEYCODE_F5..KeyEvent.KEYCODE_F12 -> {
                val number = F_KEY_NUMBERS[keyCode - KeyEvent.KEYCODE_F5]
                return tilde(number, modifier)
            }
            KeyEvent.KEYCODE_TAB -> {
                return if (shift && !ctrl && !alt && !event.isMetaPressed) {
                    byteArrayOf(ESC.toByte(), '['.code.toByte(), 'Z'.code.toByte())
                } else {
                    withAlt(byteArrayOf('\t'.code.toByte()), alt)
                }
            }
            KeyEvent.KEYCODE_ESCAPE -> return withAlt(byteArrayOf(ESC.toByte()), alt)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                return withAlt(byteArrayOf('\r'.code.toByte()), alt)
            }
            KeyEvent.KEYCODE_DEL -> return withAlt(byteArrayOf(0x7f), alt)
            KeyEvent.KEYCODE_SPACE -> return printable(event, ctrl, alt)
        }

        return printable(event, ctrl, alt)
    }

    private fun printable(
        event: KeyEvent,
        ctrl: Boolean,
        alt: Boolean,
    ): ByteArray? {
        val meta = event.metaState and (
            KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or
                KeyEvent.META_SHIFT_RIGHT_ON
            )
        val codePoint = event.getUnicodeChar(meta)
        if (codePoint == 0) return null
        if (ctrl && codePoint in 0x40..0x7f) {
            return withAlt(byteArrayOf((codePoint and 0x1f).toByte()), alt)
        }
        return withAlt(
            String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8),
            alt || event.isMetaPressed,
        )
    }

    private fun csiFinal(final: Char, modifier: Int): ByteArray {
        if (modifier == 1) {
            return byteArrayOf(ESC.toByte(), '['.code.toByte(), final.code.toByte())
        }
        return "\u001b[1;${modifier}${final}".toByteArray(Charsets.US_ASCII)
    }

    private fun csiTildeOrFunction(final: Char, modifier: Int): ByteArray =
        "\u001b[1;${modifier}${final}".toByteArray(Charsets.US_ASCII)

    private fun tilde(number: Int, modifier: Int): ByteArray =
        if (modifier == 1) "\u001b[${number}~".toByteArray(Charsets.US_ASCII)
        else "\u001b[${number};${modifier}~".toByteArray(Charsets.US_ASCII)

    private fun withAlt(bytes: ByteArray, alt: Boolean): ByteArray =
        if (!alt) bytes else byteArrayOf(ESC.toByte()) + bytes

    private val MODIFIER_KEYS = setOf(
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
    )

    private val SPECIAL_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
        KeyEvent.KEYCODE_INSERT, KeyEvent.KEYCODE_FORWARD_DEL,
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DEL,
        KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4,
        KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8,
        KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12,
    )

    private val F_KEY_NUMBERS = intArrayOf(15, 17, 18, 19, 20, 21, 23, 24)
}

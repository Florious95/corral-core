package dev.agentmirror.terminal

/**
 * TerminalEmulator is the terminal kernel under src/main/kotlin.
 *
 * 必红格：@contract 缺 @post —— 修复扫描根前这个包根本进不了判据视野（空通过），
 * 修复后 T3-3 必须报「缺 @post」。
 * @contract
 * @pre feed input bytes are non-null
 * @err none
 * @inv internal state remains consistent after every feed
 */
object TerminalEmulator

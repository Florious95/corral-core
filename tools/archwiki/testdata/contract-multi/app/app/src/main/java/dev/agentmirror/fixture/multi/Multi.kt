package dev.agentmirror.fixture.multi

/**
 * FullClient is complete: all four contract tags present.
 * @contract
 * @pre token non-null
 * @post connected
 * @err none
 * @inv session bounded
 */
object FullClient

/**
 * HalfClient is missing @post — must be caught even though FullClient is complete.
 * @contract
 * @pre token non-null
 * @err on bad token
 * @inv session closed on error
 */
object HalfClient

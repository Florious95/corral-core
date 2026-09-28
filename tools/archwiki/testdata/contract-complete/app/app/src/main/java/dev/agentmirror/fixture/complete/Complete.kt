package dev.agentmirror.fixture.complete

/**
 * Session has all four contract tags; @err is explicitly none.
 * @contract
 * @pre host is not null
 * @post state is CONNECTED
 * @err none
 * @inv state is one of the closed set
 */
object Session

/**
 * Store has all four contract tags on a value.
 * @contract
 * @pre key is not null
 * @post value is persisted
 * @err I/O failure
 * @inv key set is bounded
 */
class Store

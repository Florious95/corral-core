package dev.agentmirror.fixture.incomplete

/**
 * Client has a contract but is missing the postcondition tag.
 * @contract
 * @pre token is not null
 * @err throws on bad token
 * @inv session is closed on error
 */
object Client

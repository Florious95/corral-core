/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package dev.agentmirror.app.pairing

import dev.agentmirror.app.tsnet.ConnectionPath

/** A literal IPv4 endpoint; names, IPv6 and DNS answers never cross the dial seam. */
data class HostEndpoint(
    val address: String,
    val port: Int,
    val path: ConnectionPath,
    val source: HostEndpointSource = HostEndpointSource.DEFAULT_PORT,
) {
    init {
        require(HostRouter.isLiteralIpv4(address)) { "host endpoint must use a literal IPv4" }
        require(port in 1..65535) { "host endpoint port out of range" }
    }

    val authority: String get() = "$address:$port"
    val wsUrl: String get() = "ws://$authority/ws"
}

enum class HostEndpointSource {
    HOST_RECORD,
    SCANNED_PRIMARY,
    PERSISTED_LEGACY,
    QR,
    NSD,
    LAST_GOOD,
    PEER,
    DEFAULT_PORT,
}

/** Public discovery result. It is deliberately not an authentication result. */
data class HostCandidate(
    val hostId: String,
    val name: String,
    val endpoints: List<HostEndpoint>,
) {
    init {
        require(HostRouter.isValidHostId(hostId)) { "invalid host id" }
    }
}

/** Internal identity proof returned by /pair/identify. */
data class HostIdentity(
    val hostId: String,
    val name: String,
    val endpoint: HostEndpoint,
    val bound: String,
)

/** Public /pair/whoami response. */
data class HostWhoAmI(
    val hostId: String,
    val name: String,
    val port: Int?,
)

sealed interface HostIdentifyResult {
    data class Proven(val identity: HostIdentity) : HostIdentifyResult
    data class Legacy404(val endpoint: HostEndpoint) : HostIdentifyResult
    data class Rejected(val reason: String) : HostIdentifyResult
}


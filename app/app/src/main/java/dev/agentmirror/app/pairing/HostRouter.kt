/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package dev.agentmirror.app.pairing

import dev.agentmirror.app.tsnet.ConnectionPath
import java.net.URI

/**
 * Discovery and route policy. This class only makes bounded, literal-address plans;
 * it never opens a socket and never treats whoami/TS-Up as proof.
 */
object HostRouter {
    const val DEFAULT_PORT = 9900
    const val MAX_PEER_LINES = 256

    fun isValidHostId(value: String?): Boolean =
        !value.isNullOrBlank() && value.length in 8..64 && value.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
        }

    fun isLiteralIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { it.isNotEmpty() && (it.toIntOrNull() ?: -1) in 0..255 &&
            (it.length == 1 || !it.startsWith('0')) } &&
            value != "0.0.0.0" && value != "127.0.0.1"
    }

    fun isTailnetAddress(value: String): Boolean {
        if (!isLiteralIpv4(value)) return false
        val parts = value.split('.').map(String::toInt)
        return parts[0] == 100 && parts[1] in 64..127
    }

    fun classify(address: String): ConnectionPath? = when {
        !isLiteralIpv4(address) -> null
        isTailnetAddress(address) -> ConnectionPath.TAILNET
        else -> ConnectionPath.LAN
    }

    /** Only trusted metadata may override the protocol default; never probes a port range. */
    fun defaultPort(
        recordPort: Int? = null,
        qrPort: Int? = null,
        nsdPort: Int? = null,
        lastGoodPort: Int? = null,
    ): Int = listOf(recordPort, qrPort, nsdPort, lastGoodPort)
        .firstOrNull { it != null && it in 1..65535 } ?: DEFAULT_PORT

    /**
     * TS-only first discovery: one whoami target per peer, literal IPv4 only, default 9900
     * unless a trusted port source exists. No hostnames, IPv6, CIDR or all-port scans.
     */
    fun peerTargets(
        peers: List<TsPeer>,
        knownPort: Int? = null,
        qrPort: Int? = null,
        nsdPort: Int? = null,
        lastGoodPort: Int? = null,
    ): List<HostEndpoint> {
        val port = defaultPort(knownPort, qrPort, nsdPort, lastGoodPort)
        return peers.asSequence()
            .sortedWith(compareByDescending<TsPeer> { it.online }.thenBy { it.stableId })
            .flatMap { peer ->
                peer.ipv4.asSequence().mapNotNull { ip ->
                    classify(ip)?.let { path ->
                        if (path == ConnectionPath.TAILNET) {
                            HostEndpoint(ip, port, path, HostEndpointSource.PEER)
                        } else null
                    }
                }
            }
            .distinctBy { it.authority }
            .toList()
    }

    /** Merge addresses into one host row; name is display-only and never identity. */
    fun merge(candidates: Iterable<HostCandidate>): List<HostCandidate> {
        val merged = LinkedHashMap<String, HostCandidate>()
        for (candidate in candidates) {
            if (!isValidHostId(candidate.hostId)) continue
            val old = merged[candidate.hostId]
            if (old == null) {
                merged[candidate.hostId] = candidate.copy(
                    endpoints = candidate.endpoints.distinctBy { it.authority },
                )
            } else {
                merged[candidate.hostId] = old.copy(
                    name = old.name.ifBlank { candidate.name },
                    endpoints = (old.endpoints + candidate.endpoints)
                        .distinctBy { it.authority },
                )
            }
        }
        return merged.values.toList()
    }

    /** Build a literal candidate from a legacy QR/last-good URL only as an untrusted hint. */
    fun endpointFromWsUrl(
        raw: String,
        source: HostEndpointSource,
        fallbackPort: Int = DEFAULT_PORT,
    ): HostEndpoint? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val address = uri.host ?: return null
        val path = classify(address) ?: return null
        val port = when {
            uri.port in 1..65535 -> uri.port
            fallbackPort in 1..65535 -> fallbackPort
            else -> DEFAULT_PORT
        }
        return HostEndpoint(address, port, path, source)
    }

    /** Trusted-source order from DESIGN §2.2; path priority is applied only after identify. */
    fun prioritize(endpoints: Iterable<HostEndpoint>): List<HostEndpoint> = endpoints
        .filter { isLiteralIpv4(it.address) }
        .sortedWith(compareBy<HostEndpoint> { it.source.ordinal }
            .thenBy { if (it.path == ConnectionPath.TAILNET) 0 else 1 }
            .thenBy { it.address }
            .thenBy { it.port })
        .distinctBy { it.authority }
}

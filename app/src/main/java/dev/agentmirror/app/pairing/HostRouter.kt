/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package dev.agentmirror.app.pairing

import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.tsnet.TsPeer
import java.net.URI

/**
 * Discovery and route policy. This class only makes bounded, literal-address plans;
 * it never opens a socket and never treats whoami/TS-Up as proof.
 */
object HostRouter {
    const val DEFAULT_PORT = 9900
    const val MAX_PEER_LINES = 256
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val PLACEHOLDER_NAMES = setOf(
        "unknown",
        "unknown host",
        "unnamed",
        "未命名主机",
        "未知主机",
        "主机",
        "主机名",
        "电脑",
        "电脑名",
    )

    fun isValidHostId(value: String?): Boolean =
        !value.isNullOrBlank() && value.length in 8..64 && value.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
        }

    fun isLiteralIpv4(value: String): Boolean {
        val text = value.trim()
        if (text != value) return false
        val parts = text.split('.')
        if (parts.size != 4) return false
        if (!parts.all { it.isNotEmpty() && (it.toIntOrNull() ?: -1) in 0..255 &&
                (it.length == 1 || !it.startsWith('0'))
        }) return false
        val octets = parts.map(String::toInt)
        val first = octets[0]
        val second = octets[1]
        return octets.any { it != 0 } &&
            first != 127 &&
            !(first == 169 && second == 254) &&
            !(first == 198 && second in 18..19) &&
            first !in 224..239 &&
            text != "255.255.255.255"
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

    /**
     * A discovery name is display-only. DNS-SD commonly reports the random host ID as its
     * instance name, so a later whoami/TS name must be allowed to upgrade that placeholder.
     */
    fun isPlaceholderName(name: String?, hostId: String? = null): Boolean {
        val value = name?.trim().orEmpty()
        if (value.isEmpty()) return true
        if (!hostId.isNullOrBlank() && value.equals(hostId.trim(), ignoreCase = true)) return true
        if (value.lowercase() in PLACEHOLDER_NAMES) return true
        return value.length in 20..64 && value.all { it.uppercaseChar() in BASE32_ALPHABET }
    }

    fun displayName(name: String?, hostId: String): String =
        name?.trim()?.takeUnless { isPlaceholderName(it, hostId) } ?: "主机"

    /** Merge addresses into one host row; human names upgrade ID/placeholder names. */
    fun merge(candidates: Iterable<HostCandidate>): List<HostCandidate> {
        val merged = LinkedHashMap<String, HostCandidate>()
        for (candidate in candidates) {
            if (!isValidHostId(candidate.hostId)) continue
            val old = merged[candidate.hostId]
            if (old == null) {
                merged[candidate.hostId] = candidate.copy(
                    name = candidate.name.trim().takeUnless {
                        isPlaceholderName(it, candidate.hostId)
                    }.orEmpty(),
                    endpoints = candidate.endpoints.distinctBy { it.authority },
                )
            } else {
                val oldName = old.name.trim()
                val newName = candidate.name.trim()
                val name = when {
                    !isPlaceholderName(oldName, candidate.hostId) -> oldName
                    !isPlaceholderName(newName, candidate.hostId) -> newName
                    else -> ""
                }
                merged[candidate.hostId] = old.copy(
                    name = name,
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

    /** TS is the primary channel; LAN remains the bounded fallback after it. */
    fun prioritize(endpoints: Iterable<HostEndpoint>): List<HostEndpoint> = endpoints
        .filter { isLiteralIpv4(it.address) }
        .sortedWith(compareBy<HostEndpoint> { if (it.path == ConnectionPath.TAILNET) 0 else 1 }
            .thenBy { it.source.ordinal }
            .thenBy { it.address }
            .thenBy { it.port })
        .distinctBy { it.authority }
}

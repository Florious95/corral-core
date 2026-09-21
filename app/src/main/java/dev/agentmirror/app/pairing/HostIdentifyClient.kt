/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package dev.agentmirror.app.pairing

import android.util.Log
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.app.tsnet.ConnectionPath
import dev.agentmirror.app.tsnet.TsnetDial
import dev.agentmirror.app.tsnet.TsnetWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HTTP seam used by tests; implementations must not follow redirects. */
interface HostHttpTransport {
    fun whoami(endpoint: HostEndpoint): HostHttpResponse
    fun identify(endpoint: HostEndpoint, request: IdentifyRequest): HostHttpResponse
}

/** Identity seam used by pairing orchestration; production defaults to [HostIdentifyClient]. */
interface HostIdentityVerifier {
    fun whoami(endpoint: HostEndpoint): HostCandidate?
    fun identify(
        endpoint: HostEndpoint,
        hostId: String?,
        token: String,
        legacyUrl: String?,
    ): HostIdentifyResult
}

data class HostHttpResponse(val code: Int, val body: String = "", val location: String? = null)

data class IdentifyRequest(
    val hostId: String?,
    val nonceHex: String,
    val destIp: String,
)

/**
 * HTTP discovery/identity client. It has no WS capability: callers can only create a WS after
 * [identify] returns [HostIdentifyResult.Proven].
 */
class HostIdentifyClient(
    private val transport: HostHttpTransport,
    private val nonceSource: () -> ByteArray = { ByteArray(16).also(SecureRandom()::nextBytes) },
) : HostIdentityVerifier {
    override fun whoami(endpoint: HostEndpoint): HostCandidate? {
        if (!HostRouter.isLiteralIpv4(endpoint.address)) {
            logWhoamiDecision(endpoint, "reject=non_literal_ip")
            return null
        }
        val response = runCatching { transport.whoami(endpoint) }.getOrNull() ?: run {
            logWhoamiDecision(endpoint, "reject=transport_null")
            return null
        }
        if (response.code != 200) {
            logWhoamiDecision(endpoint, "reject=http_code=${response.code}")
            return null
        }
        if (response.body.toByteArray().size > MAX_HOST_BODY_BYTES) {
            logWhoamiDecision(endpoint, "reject=body_oversize")
            return null
        }
        val json = parse(response.body) ?: run {
            logWhoamiDecision(endpoint, "reject=invalid_json")
            return null
        }
        val hostId = json.string("host_id") ?: run {
            logWhoamiDecision(endpoint, "reject=missing_host_id")
            return null
        }
        if (!HostRouter.isValidHostId(hostId)) {
            logWhoamiDecision(endpoint, "reject=invalid_host_id length=${hostId.length}")
            return null
        }
        val name = json.string("name").orEmpty()
        val port = json.int("port")?.takeIf { it in 1..65535 } ?: endpoint.port
        // The transport endpoint may be a NAT alias (for example 10.0.2.2 on
        // Android Emulator). Prefer server-advertised literal addresses for identify/WS,
        // while retaining the original endpoint as a bounded fallback.
        val advertisedAddresses = runCatching {
            json["addresses"]?.jsonArray.orEmpty().mapNotNull { element ->
                element.jsonPrimitive.content.takeIf(HostRouter::isLiteralIpv4)
            }
        }.getOrDefault(emptyList())
        val endpoints = buildList {
            advertisedAddresses.forEach { address ->
                if (address == endpoint.address) return@forEach
                val path = HostRouter.classify(address) ?: return@forEach
                add(HostEndpoint(address, port, path, HostEndpointSource.HOST_RECORD))
            }
            add(endpoint.copy(port = port, source = endpoint.source))
        }.distinctBy { it.authority }
        val candidate = HostCandidate(hostId, name, endpoints)
        logWhoamiDecision(endpoint, "accepted host_id_length=${hostId.length} endpoints=${endpoints.size}")
        return candidate
    }

    private fun logWhoamiDecision(endpoint: HostEndpoint, verdict: String) {
        if (endpoint.path != ConnectionPath.TAILNET) return
        val message = "tailnet whoami authority=${endpoint.authority} $verdict"
        runCatching { Log.d(TAG, message) }
        DiagLog.record("identify-tailnet", message)
    }

    override fun identify(
        endpoint: HostEndpoint,
        hostId: String?,
        token: String,
        legacyUrl: String?,
    ): HostIdentifyResult {
        val authority = endpoint.authority
        fun reject(reason: String, extra: String = ""): HostIdentifyResult {
            DiagLog.record(
                "identify",
                "verdict=Rejected reason=$reason authority=$authority dest_ip=${endpoint.address} " +
                    "port=${endpoint.port} $extra".trim(),
            )
            return HostIdentifyResult.Rejected(reason)
        }
        if (token.isEmpty()) return reject("empty token", "token_len=0")
        if (!HostRouter.isLiteralIpv4(endpoint.address)) {
            return reject("non-literal address")
        }
        val nonceHex = nonceSource().takeIf { it.size == 16 }?.toHex()
            ?: return reject("invalid nonce")
        val request = IdentifyRequest(hostId, nonceHex, endpoint.address)
        val response = runCatching { transport.identify(endpoint, request) }.getOrNull()
            ?: return reject("identify unavailable")
        val bodyLen = response.body.toByteArray().size
        val httpExtra = "http_code=${response.code} body_len=$bodyLen nonce_len=${nonceHex.length}"
        if (response.code == 404 && legacyAllowed(endpoint, legacyUrl, hostId)) {
            DiagLog.record("identify", "verdict=Legacy404 authority=$authority $httpExtra")
            return HostIdentifyResult.Legacy404(endpoint)
        }
        if (response.code !in 200..299 || bodyLen > MAX_HOST_BODY_BYTES) {
            return reject("identify rejected", httpExtra)
        }
        val json = parse(response.body)
            ?: return reject("invalid identify response", "$httpExtra parsed=false")
        val responseHostId = json.string("host_id")
            ?: return reject("missing host id", "$httpExtra parsed=true")
        val hostIdValid = HostRouter.isValidHostId(responseHostId)
        val hostIdMatch = hostId == null || responseHostId == hostId
        if (!hostIdValid) {
            return reject(
                "invalid host id",
                "$httpExtra parsed=true host_id_len=${responseHostId.length} host_id_valid=false",
            )
        }
        if (!hostIdMatch) {
            return reject(
                "host id mismatch",
                "$httpExtra parsed=true host_id_len=${responseHostId.length} host_id_valid=true host_id_match=false",
            )
        }
        val bound = json.string("bound") ?: return reject("missing bound", "$httpExtra parsed=true host_id_match=true")
        val boundEq = bound == authority
        if (!boundEq) {
            return reject(
                "bound address mismatch",
                "$httpExtra parsed=true host_id_match=true bound=$bound bound_eq=false",
            )
        }
        val wireMac = json.string("mac")?.lowercase() ?: return reject("missing mac", "$httpExtra parsed=true bound_eq=true")
        val macHex64 = wireMac.matches(Regex("[0-9a-f]{64}"))
        if (!macHex64) {
            return reject(
                "invalid mac",
                "$httpExtra parsed=true bound_eq=true mac_len=${wireMac.length} mac_hex64=false",
            )
        }
        val expected = mac(
            token = token,
            hostId = responseHostId,
            nonceHex = nonceHex,
            boundIp = endpoint.address,
            boundPort = endpoint.port,
        )
        val macMatch = constantTimeEquals(wireMac, expected)
        if (!macMatch) {
            return reject(
                "mac mismatch",
                "$httpExtra parsed=true host_id_match=true bound=$bound bound_eq=true " +
                    "mac_len=${wireMac.length} mac_hex64=true mac_match=false",
            )
        }
        DiagLog.record(
            "identify",
            "verdict=Proven authority=$authority dest_ip=${endpoint.address} port=${endpoint.port} " +
                "$httpExtra parsed=true host_id_len=${responseHostId.length} host_id_valid=true " +
                "host_id_match=true bound=$bound bound_eq=true mac_len=${wireMac.length} " +
                "mac_hex64=true mac_match=true",
        )
        return HostIdentifyResult.Proven(
            HostIdentity(responseHostId, json.string("name").orEmpty(), endpoint, bound),
        )
    }

    /** Compatibility overload for callers that do not have a legacy URL hint. */
    fun identify(endpoint: HostEndpoint, hostId: String?, token: String): HostIdentifyResult =
        identify(endpoint, hostId, token, null)

    private fun legacyAllowed(endpoint: HostEndpoint, legacyUrl: String?, hostId: String?): Boolean {
        if (hostId != null || legacyUrl.isNullOrBlank()) return false
        val legacy = HostRouter.endpointFromWsUrl(legacyUrl, HostEndpointSource.QR) ?: return false
        return legacy.address == endpoint.address && legacy.port == endpoint.port &&
            endpoint.source in setOf(HostEndpointSource.SCANNED_PRIMARY, HostEndpointSource.PERSISTED_LEGACY)
    }

    internal fun mac(token: String, hostId: String, nonceHex: String, boundIp: String, boundPort: Int): String {
        val message = "agentmirror-identify-v1\u001f$hostId\u001f$nonceHex\u001f$boundIp\u001f$boundPort"
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return hmac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun parse(body: String) = runCatching {
        Json.parseToJsonElement(body).jsonObject
    }.getOrNull()

    private fun Map<String, kotlinx.serialization.json.JsonElement>.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private fun Map<String, kotlinx.serialization.json.JsonElement>.int(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.content?.toIntOrNull() }.getOrNull()

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val aa = a.toByteArray(Charsets.US_ASCII)
        val bb = b.toByteArray(Charsets.US_ASCII)
        if (aa.size != bb.size) return false
        var diff = 0
        for (i in aa.indices) diff = diff or (aa[i].toInt() xor bb[i].toInt())
        return diff == 0
    }

    private companion object {
        const val TAG = "HostIdentifyClient"
    }

}

private const val MAX_HOST_BODY_BYTES = 1024
private const val HOST_PROBE_TIMEOUT_MS = 1_500L

/** Production OkHttp transport: literal IPv4 only, no redirects, no proxy/DNS fallback. */
class OkHttpHostHttpTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(HOST_PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(HOST_PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(HOST_PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .callTimeout(HOST_PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (!HostRouter.isLiteralIpv4(hostname)) throw UnknownHostException(hostname)
                val bytes = hostname.split('.').map(String::toInt).map { it.toByte() }.toByteArray()
                return listOf(InetAddress.getByAddress(hostname, bytes))
            }
        })
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .proxy(java.net.Proxy.NO_PROXY)
        .build(),
) : HostHttpTransport {
    override fun whoami(endpoint: HostEndpoint): HostHttpResponse =
        execute(endpoint, "GET", "/pair/whoami", null)

    override fun identify(endpoint: HostEndpoint, request: IdentifyRequest): HostHttpResponse =
        execute(
            endpoint,
            "POST",
            "/pair/identify",
            "{\"v\":1,\"host_id\":${request.hostId?.let(::quote) ?: "null"}," +
                "\"nonce\":${quote(request.nonceHex)},\"dest_ip\":${quote(request.destIp)}}",
        )

    private fun execute(endpoint: HostEndpoint, method: String, path: String, body: String?): HostHttpResponse {
        if (!HostRouter.isLiteralIpv4(endpoint.address)) return HostHttpResponse(400)
        val url = "http://${endpoint.authority}$path"
        val builder = Request.Builder().url(url)
        if (method == "POST") {
            builder.post((body ?: "{}").toRequestBody(JSON))
        } else {
            builder.get()
        }
        val result = runCatching {
            clientFor(endpoint).newCall(builder.build()).execute().use { response ->
                val source = response.body?.source()
                if (source == null) {
                    DiagLog.record(
                        "identify",
                        "transport_http method=$method path=$path authority=${endpoint.authority} " +
                            "http_code=${response.code} body_len=0 has_source=false",
                    )
                    return@use HostHttpResponse(response.code)
                }
                // request(N) is true iff at least N bytes are buffered. Short JSON must not
                // use readByteArray(N), which throws EOFException when the body is smaller.
                val oversize = source.request((MAX_HOST_BODY_BYTES + 1).toLong())
                if (oversize) {
                    DiagLog.record(
                        "identify",
                        "transport_http method=$method path=$path authority=${endpoint.authority} " +
                            "http_code=${response.code} body_oversize=true",
                    )
                    return@use HostHttpResponse(response.code, "x".repeat(MAX_HOST_BODY_BYTES + 1))
                }
                val body = source.readByteArray().toString(Charsets.UTF_8)
                DiagLog.record(
                    "identify",
                    "transport_http method=$method path=$path authority=${endpoint.authority} " +
                        "http_code=${response.code} body_len=${body.toByteArray().size} body_oversize=false",
                )
                HostHttpResponse(response.code, body, response.header("Location"))
            }
        }.getOrElse { error ->
            DiagLog.record(
                "identify",
                "transport_fail method=$method path=$path authority=${endpoint.authority} " +
                    "kind=${error.javaClass.simpleName} http_code=599",
            )
            if (endpoint.path == ConnectionPath.TAILNET) {
                logTailnetWarning(
                    endpoint,
                    "transport_fail kind=${error.javaClass.simpleName} message=${error.message.orEmpty()}",
                )
            }
            HostHttpResponse(599)
        }
        if (endpoint.path == ConnectionPath.TAILNET && result.code !in 200..299) {
            logTailnetWarning(endpoint, "http_code=${result.code} method=$method path=$path")
        }
        return result
    }

    private fun logTailnetWarning(endpoint: HostEndpoint, detail: String) {
        val message = "Failed to identify tailnet peer ${endpoint.address}:${endpoint.port} - $detail"
        runCatching { Log.w(TAG, message) }
        DiagLog.record("identify-tailnet", message)
    }

    /** Tailnet identity requests must traverse the same authenticated tsnet SOCKS seam as WS. */
    private fun clientFor(endpoint: HostEndpoint): OkHttpClient {
        val socketFactory = TsnetDial.socketFactoryFor(TsnetWire.state, endpoint.address)
        return if (socketFactory == null) client else {
            client.newBuilder()
                .socketFactory(socketFactory)
                .proxy(java.net.Proxy.NO_PROXY)
                .build()
        }
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val TAG = "HostIdentifyClient"
        val JSON = "application/json".toMediaType()
    }
}

private fun ByteArray.toHex(): String = buildString(size * 2) {
    for (b in this@toHex) append("%02x".format(b))
}

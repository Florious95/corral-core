/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package dev.agentmirror.app.pairing

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import dev.agentmirror.app.tsnet.ConnectionPath
import java.net.Inet4Address

/**
 * Bounded Android DNS-SD seam. TXT carries only host_id; discovered addresses remain
 * untrusted until HostIdentifyClient proves them. MulticastLock is held only during a window.
 */
class NsdHostDiscovery(context: Context) {
    interface Listener {
        fun onHost(candidate: HostCandidate)
        fun onFinished()
        fun onFailure(reason: String)
    }

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private val callbackExecutor = ContextCompat.getMainExecutor(appContext)
    private var lock: WifiManager.MulticastLock? = null
    private var listener: Listener? = null
    private var discovering = false

    fun start(windowMs: Long = 4_000L, listener: Listener) {
        stop()
        this.listener = listener
        lock = wifi?.createMulticastLock("agentmirror-discovery")?.apply { setReferenceCounted(false); acquire() }
        discovering = true
        runCatching { startDiscovery() }
            .onFailure { fail("NSD discover failed") }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (discovering) {
                stop()
                listener.onFinished()
            }
        }, windowMs.coerceIn(250L, MAX_WINDOW_MS))
    }

    fun stop() {
        if (discovering) runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        discovering = false
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        listener = null
    }

    /**
     * API 34+ allows NSD to bind to the actual Wi-Fi/Ethernet Network. The legacy overload
     * delegates network selection to framework defaults, which is ambiguous when a VPN and
     * virtual Ethernet coexist. This fixes interface selection, but cannot manufacture L2
     * multicast through a QEMU NAT that does not forward 224.0.0.251.
     */
    private fun startDiscovery() {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val network = discoveryNetwork()
            if (network != null) {
                nsd.discoverServices(
                    SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    network,
                    callbackExecutor,
                    discoveryListener,
                )
                return
            }
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private fun discoveryNetwork(): Network? {
        val manager = connectivity ?: return null
        val candidates = buildList {
            manager.activeNetwork?.let(::add)
            addAll(manager.allNetworks)
        }.distinct()
        fun hasTransport(network: Network, transport: Int): Boolean =
            manager.getNetworkCapabilities(network)?.hasTransport(transport) == true
        return candidates.firstOrNull { hasTransport(it, NetworkCapabilities.TRANSPORT_WIFI) }
            ?: candidates.firstOrNull { hasTransport(it, NetworkCapabilities.TRANSPORT_ETHERNET) }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            runCatching { nsd.resolveService(serviceInfo, resolveListener) }
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = fail("NSD start failed")
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val hostId = serviceInfo.attributes["id"]?.toString(Charsets.UTF_8)?.trim()
                ?.takeIf { HostRouter.isValidHostId(it) } ?: return
            val address = (serviceInfo.host as? Inet4Address)?.hostAddress ?: return
            val port = serviceInfo.port.takeIf { it in 1..65535 } ?: return
            val endpoint = HostEndpoint(address, port, ConnectionPath.LAN, HostEndpointSource.NSD)
            // The DNS-SD instance is the daemon's random host ID, not a human display name.
            // Leave naming to /pair/whoami so the UI never promotes a Base32 ID to a title.
            listener?.onHost(HostCandidate(hostId, "", listOf(endpoint)))
        }
    }

    private fun fail(reason: String) {
        val current = listener
        stop()
        current?.onFailure(reason)
    }

    companion object {
        const val SERVICE_TYPE = "_agentmirror._tcp"
        const val MAX_WINDOW_MS = 10_000L
    }
}

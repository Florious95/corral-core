/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package dev.agentmirror.app.pairing

import dev.agentmirror.app.conn.AsyncDialCoordinator
import dev.agentmirror.app.conn.DialTarget
import dev.agentmirror.app.tsnet.ConnectionPath
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Bounded asynchronous identify scheduler. It never creates a WebSocket; only
 * [ConnectionManager] can consume its proven targets.
 */
class HostDialCoordinator(
    private val endpointSource: () -> List<HostEndpoint>,
    private val hostId: String?,
    private val token: String,
    private val identifyClient: HostIdentifyClient,
    private val executor: Executor = Executors.newCachedThreadPool(),
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AsyncDialCoordinator {
    private data class Round(
        val generation: Long,
        val onTarget: (DialTarget) -> Unit,
        val onExhausted: () -> Unit,
        val candidates: List<HostEndpoint>,
        val pending: MutableSet<String>,
        val proved: MutableList<HostEndpoint> = mutableListOf(),
        val emitted: MutableSet<String> = mutableSetOf(),
        var tsInFlight: Int = 0,
        var lanInFlight: Int = 0,
        var completed: Int = 0,
        var stopped: Boolean = false,
        var lanSlotOpen: Boolean = false,
        var deadlineReached: Boolean = false,
    )

    private val lock = Any()
    private var round: Round? = null
    private var cursorAuthority: String? = null

    override fun begin(generation: Long, onTarget: (DialTarget) -> Unit, onExhausted: () -> Unit) {
        cancelAll()
        val ordered = HostRouter.prioritize(endpointSource()).distinctBy { it.authority }
        val cursor = cursorAuthority
        val pivot = cursor?.let { value -> ordered.indexOfFirst { it.authority == value } }
            ?.takeIf { it >= 0 }?.plus(1)?.rem(ordered.size.coerceAtLeast(1)) ?: 0
        val candidates = if (pivot == 0) ordered else ordered.drop(pivot) + ordered.take(pivot)
        val current = Round(
            generation = generation,
            onTarget = onTarget,
            onExhausted = onExhausted,
            candidates = candidates,
            pending = candidates.mapTo(LinkedHashSet()) { it.authority },
        )
        synchronized(lock) { round = current }
        if (candidates.isEmpty()) {
            onExhausted()
            return
        }
        scheduler.schedule({
            synchronized(lock) {
                if (round?.generation == generation) round?.lanSlotOpen = true
            }
            offer(generation)
        }, LAN_SLOT_MS, TimeUnit.MILLISECONDS)
        scheduler.schedule({
            synchronized(lock) { if (round?.generation == generation) round?.deadlineReached = true }
            offer(generation)
        }, ROUND_BUDGET_MS, TimeUnit.MILLISECONDS)
        // Discovery/identify work is bounded to four simultaneous HTTP requests.
        startMore(generation)
    }

    private fun startMore(generation: Long) {
        while (true) {
            val endpoint = synchronized(lock) {
                val r = round ?: return
                if (r.generation != generation || r.stopped) return
                val inFlight = r.tsInFlight + r.lanInFlight
                if (inFlight >= MAX_IDENTIFY_CONCURRENCY) return
                val next = r.candidates.firstOrNull { it.authority in r.pending }
                if (next != null) {
                    r.pending.remove(next.authority)
                    if (next.path == ConnectionPath.TAILNET) r.tsInFlight++ else r.lanInFlight++
                }
                next
            } ?: return
            executor.execute { prove(generation, endpoint) }
        }
    }

    private fun prove(generation: Long, endpoint: HostEndpoint) {
        val result = identifyClient.identify(endpoint, hostId, token)
        synchronized(lock) {
            val r = round ?: return
            if (r.generation != generation || r.stopped) return
            r.completed++
            if (endpoint.path == ConnectionPath.TAILNET) r.tsInFlight-- else r.lanInFlight--
            if (result is HostIdentifyResult.Proven || result is HostIdentifyResult.Legacy404) {
                r.proved += endpoint
            }
        }
        startMore(generation)
        offer(generation)
    }

    /** Emit at most one target; later candidates wait for ConnectionManager failure feedback. */
    private fun offer(generation: Long) {
        var target: DialTarget? = null
        var exhausted = false
        synchronized(lock) {
            val r = round ?: return
            if (r.generation != generation || r.stopped) return
            val ordered = r.proved
                .filter { it.authority !in r.emitted }
                .sortedWith(compareBy<HostEndpoint> {
                    if (it.path == ConnectionPath.TAILNET) 0 else 1
                }.thenBy { it.source.ordinal }.thenBy { it.authority })
            val next = ordered.firstOrNull { endpoint ->
                endpoint.path == ConnectionPath.TAILNET || r.lanSlotOpen ||
                    (r.tsInFlight == 0 && r.pending.none { address ->
                        r.candidates.firstOrNull { it.authority == address }?.path == ConnectionPath.TAILNET
                    }) || r.deadlineReached
            }
            if (next != null) {
                r.emitted += next.authority
                cursorAuthority = next.authority
                target = DialTarget(next.wsUrl, if (next.path == ConnectionPath.TAILNET) "TS" else "LAN")
            } else if (r.completed == r.candidates.size &&
                r.proved.all { it.authority in r.emitted }) {
                r.stopped = true
                exhausted = true
            } else if (r.deadlineReached && r.proved.none { it.authority !in r.emitted }) {
                r.stopped = true
                exhausted = true
            }
        }
        target?.let { synchronized(lock) { round?.onTarget?.invoke(it) } }
        if (exhausted) synchronized(lock) { round?.onExhausted?.invoke() }
    }

    override fun onTargetFailed(generation: Long, url: String, reason: String) {
        // The target is blacklisted for this generation; its next candidate is offered.
        offer(generation)
    }

    override fun onReady(generation: Long) = cancel(generation)

    override fun cancel(generation: Long) {
        synchronized(lock) {
            if (round?.generation == generation) round?.stopped = true
            if (round?.generation == generation) round = null
        }
    }

    private fun cancelAll() {
        synchronized(lock) {
            round?.stopped = true
            round = null
        }
    }

    companion object {
        const val MAX_IDENTIFY_CONCURRENCY = 4
        const val IDENTIFY_TIMEOUT_MS = 2_000L
        const val WS_TIMEOUT_MS = 3_000L
        const val LAN_SLOT_MS = 1_500L
        const val TS_BUDGET_MS = 4_000L
        const val ROUND_BUDGET_MS = 8_000L
    }
}

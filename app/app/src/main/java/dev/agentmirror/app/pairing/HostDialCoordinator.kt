/*
 * Copyright 2026 AgentMirror Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package dev.agentmirror.app.pairing

import dev.agentmirror.app.conn.AsyncDialCoordinator
import dev.agentmirror.app.conn.DialTarget
import dev.agentmirror.app.diag.DiagLog
import dev.agentmirror.app.tsnet.ConnectionPath
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private fun hostIdentifyExecutor(): Executor = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "host-identify").apply { isDaemon = true }
}

private fun hostDiscoveryScheduler(): ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "host-discovery").apply { isDaemon = true }
    }

/**
 * Bounded asynchronous identify scheduler. It never creates a WebSocket; only
 * [ConnectionManager] can consume its proven targets.
 */
class HostDialCoordinator(
    private val endpointSource: () -> List<HostEndpoint>,
    private val hostId: String?,
    private val token: String,
    private val identifyClient: HostIdentifyClient,
    private val executor: Executor = hostIdentifyExecutor(),
    private val scheduler: ScheduledExecutorService = hostDiscoveryScheduler(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val legacyUrl: String? = null,
) : AsyncDialCoordinator {
    private data class Round(
        val generation: Long,
        val onTarget: (DialTarget) -> Unit,
        val onExhausted: () -> Unit,
        val candidates: List<HostEndpoint>,
        val pending: MutableSet<String>,
        val proved: MutableList<HostEndpoint> = mutableListOf(),
        val emitted: MutableSet<String> = mutableSetOf(),
        val failed: MutableSet<String> = mutableSetOf(),
        var tsInFlight: Int = 0,
        var lanInFlight: Int = 0,
        var completed: Int = 0,
        var stopped: Boolean = false,
        var lanSlotOpen: Boolean = false,
        var tsBudgetReached: Boolean = false,
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
            ?.takeIf { it >= 0 }
            ?.let { (it + 1) % ordered.size.coerceAtLeast(1) }
            ?: 0
        val candidates = if (pivot == 0) ordered else ordered.drop(pivot) + ordered.take(pivot)
        val current = Round(
            generation = generation,
            onTarget = onTarget,
            onExhausted = onExhausted,
            candidates = candidates,
            pending = candidates.mapTo(LinkedHashSet()) { it.authority },
        )
        synchronized(lock) { round = current }
        DiagLog.record(
            "identify",
            "begin gen=$generation candidates=${candidates.size} " +
                "authorities=${candidates.joinToString(",") { it.authority }}",
        )
        if (candidates.isEmpty()) {
            onExhausted()
            return
        }
        scheduler.schedule({
            val match = synchronized(lock) {
                val hit = round?.generation == generation
                if (hit) round?.lanSlotOpen = true
                hit
            }
            DiagLog.record("identify", "lan_slot gen=$generation match=$match lan_slot_ms=$LAN_SLOT_MS")
            offer(generation)
        }, LAN_SLOT_MS, TimeUnit.MILLISECONDS)
        scheduler.schedule({
            val match = synchronized(lock) {
                val hit = round?.generation == generation
                if (hit) {
                    round?.tsBudgetReached = true
                    round?.lanSlotOpen = true
                }
                hit
            }
            DiagLog.record("identify", "ts_budget gen=$generation match=$match ts_budget_ms=$TS_BUDGET_MS")
            startMore(generation)
            offer(generation)
        }, TS_BUDGET_MS, TimeUnit.MILLISECONDS)
        scheduler.schedule({
            val match = synchronized(lock) {
                val hit = round?.generation == generation
                if (hit) {
                    round?.deadlineReached = true
                    round?.pending?.clear()
                }
                hit
            }
            DiagLog.record(
                "identify",
                "deadline gen=$generation match=$match round_budget_ms=$ROUND_BUDGET_MS pending_cleared=$match",
            )
            offer(generation)
        }, ROUND_BUDGET_MS, TimeUnit.MILLISECONDS)
        // Discovery/identify work is bounded to four simultaneous HTTP requests.
        startMore(generation)
    }

    private fun startMore(generation: Long) {
        while (true) {
            val endpoint = synchronized(lock) {
                val r = round ?: return
                if (r.generation != generation || r.stopped || r.deadlineReached) return
                val inFlight = r.tsInFlight + r.lanInFlight
                if (inFlight >= MAX_IDENTIFY_CONCURRENCY) return
                val pendingTs = r.pending.any { authority ->
                    r.candidates.firstOrNull { it.authority == authority }?.path == ConnectionPath.TAILNET
                }
                val pendingLan = r.pending.any { authority ->
                    r.candidates.firstOrNull { it.authority == authority }?.path != ConnectionPath.TAILNET
                }
                val next = r.candidates.asSequence()
                    .filter { candidate ->
                        candidate.authority in r.pending &&
                            (candidate.path != ConnectionPath.TAILNET || !r.tsBudgetReached) &&
                            (candidate.path == ConnectionPath.TAILNET || r.lanSlotOpen || !pendingTs) &&
                            (candidate.path != ConnectionPath.TAILNET || !pendingLan || r.lanSlotOpen || r.tsInFlight < MAX_IDENTIFY_CONCURRENCY - 1)
                    }
                    .sortedWith(compareBy<HostEndpoint> {
                        if (it.path == ConnectionPath.TAILNET) 0 else 1
                    }.thenBy { it.source.ordinal }.thenBy { it.authority })
                    .firstOrNull()
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
        val result = identifyClient.identify(endpoint, hostId, token, legacyUrl)
        val verdict = when (result) {
            is HostIdentifyResult.Proven -> "Proven"
            is HostIdentifyResult.Legacy404 -> "Legacy404"
            is HostIdentifyResult.Rejected -> "Rejected"
        }
        val reason = (result as? HostIdentifyResult.Rejected)?.reason.orEmpty()
        var dropped = false
        var deadline = false
        var completed = 0
        var proved = 0
        synchronized(lock) {
            val r = round
            if (r == null || r.generation != generation || r.stopped) {
                dropped = true
            } else {
                r.completed++
                completed = r.completed
                if (endpoint.path == ConnectionPath.TAILNET) r.tsInFlight-- else r.lanInFlight--
                deadline = r.deadlineReached
                if (!r.deadlineReached && (result is HostIdentifyResult.Proven || result is HostIdentifyResult.Legacy404)) {
                    r.proved += endpoint
                }
                proved = r.proved.size
            }
        }
        DiagLog.record(
            "identify",
            "prove gen=$generation authority=${endpoint.authority} path=${endpoint.path} " +
                "verdict=$verdict reason=$reason dropped=$dropped deadline=$deadline " +
                "completed=$completed proved=$proved",
        )
        startMore(generation)
        offer(generation)
    }

    /** Emit at most one target; later candidates wait for ConnectionManager failure feedback. */
    private fun offer(generation: Long) {
        var target: DialTarget? = null
        var exhaustedCallback: (() -> Unit)? = null
        var targetCallback: ((DialTarget) -> Unit)? = null
        var skip = ""
        var snapshot = ""
        synchronized(lock) {
            val r = round
            if (r == null) {
                skip = "no_round"
                return@synchronized
            }
            if (r.generation != generation || r.stopped) {
                skip = "stale_or_stopped gen_match=${r.generation == generation} stopped=${r.stopped}"
                return@synchronized
            }
            snapshot = "proved=${r.proved.size} emitted=${r.emitted.size} failed=${r.failed.size} " +
                "pending=${r.pending.size} in_flight=${r.tsInFlight + r.lanInFlight} " +
                "lan_slot=${r.lanSlotOpen} deadline=${r.deadlineReached} ts_budget=${r.tsBudgetReached}"
            // A WebSocket is the sole in-flight route. The next proven endpoint is offered only
            // after ConnectionManager reports this one failed (or READY cancels the round).
            if (r.emitted.any { it !in r.failed }) {
                skip = "in_flight_ws $snapshot"
                return@synchronized
            }
            val ordered = r.proved
                .filter { it.authority !in r.emitted }
                .sortedWith(compareBy<HostEndpoint> {
                    if (it.path == ConnectionPath.TAILNET) 0 else 1
                }.thenBy { it.source.ordinal }.thenBy { it.authority })
            val next = ordered.firstOrNull { endpoint ->
                endpoint.path == ConnectionPath.TAILNET || r.lanSlotOpen ||
                    (r.tsInFlight == 0 && r.pending.none { authority ->
                        r.candidates.firstOrNull { it.authority == authority }?.path == ConnectionPath.TAILNET
                    }) || r.deadlineReached
            }
            if (next != null) {
                r.emitted += next.authority
                cursorAuthority = next.authority
                target = DialTarget(next.wsUrl, if (next.path == ConnectionPath.TAILNET) "TS" else "LAN")
                targetCallback = r.onTarget
            } else {
                val inFlight = r.tsInFlight + r.lanInFlight
                val noMoreWork = r.pending.isEmpty() && inFlight == 0 &&
                    r.proved.none { it.authority !in r.emitted }
                if (noMoreWork && !r.emitted.any { it !in r.failed }) {
                    r.stopped = true
                    exhaustedCallback = r.onExhausted
                } else {
                    skip = "wait $snapshot"
                }
            }
        }
        if (skip.isNotEmpty()) {
            DiagLog.record("identify", "offer gen=$generation emit=false $skip")
        }
        target?.let {
            DiagLog.record(
                "identify",
                "offer gen=$generation emit=true url=${it.url} path=${it.path} $snapshot",
            )
            targetCallback?.invoke(it)
        }
        if (exhaustedCallback != null) {
            DiagLog.record("identify", "exhausted gen=$generation $snapshot")
        }
        exhaustedCallback?.invoke()
    }

    override fun onTargetFailed(generation: Long, url: String, reason: String) {
        var matched = false
        synchronized(lock) {
            val r = round
            if (r == null || r.generation != generation || r.stopped) return@synchronized
            r.candidates.firstOrNull { it.wsUrl == url }?.let {
                if (it.authority in r.emitted) {
                    r.failed += it.authority
                    matched = true
                }
            }
        }
        DiagLog.record(
            "identify",
            "target_failed gen=$generation matched=$matched url=$url reason_len=${reason.length}",
        )
        if (matched) offer(generation)
    }

    override fun onReady(generation: Long) = cancel(generation)

    override fun cancel(generation: Long) {
        val match = synchronized(lock) {
            val hit = round?.generation == generation
            if (hit) {
                round?.stopped = true
                round = null
            }
            hit
        }
        DiagLog.record("identify", "cancel gen=$generation match=$match")
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

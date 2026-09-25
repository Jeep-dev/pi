package com.piandroid

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/** How a failed Bridge/Pi probe failed; decides whether a restart may kill live work. */
internal enum class BridgeFailureKind {
    /** Nothing listens on the endpoint: the Bridge process is gone. */
    UNREACHABLE,

    /** Something holds the endpoint but did not answer in time (frozen Termux, busy Pi). */
    UNRESPONSIVE,

    /** Any other definitive answer: wrong token, old Bridge version, Pi not running. */
    OTHER
}

internal fun classifyBridgeFailure(error: Throwable?): BridgeFailureKind {
    var current = error
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        when {
            current is SocketTimeoutException -> return BridgeFailureKind.UNRESPONSIVE
            // The Bridge answers an RPC that Pi did not acknowledge with this prefix.
            current.message.orEmpty().startsWith("RPC timeout") -> return BridgeFailureKind.UNRESPONSIVE
            current is ConnectException -> return BridgeFailureKind.UNREACHABLE
        }
        current = current.cause
    }
    return BridgeFailureKind.OTHER
}

/**
 * A prompt that failed this way may already have reached Pi: the request left the
 * app, but no definitive answer came back. Resending it blindly can duplicate work.
 */
internal fun isAmbiguousDeliveryFailure(error: Throwable): Boolean {
    if (classifyBridgeFailure(error) == BridgeFailureKind.UNRESPONSIVE) return true
    var current: Throwable? = error
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        if (current is ConnectException) return false
        if (current is IOException) return true
        current = current.cause
    }
    return false
}

/** Exponential backoff with a hard attempt limit, so a dead setup cannot retry forever. */
internal class ReconnectBackoff(
    private val baseDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 60_000L,
    private val maxAttempts: Int = 8
) {
    var failedAttempts: Int = 0
        private set

    fun reset() {
        failedAttempts = 0
    }

    /** Record one failed attempt; returns the wait before the next one, or null to give up. */
    fun nextDelayMs(): Long? {
        failedAttempts++
        if (failedAttempts >= maxAttempts) return null
        val shift = (failedAttempts - 1).coerceAtMost(20)
        return (baseDelayMs shl shift).coerceAtMost(maxDelayMs)
    }
}

/**
 * Restarting a Bridge kills its Pi child. An endpoint that accepts connections but
 * does not answer is usually frozen or busy, not dead, so it gets a grace window
 * before recovery may replace it. A dead or definitively wrong endpoint does not.
 */
internal class UnresponsiveBridgeGrace(private val graceMs: Long = 60_000L) {
    private var firstUnresponsiveAtMs: Long? = null

    fun reset() {
        firstUnresponsiveAtMs = null
    }

    fun allowRestart(kind: BridgeFailureKind, nowMs: Long): Boolean {
        if (kind != BridgeFailureKind.UNRESPONSIVE) {
            firstUnresponsiveAtMs = null
            return true
        }
        val first = firstUnresponsiveAtMs ?: nowMs.also { firstUnresponsiveAtMs = it }
        return nowMs - first >= graceMs
    }
}

internal enum class KeepAliveProbe { WORKING, IDLE, UNKNOWN }

internal fun classifyKeepAliveProbe(
    healthError: Throwable?,
    piRunning: Boolean,
    stateError: Throwable?,
    busy: Boolean
): KeepAliveProbe = when {
    healthError != null ->
        if (classifyBridgeFailure(healthError) == BridgeFailureKind.UNRESPONSIVE) KeepAliveProbe.UNKNOWN
        else KeepAliveProbe.IDLE
    !piRunning -> KeepAliveProbe.IDLE
    stateError != null -> KeepAliveProbe.UNKNOWN
    busy -> KeepAliveProbe.WORKING
    else -> KeepAliveProbe.IDLE
}

/**
 * Keep the foreground service while any Session works, reconnects, or cannot be
 * probed. Only confirmed idleness stops it quickly; uncertainty is capped so a
 * permanently broken endpoint cannot hold a wake lock forever.
 */
internal class KeepAlivePolicy(
    private val idlePollsToStop: Int = 2,
    private val maxUncertainPolls: Int = 24
) {
    private var idlePolls = 0
    private var uncertainPolls = 0

    fun shouldStop(probes: List<KeepAliveProbe>, recovering: Boolean): Boolean {
        if (probes.any { it == KeepAliveProbe.WORKING }) {
            idlePolls = 0
            uncertainPolls = 0
            return false
        }
        if (recovering || probes.any { it == KeepAliveProbe.UNKNOWN }) {
            idlePolls = 0
            uncertainPolls++
            return uncertainPolls >= maxUncertainPolls
        }
        uncertainPolls = 0
        idlePolls++
        return idlePolls >= idlePollsToStop
    }
}

/** Process-wide view of Session runtimes that are reconnecting, for the keep-alive service. */
internal object PiRecoveryTracker {
    private val recovering = ConcurrentHashMap.newKeySet<String>()

    fun mark(androidSessionId: String, active: Boolean) {
        if (active) recovering.add(androidSessionId) else recovering.remove(androidSessionId)
    }

    fun anyRecovering(): Boolean = recovering.isNotEmpty()
}

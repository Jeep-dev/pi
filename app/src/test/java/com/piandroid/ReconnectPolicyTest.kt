package com.piandroid

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun backoffGrowsExponentiallyIsCappedAndGivesUp() {
        val backoff = ReconnectBackoff(baseDelayMs = 1_000L, maxDelayMs = 10_000L, maxAttempts = 6)
        assertEquals(1_000L, backoff.nextDelayMs())
        assertEquals(2_000L, backoff.nextDelayMs())
        assertEquals(4_000L, backoff.nextDelayMs())
        assertEquals(8_000L, backoff.nextDelayMs())
        assertEquals(10_000L, backoff.nextDelayMs())
        assertNull(backoff.nextDelayMs())
        assertEquals(6, backoff.failedAttempts)

        backoff.reset()
        assertEquals(1_000L, backoff.nextDelayMs())
    }

    @Test
    fun defaultRecoveryBackoffIsBoundedInTime() {
        val backoff = ReconnectBackoff()
        var total = 0L
        while (true) total += backoff.nextDelayMs() ?: break
        assertTrue("recovery must stop instead of retrying forever", total in 1L..5 * 60_000L)
    }

    @Test
    fun failuresAreClassifiedThroughTheCauseChain() {
        assertEquals(BridgeFailureKind.UNREACHABLE, classifyBridgeFailure(ConnectException("refused")))
        assertEquals(BridgeFailureKind.UNRESPONSIVE, classifyBridgeFailure(SocketTimeoutException("read timed out")))
        assertEquals(
            BridgeFailureKind.UNRESPONSIVE,
            classifyBridgeFailure(IllegalStateException("wrapped", SocketTimeoutException("read timed out")))
        )
        assertEquals(BridgeFailureKind.UNRESPONSIVE, classifyBridgeFailure(IllegalStateException("RPC timeout: get_state")))
        assertEquals(BridgeFailureKind.OTHER, classifyBridgeFailure(IllegalStateException("unauthorized")))
        assertEquals(BridgeFailureKind.OTHER, classifyBridgeFailure(null))
    }

    @Test
    fun deadBridgeMayRestartImmediatelyButUnresponsiveOneGetsGrace() {
        val grace = UnresponsiveBridgeGrace(graceMs = 60_000L)
        assertTrue(grace.allowRestart(BridgeFailureKind.UNREACHABLE, 0L))
        assertTrue(grace.allowRestart(BridgeFailureKind.OTHER, 0L))

        assertFalse(grace.allowRestart(BridgeFailureKind.UNRESPONSIVE, 1_000L))
        assertFalse(grace.allowRestart(BridgeFailureKind.UNRESPONSIVE, 60_999L))
        assertTrue(grace.allowRestart(BridgeFailureKind.UNRESPONSIVE, 61_000L))
    }

    @Test
    fun graceWindowRestartsAfterTheEndpointAnswersAgain() {
        val grace = UnresponsiveBridgeGrace(graceMs = 60_000L)
        assertFalse(grace.allowRestart(BridgeFailureKind.UNRESPONSIVE, 0L))
        grace.reset()
        assertFalse(grace.allowRestart(BridgeFailureKind.UNRESPONSIVE, 70_000L))
        assertTrue(grace.allowRestart(BridgeFailureKind.UNRESPONSIVE, 130_000L))
    }

    @Test
    fun onlyUnansweredPromptsAreAmbiguous() {
        assertTrue(isAmbiguousDeliveryFailure(SocketTimeoutException("read timed out")))
        assertTrue(isAmbiguousDeliveryFailure(IllegalStateException("RPC timeout: prompt")))
        assertTrue(isAmbiguousDeliveryFailure(IOException("unexpected end of stream")))
        assertFalse(isAmbiguousDeliveryFailure(ConnectException("refused")))
        assertFalse(isAmbiguousDeliveryFailure(IllegalStateException("Stop in progress; prompt rejected")))
    }

    @Test
    fun keepAliveProbeTreatsTimeoutsAsUnknownNotIdle() {
        assertEquals(KeepAliveProbe.UNKNOWN, classifyKeepAliveProbe(SocketTimeoutException(), false, null, false))
        assertEquals(KeepAliveProbe.IDLE, classifyKeepAliveProbe(ConnectException(), false, null, false))
        assertEquals(KeepAliveProbe.IDLE, classifyKeepAliveProbe(null, false, null, false))
        assertEquals(KeepAliveProbe.UNKNOWN, classifyKeepAliveProbe(null, true, IllegalStateException("RPC timeout: get_state"), false))
        assertEquals(KeepAliveProbe.WORKING, classifyKeepAliveProbe(null, true, null, true))
        assertEquals(KeepAliveProbe.IDLE, classifyKeepAliveProbe(null, true, null, false))
    }

    @Test
    fun keepAliveStopsOnlyAfterConfirmedIdlePolls() {
        val policy = KeepAlivePolicy(idlePollsToStop = 2, maxUncertainPolls = 4)
        assertFalse(policy.shouldStop(listOf(KeepAliveProbe.IDLE), recovering = false))
        assertTrue(policy.shouldStop(listOf(KeepAliveProbe.IDLE), recovering = false))
    }

    @Test
    fun keepAliveSurvivesReconnectsButNotForever() {
        val policy = KeepAlivePolicy(idlePollsToStop = 2, maxUncertainPolls = 4)
        assertFalse(policy.shouldStop(listOf(KeepAliveProbe.IDLE), recovering = true))
        assertFalse(policy.shouldStop(listOf(KeepAliveProbe.UNKNOWN), recovering = false))
        assertFalse(policy.shouldStop(listOf(KeepAliveProbe.IDLE), recovering = true))
        assertTrue(policy.shouldStop(listOf(KeepAliveProbe.UNKNOWN), recovering = false))
    }

    @Test
    fun workingSessionResetsKeepAliveCounters() {
        val policy = KeepAlivePolicy(idlePollsToStop = 2, maxUncertainPolls = 2)
        assertFalse(policy.shouldStop(listOf(KeepAliveProbe.UNKNOWN), recovering = false))
        assertFalse(policy.shouldStop(listOf(KeepAliveProbe.WORKING, KeepAliveProbe.UNKNOWN), recovering = true))
        assertFalse(policy.shouldStop(listOf(KeepAliveProbe.UNKNOWN), recovering = false))
        assertFalse(policy.shouldStop(listOf(KeepAliveProbe.IDLE), recovering = false))
        assertTrue(policy.shouldStop(listOf(KeepAliveProbe.IDLE), recovering = false))
    }

    @Test
    fun keepAliveStaysWhileAnySessionIsOnline() {
        val policy = KeepAlivePolicy(idlePollsToStop = 2, maxUncertainPolls = 2)
        repeat(5) {
            assertFalse(policy.shouldStop(listOf(KeepAliveProbe.IDLE), recovering = false, anyOnline = true))
        }
        assertFalse(policy.shouldStop(listOf(KeepAliveProbe.IDLE), recovering = false, anyOnline = false))
        assertTrue(policy.shouldStop(listOf(KeepAliveProbe.IDLE), recovering = false, anyOnline = false))
    }

    @Test
    fun recoveryTrackerFollowsEachSession() {
        PiRecoveryTracker.mark("a", true)
        PiRecoveryTracker.mark("b", true)
        PiRecoveryTracker.mark("a", false)
        assertTrue(PiRecoveryTracker.anyRecovering())
        PiRecoveryTracker.mark("b", false)
        assertFalse(PiRecoveryTracker.anyRecovering())
    }
}

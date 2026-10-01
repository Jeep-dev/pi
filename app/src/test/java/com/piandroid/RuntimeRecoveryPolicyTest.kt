package com.piandroid

import org.junit.Assert.*
import org.junit.Test

class RuntimeRecoveryPolicyTest {
    @Test fun timeoutAndResetNeverAuthorizeProcessReplacement() {
        repeat(100) { assertFalse(canReplaceUnreachableBridge(false, false)) }
        assertTrue(canReplaceUnreachableBridge(true, false))
        assertTrue(canReplaceUnreachableBridge(false, true))
    }

    @Test fun keepAliveRequiresPositiveOfflineEvidenceBeforeStopping() {
        assertFalse(confirmedOffline(null, false))
        assertFalse(confirmedOffline(true, false))
        assertTrue(confirmedOffline(null, true))
        assertTrue(confirmedOffline(false, false))
    }

    @Test fun workingOrUnknownPiMustNotBeReplacedForUpgrade() {
        assertFalse(canReplaceRunningBridge(true, false))
        assertFalse(canReplaceRunningBridge(false, true))
        assertFalse(canReplaceRunningBridge(null, null))
        assertFalse(canReplaceRunningBridge(null, false))
        assertFalse(canReplaceRunningBridge(false, null))
        assertTrue(canReplaceRunningBridge(false, false))
    }
}

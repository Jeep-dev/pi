package com.piandroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLaunchPolicyTest {
    @Test
    fun existingSessionArgumentsNeverReceiveAndroidDefaultModel() {
        val commands = listOf(
            "pi --mode rpc --session /tmp/old.jsonl",
            "pi --mode rpc --session=/tmp/old.jsonl",
            "pi --mode rpc --session-id old-id",
            "pi --mode rpc --continue",
            "pi --mode rpc -c",
            "pi --mode rpc --resume",
            "pi --mode rpc --resume=old-id",
            "pi --mode rpc -r",
            "pi --mode rpc --fork /tmp/old.jsonl"
        )
        commands.forEach { command ->
            assertTrue(selectsExistingPiSession(command))
            assertFalse(shouldApplyAndroidDefaultModel(command, 0))
        }
    }

    @Test
    fun defaultModelIsRestrictedToFreshEmptySessions() {
        assertTrue(shouldApplyAndroidDefaultModel("pi --mode rpc", 0))
        assertFalse(shouldApplyAndroidDefaultModel("pi --mode rpc", 1))
    }
}

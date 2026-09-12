package com.piandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLaunchPolicyTest {
    @Test
    fun cwdMismatchRestartsButMatchingCwdCanAttach() {
        assertEquals(ReconnectDecision.RESTART, reconnectDecision("/tmp/new-project", "/tmp/old-project"))
        assertEquals(ReconnectDecision.ATTACH, reconnectDecision(" /tmp/project ", "/tmp/project"))
    }

    @Test
    fun cwdChangeLaunchRemovesEveryExistingSessionSelector() {
        val command = "pi --mode rpc --session /tmp/old.jsonl --resume old-id --continue -e ~/.pi/android/mobile.ts"
        val fresh = launchPiWithoutSession(command)

        assertFalse(fresh.contains("--session"))
        assertFalse(fresh.contains("--resume"))
        assertFalse(fresh.contains("--continue"))
        assertFalse(fresh.contains("old.jsonl"))
        assertFalse(fresh.contains("old-id"))
        assertTrue(fresh.contains("--mode rpc"))
        assertTrue(fresh.contains("-e ~/.pi/android/mobile.ts"))
    }

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
    fun reconnectPinsTheActuallyActiveSessionInsteadOfTheStartupSession() {
        val active = "/tmp/current session's branch.jsonl"
        val staleCommands = listOf(
            "pi --mode rpc --session /tmp/stale.jsonl -e ~/.pi/android/mobile.ts",
            "pi --mode rpc --session=/tmp/stale.jsonl -e ~/.pi/android/mobile.ts",
            "pi --mode rpc --session-id stale-id -e ~/.pi/android/mobile.ts",
            "pi --mode rpc --continue -e ~/.pi/android/mobile.ts",
            "pi --mode rpc --resume stale-id -e ~/.pi/android/mobile.ts",
            "pi --mode rpc --fork /tmp/stale.jsonl -e ~/.pi/android/mobile.ts"
        )

        staleCommands.forEach { command ->
            val recovered = pinPiLaunchToSession(command, active)
            assertFalse(recovered.contains("stale"))
            assertTrue(recovered.contains("--session"))
            assertTrue(recovered.contains("current session"))
            assertEquals(1, Regex("(^|\\s)--session(?:\\s|=)").findAll(recovered).count())
        }
    }

    @Test
    fun sessionPinningDoesNotRewriteCustomShellLaunchers() {
        val custom = "bash -lc 'exec my-agent --mode rpc'"
        assertEquals(custom, pinPiLaunchToSession(custom, "/tmp/current.jsonl"))
    }

    @Test
    fun defaultModelIsRestrictedToFreshEmptySessions() {
        assertTrue(shouldApplyAndroidDefaultModel("pi --mode rpc", 0))
        assertFalse(shouldApplyAndroidDefaultModel("pi --mode rpc", 1))
    }

    @Test
    fun shellArgumentParsingKeepsQuotedSpacesEmptyArgumentsAndEscapes() {
        assertEquals(
            listOf("pi", "", "two words", "escaped value"),
            parseShellArguments("pi '' \"two words\" escaped\\ value")
        )
        assertNull(parseShellArguments("pi --system-prompt \"unterminated"))
    }

    @Test
    fun newSessionIdentityUsesAPrivateDirectoryAndExactNativeId() {
        val command = ensurePiSessionIdentity(
            "pi --mode rpc --session-id stale --session /tmp/old.jsonl --session-dir /tmp/shared",
            "session-a",
            "~/.pi/android/sessions/session-a/pi-sessions"
        )
        assertFalse(command.contains("stale"))
        assertFalse(command.contains("/tmp/old.jsonl"))
        assertFalse(command.contains("/tmp/shared"))
        assertTrue(command.contains("--session-dir ~/.pi/android/sessions/session-a/pi-sessions"))
        assertTrue(command.contains("--session-id session-a"))
    }

    @Test
    fun startupArgumentsPreserveQuotesAndAppendToThePiCommand() {
        val base = "pi --mode rpc -e ~/.pi/android/mobile.ts"
        val args = "--no-tools --system-prompt \"You are a simple chat assistant.\""
        assertEquals(
            "pi --mode rpc -e ~/.pi/android/mobile.ts --no-tools --system-prompt 'You are a simple chat assistant.'",
            appendPiStartupArguments(base, args)
        )
        assertEquals(base, appendPiStartupArguments(base, ""))
        assertNull(startupArgumentsError(args))
    }

    @Test
    fun startupArgumentsCannotReplaceAppOwnedSessionOrRpcOptions() {
        listOf(
            "--mode json",
            "--session /tmp/other.jsonl",
            "--session-dir /tmp/other",
            "--no-session",
            "--api-key secret"
        ).forEach {
            assertNotNull(startupArgumentsError(it))
        }
        assertNull(startupArgumentsError("--system-prompt \"--session is text\" --no-tools"))
        assertNotNull(startupArgumentsError("--system-prompt \"unterminated"))
    }
}

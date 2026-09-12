package com.piandroid

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRegistryPolicyTest {
    private class RuntimeTestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun newSessionLaunchUsesAnIsolatedExtensionDirectory() {
        val command = PiSessionStore.defaultLaunchCommand("session-abc")
        assertTrue(command.contains("--mode rpc"))
        assertTrue(command.contains(".pi/android/sessions/session-abc/pi-android-mobile.ts"))
    }

    @Test
    fun sessionsWithTheSameCwdRemainDistinctRecords() {
        val first = PiSessionRecord("a", "one", "/tmp/project", "pi --mode rpc", 17650, "token-a")
        val second = PiSessionRecord("b", "two", "/tmp/project", "pi --mode rpc", 17651, "token-b")
        assertEquals(first.cwd, second.cwd)
        assertNotEquals(first.androidSessionId, second.androidSessionId)
        assertNotEquals(first.port, second.port)
    }

    @Test
    fun persistedProcessStatusStartsAsNotStarted() {
        assertEquals(PiSessionStatus.NOT_STARTED, PiSessionRecord("a", "one", "/tmp", "pi", 17650, "token").status)
    }

    @Test
    fun nativeIdentityAndHistoryNamespaceAreIndependentOfCwd() {
        val first = PiSessionStore.defaultLaunchCommand("a")
        val second = PiSessionStore.defaultLaunchCommand("b")
        assertTrue(first.contains("--session-dir ~/.pi/android/sessions/a/pi-sessions"))
        assertTrue(second.contains("--session-dir ~/.pi/android/sessions/b/pi-sessions"))
        assertTrue(first.contains("--session-id a"))
        assertTrue(second.contains("--session-id b"))
        assertNotEquals(first, second)
    }

    @Test
    fun renameAndPinOnlyChangeAndroidPresentation() {
        val original = PiSessionRecord(
            "a", "native", "/tmp/project", "pi", 17650, "token",
            sessionFile = "/tmp/a.jsonl", piConversationId = "native-a",
            displayName = "A", ownedSessionFile = "/tmp/a.jsonl",
            sessionDirectory = "~/.pi/android/sessions/a/pi-sessions"
        )
        val renamed = renamePiSession(listOf(original), "a", "Renamed").single()
        assertEquals("Renamed", sessionDisplayName(renamed))
        assertEquals(original.cwd, renamed.cwd)
        assertEquals(original.sessionFile, renamed.sessionFile)
        assertEquals(original.piConversationId, renamed.piConversationId)
        assertEquals(original.port, renamed.port)
        assertEquals(original.token, renamed.token)

        val records = listOf(original, original.copy(androidSessionId = "b", displayName = "B", pinned = true))
        val pinned = togglePiSessionPinned(records, "a")
        assertEquals(listOf("a", "b"), pinned.map { it.androidSessionId })
        assertTrue(pinned.first().pinned)
    }

    @Test
    fun selectingAnotherSessionCommitsTheRequestedActiveId() {
        val records = listOf(
            PiSessionRecord("a", "A", "/tmp", "pi", 17650, "token-a"),
            PiSessionRecord("b", "B", "/tmp", "pi", 17651, "token-b")
        )
        assertEquals("b", selectPiSessionId(records, "b"))
        assertEquals(null, selectPiSessionId(records, "missing"))
    }

    @Test
    fun sameCwdSessionsKeepDistinctListAndRuntimeIdentityAcrossRepeatedSelection() {
        val records = listOf(
            PiSessionRecord("session-A", "Pi 1", "/data/data/com.termux/files/home", "pi", 17650, "token-a"),
            PiSessionRecord("session-B", "Pi 2", "/data/data/com.termux/files/home", "pi", 17651, "token-b")
        )
        assertEquals(2, records.map { it.androidSessionId }.distinct().size)
        val manager = PiSessionRuntimeManager(RuntimeTestContext())
        val runtimes = records.associate { it.androidSessionId to manager.activate(it) }
        var active = "session-B"
        repeat(20) { index ->
            val target = if (index % 2 == 0) "session-A" else "session-B"
            active = requireNotNull(selectPiSessionId(records, target))
            val activeRuntime = manager.activate(records.single { it.androidSessionId == active })
            assertEquals(target, active)
            assertEquals(target, manager.activeAndroidSessionId)
            assertEquals(target, activeRuntime.runtimeOwnerSessionId)
            assertSame(runtimes[target], activeRuntime)
        }
        assertEquals("session-B", active)
    }

    @Test
    fun deletingCurrentSessionChoosesOnlyARemainingSession() {
        val records = listOf(
            PiSessionRecord("a", "A", "/tmp", "pi", 17650, "token-a"),
            PiSessionRecord("b", "B", "/tmp", "pi", 17651, "token-b")
        )
        val remaining = removePiSession(records, "a")
        assertEquals(listOf("b"), remaining.map { it.androidSessionId })
        assertEquals("b", nextPiSessionIdAfterDelete(remaining, "a", "a"))
        assertEquals("b", nextPiSessionIdAfterDelete(remaining, "b", "b"))
        assertEquals(null, nextPiSessionIdAfterDelete(emptyList(), "a", "a"))
    }

    @Test
    fun stalePollCannotOverwriteAResumedConversationBinding() {
        val before = listOf(
            PiSessionRecord("a", "A", "/tmp", "pi", 17650, "token-a", sessionFile = "/tmp/A1.jsonl", piConversationId = "A1"),
            PiSessionRecord("b", "B", "/tmp", "pi", 17651, "token-b", sessionFile = "/tmp/B1.jsonl", piConversationId = "B1")
        )
        val current = before.map {
            if (it.androidSessionId == "a") it.copy(sessionFile = "/tmp/A2.jsonl", piConversationId = "A2") else it
        }
        val staleRefresh = before.map { it.copy(status = PiSessionStatus.IDLE) }
        val merged = mergePolledSessions(before, current, staleRefresh)
        assertEquals("/tmp/A2.jsonl", merged.single { it.androidSessionId == "a" }.sessionFile)
        assertEquals("A2", merged.single { it.androidSessionId == "a" }.piConversationId)
        assertEquals("/tmp/B1.jsonl", merged.single { it.androidSessionId == "b" }.sessionFile)
    }

    @Test
    fun resumingChangesOnlyThePiConversationBinding() {
        val original = PiSessionRecord(
            androidSessionId = "android-a",
            name = "A",
            cwd = "/tmp/project",
            launchCommand = "pi --mode rpc --session-id android-a",
            port = 17650,
            token = "token-a",
            sessionFile = "/tmp/A2.jsonl",
            piConversationId = "A2",
            ownedSessionFile = "/tmp/A2.jsonl",
            sessionDirectory = "~/.pi/android/sessions/android-a/pi-sessions"
        )
        val resumed = bindPiConversation(
            original,
            PiState("provider", "model", "Model", "off", false, false, "/tmp/A1.jsonl", "A1", "A1", 2, true),
            "/tmp/A1.jsonl"
        )
        assertEquals("android-a", resumed.androidSessionId)
        assertEquals(17650, resumed.port)
        assertEquals("token-a", resumed.token)
        assertEquals("A1", resumed.piConversationId)
        assertEquals("/tmp/A1.jsonl", resumed.sessionFile)
        assertTrue(resumed.legacySessionFile)
        assertEquals(original.ownedSessionFile, resumed.ownedSessionFile)
        assertEquals(original.sessionDirectory, resumed.sessionDirectory)
    }

    @Test
    fun privateConversationFilesRemainScopedToTheirAndroidSession() {
        assertTrue(isPrivatePiSessionFile("android-a", "/data/data/com.termux/files/home/.pi/android/sessions/android-a/pi-sessions/A1.jsonl"))
        assertFalse(isPrivatePiSessionFile("android-a", "/data/data/com.termux/files/home/.pi/android/sessions/android-b/pi-sessions/B1.jsonl"))
        assertFalse(isPrivatePiSessionFile("android-a", "/data/data/com.termux/files/home/.pi/agent/sessions/--project--/legacy.jsonl"))
    }

    @Test
    fun duplicateLegacyConversationHasExactlyOneAndroidOwner() {
        val legacy = "/data/data/com.termux/files/home/.pi/agent/sessions/--project--/legacy.jsonl"
        val records = listOf(
            PiSessionRecord("android-A", "A", "/same", "pi", 17650, "token-a", sessionFile = legacy, piConversationId = "legacy", legacySessionFile = true),
            PiSessionRecord("android-B", "B", "/same", "pi", 17651, "token-b", sessionFile = legacy, piConversationId = "legacy", legacySessionFile = true),
            PiSessionRecord("android-C", "C", "/same", "pi", 17652, "token-c", sessionFile = legacy, piConversationId = "legacy", legacySessionFile = true)
        )
        val repaired = isolatePiConversationOwnership(records, preferredAndroidSessionId = "android-B")
        assertEquals(legacy, repaired.single { it.androidSessionId == "android-B" }.sessionFile)
        assertEquals("legacy", repaired.single { it.androidSessionId == "android-B" }.piConversationId)
        for (id in listOf("android-A", "android-C")) {
            val reset = repaired.single { it.androidSessionId == id }
            assertEquals("", reset.sessionFile)
            assertEquals(id, reset.piConversationId)
            assertFalse(reset.legacySessionFile)
        }
    }

    @Test
    fun runtimeAttachRequiresOwnerAndConversationNotOnlyCwd() {
        val record = PiSessionRecord(
            "android-B", "B", "/same", "pi", 17651, "token-b",
            sessionFile = "/private/B.jsonl", piConversationId = "conversation-B"
        )
        val matching = PiState("p", "m", "M", "off", false, false, "/private/B.jsonl", "conversation-B", "B", 1, true)
        val wrongConversation = matching.copy(sessionFile = "/legacy/shared.jsonl", piConversationId = "conversation-A")
        assertTrue(runtimeConversationMatches(record, matching))
        assertFalse(runtimeConversationMatches(record, wrongConversation))
    }

    @Test
    fun productionRuntimeStateKeepsABCIndependentAcrossSwitchResumeAndRebuild() {
        val cwd = "/same/project"
        fun record(owner: String, conversation: String, port: Int) = PiSessionRecord(
            owner, owner, cwd, "pi", port, "token-$owner",
            sessionFile = "/private/$owner/$conversation.jsonl",
            piConversationId = conversation,
            sessionDirectory = "/private/$owner"
        )
        fun ready(conversation: String, marker: String) = PiRuntimeReady(
            state = PiState(
                "p", "m", "M", "off", false, false,
                "/private/android-${conversation.substringAfterLast('-').first()}/$conversation.jsonl",
                conversation, conversation, 1, true
            ),
            snapshot = PiRecoverySnapshot(
                history = listOf(PiHistoryMessage("user", marker)),
                events = emptyList(),
                latest = 1,
                pendingUi = emptyList(),
                editorText = null
            ),
            runtimeCwd = cwd
        )

        var records = listOf(
            record("android-A", "conversation-A", 17650),
            record("android-B", "conversation-B", 17651),
            record("android-C", "conversation-C", 17652)
        )
        var manager = PiSessionRuntimeManager(RuntimeTestContext())
        val initial = mapOf(
            "android-A" to ready("conversation-A", "AAA"),
            "android-B" to ready("conversation-B", "BBB"),
            "android-C" to ready("conversation-C", "CCC")
        )
        records.forEach { record ->
            assertTrue(manager.runtime(record).commitReady(requireNotNull(initial[record.androidSessionId])))
        }

        fun verifySwitches(expected: Map<String, Pair<String, String>>) {
            val sequence = listOf("android-A", "android-B", "android-C", "android-A", "android-C", "android-B")
            repeat(20) {
                sequence.forEach { targetAndroidSessionId ->
                    val target = records.single { it.androidSessionId == targetAndroidSessionId }
                    manager.activate(target)
                    val identity = requireNotNull(manager.activeIdentity())
                    val (conversation, marker) = requireNotNull(expected[targetAndroidSessionId])
                    assertEquals(targetAndroidSessionId, manager.activeAndroidSessionId)
                    assertEquals(targetAndroidSessionId, identity.androidSessionId)
                    assertEquals(targetAndroidSessionId, identity.runtimeOwnerSessionId)
                    assertEquals(conversation, identity.piConversationId)
                    assertEquals(listOf(marker), identity.history.map { it.text })
                }
            }
        }

        verifySwitches(
            mapOf(
                "android-A" to ("conversation-A" to "AAA"),
                "android-B" to ("conversation-B" to "BBB"),
                "android-C" to ("conversation-C" to "CCC")
            )
        )

        val b2Ready = ready("conversation-B2", "BBB2").copy(
            state = ready("conversation-B2", "BBB2").state.copy(
                sessionFile = "/private/android-B/conversation-B2.jsonl"
            )
        )
        val bRuntime = manager.runtime(records.single { it.androidSessionId == "android-B" })
        assertTrue(bRuntime.commitReady(b2Ready))
        records = records.map { record ->
            if (record.androidSessionId == "android-B") {
                bindPiConversation(record, b2Ready.state, b2Ready.state.sessionFile)
            } else record
        }
        bRuntime.update(records.single { it.androidSessionId == "android-B" })

        val afterResume = mapOf(
            "android-A" to ("conversation-A" to "AAA"),
            "android-B" to ("conversation-B2" to "BBB2"),
            "android-C" to ("conversation-C" to "CCC")
        )
        verifySwitches(afterResume)

        manager.close()
        manager = PiSessionRuntimeManager(RuntimeTestContext())
        val rebuiltReady = mapOf(
            "android-A" to ready("conversation-A", "AAA"),
            "android-B" to b2Ready,
            "android-C" to ready("conversation-C", "CCC")
        )
        records.forEach { record ->
            assertTrue(manager.runtime(record).commitReady(requireNotNull(rebuiltReady[record.androidSessionId])))
        }
        verifySwitches(afterResume)
        manager.close()
    }

    @Test
    fun anEmptyRegistryStartsWithItsDrawerOpen() {
        assertTrue(emptySessionDrawerInitiallyOpen(emptyList()))
        assertFalse(emptySessionDrawerInitiallyOpen(listOf(PiSessionRecord("a", "A", "/tmp", "pi", 17650, "token-a"))))
    }

    @Test
    fun deletingLastSessionClearsActiveIdAndKeepsEmptyDrawerAvailable() {
        val result = deletePiSessionState(
            records = listOf(PiSessionRecord("a", "A", "/tmp", "pi", 17650, "token-a")),
            deletedId = "a",
            activeId = "a"
        )
        assertTrue(result.remaining.isEmpty())
        assertEquals(null, result.activeId)
        assertTrue(result.keepDrawerOpen)

        val withRemaining = deletePiSessionState(
            records = listOf(
                PiSessionRecord("a", "A", "/tmp", "pi", 17650, "token-a"),
                PiSessionRecord("b", "B", "/tmp", "pi", 17651, "token-b")
            ),
            deletedId = "a",
            activeId = "a"
        )
        assertEquals(listOf("b"), withRemaining.remaining.map { it.androidSessionId })
        assertEquals("b", withRemaining.activeId)
        assertFalse(withRemaining.keepDrawerOpen)
    }
}

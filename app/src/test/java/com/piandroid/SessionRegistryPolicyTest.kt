package com.piandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRegistryPolicyTest {
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
        assertNotEquals(first.id, second.id)
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
            sessionFile = "/tmp/a.jsonl", piSessionId = "native-a",
            displayName = "A", ownedSessionFile = "/tmp/a.jsonl",
            sessionDirectory = "~/.pi/android/sessions/a/pi-sessions"
        )
        val renamed = renamePiSession(listOf(original), "a", "Renamed").single()
        assertEquals("Renamed", sessionDisplayName(renamed))
        assertEquals(original.cwd, renamed.cwd)
        assertEquals(original.sessionFile, renamed.sessionFile)
        assertEquals(original.piSessionId, renamed.piSessionId)
        assertEquals(original.port, renamed.port)
        assertEquals(original.token, renamed.token)

        val records = listOf(original, original.copy(id = "b", displayName = "B", pinned = true))
        val pinned = togglePiSessionPinned(records, "a")
        assertEquals(listOf("a", "b"), pinned.map { it.id })
        assertTrue(pinned.first().pinned)
    }

    @Test
    fun deletingCurrentSessionChoosesOnlyARemainingSession() {
        val records = listOf(
            PiSessionRecord("a", "A", "/tmp", "pi", 17650, "token-a"),
            PiSessionRecord("b", "B", "/tmp", "pi", 17651, "token-b")
        )
        val remaining = removePiSession(records, "a")
        assertEquals(listOf("b"), remaining.map { it.id })
        assertEquals("b", nextPiSessionIdAfterDelete(remaining, "a", "a"))
        assertEquals("b", nextPiSessionIdAfterDelete(remaining, "b", "b"))
        assertEquals(null, nextPiSessionIdAfterDelete(emptyList(), "a", "a"))
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
        assertEquals(listOf("b"), withRemaining.remaining.map { it.id })
        assertEquals("b", withRemaining.activeId)
        assertFalse(withRemaining.keepDrawerOpen)
    }
}

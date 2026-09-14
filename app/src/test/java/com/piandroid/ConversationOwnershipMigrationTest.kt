package com.piandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationOwnershipMigrationTest {
    @Test
    fun duplicatePiConversationIdAcrossDifferentPrivateFilesKeepsOnlyActiveOwner() {
        val sharedConversationId = "conversation-shared"
        val records = listOf(
            PiSessionRecord(
                androidSessionId = "android-A",
                name = "A",
                cwd = "/same/project",
                launchCommand = "pi",
                port = 17650,
                token = "token-a",
                sessionFile = "/data/data/com.termux/files/home/.pi/android/sessions/android-A/pi-sessions/a.jsonl",
                piConversationId = sharedConversationId,
                ownedSessionFile = "/data/data/com.termux/files/home/.pi/android/sessions/android-A/pi-sessions/a.jsonl",
                sessionDirectory = "~/.pi/android/sessions/android-A/pi-sessions"
            ),
            PiSessionRecord(
                androidSessionId = "android-B",
                name = "B",
                cwd = "/same/project",
                launchCommand = "pi",
                port = 17651,
                token = "token-b",
                sessionFile = "/data/data/com.termux/files/home/.pi/android/sessions/android-B/pi-sessions/b.jsonl",
                piConversationId = sharedConversationId,
                ownedSessionFile = "/data/data/com.termux/files/home/.pi/android/sessions/android-B/pi-sessions/b.jsonl",
                sessionDirectory = "~/.pi/android/sessions/android-B/pi-sessions"
            ),
            PiSessionRecord(
                androidSessionId = "android-C",
                name = "C",
                cwd = "/same/project",
                launchCommand = "pi",
                port = 17652,
                token = "token-c",
                sessionFile = "/data/data/com.termux/files/home/.pi/android/sessions/android-C/pi-sessions/c.jsonl",
                piConversationId = sharedConversationId,
                ownedSessionFile = "/data/data/com.termux/files/home/.pi/android/sessions/android-C/pi-sessions/c.jsonl",
                sessionDirectory = "~/.pi/android/sessions/android-C/pi-sessions"
            )
        )

        val repaired = isolatePiConversationOwnership(records, preferredAndroidSessionId = "android-B")
        val a = repaired.single { it.androidSessionId == "android-A" }
        val b = repaired.single { it.androidSessionId == "android-B" }
        val c = repaired.single { it.androidSessionId == "android-C" }

        assertEquals(sharedConversationId, b.piConversationId)
        assertTrue(b.sessionFile.endsWith("/android-B/pi-sessions/b.jsonl"))

        assertEquals("android-A", a.piConversationId)
        assertEquals("", a.sessionFile)
        assertEquals("", a.ownedSessionFile)
        assertFalse(a.legacySessionFile)

        assertEquals("android-C", c.piConversationId)
        assertEquals("", c.sessionFile)
        assertEquals("", c.ownedSessionFile)
        assertFalse(c.legacySessionFile)
    }

    @Test
    fun distinctPiConversationIdsRemainUntouchedEvenWithSameCwd() {
        val records = listOf(
            PiSessionRecord(
                androidSessionId = "android-A",
                name = "A",
                cwd = "/same/project",
                launchCommand = "pi",
                port = 17650,
                token = "token-a",
                sessionFile = "/data/data/com.termux/files/home/.pi/android/sessions/android-A/pi-sessions/a.jsonl",
                piConversationId = "conversation-A",
                ownedSessionFile = "/data/data/com.termux/files/home/.pi/android/sessions/android-A/pi-sessions/a.jsonl",
                sessionDirectory = "~/.pi/android/sessions/android-A/pi-sessions"
            ),
            PiSessionRecord(
                androidSessionId = "android-B",
                name = "B",
                cwd = "/same/project",
                launchCommand = "pi",
                port = 17651,
                token = "token-b",
                sessionFile = "/data/data/com.termux/files/home/.pi/android/sessions/android-B/pi-sessions/b.jsonl",
                piConversationId = "conversation-B",
                ownedSessionFile = "/data/data/com.termux/files/home/.pi/android/sessions/android-B/pi-sessions/b.jsonl",
                sessionDirectory = "~/.pi/android/sessions/android-B/pi-sessions"
            )
        )

        val repaired = isolatePiConversationOwnership(records, preferredAndroidSessionId = "android-A")

        assertEquals(records, repaired)
        assertNotEquals(repaired[0].piConversationId, repaired[1].piConversationId)
        assertNotEquals(repaired[0].sessionFile, repaired[1].sessionFile)
    }
}

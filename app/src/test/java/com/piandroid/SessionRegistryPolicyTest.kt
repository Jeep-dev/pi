package com.piandroid

import org.junit.Assert.assertEquals
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
}

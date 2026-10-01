package com.piandroid

import org.junit.Assert.*
import org.junit.Test

class CommandInputPolicyTest {
    private val commands = listOf(
        PiCommand("usage", "Codex quota", "extension"),
        PiCommand("review", "Review", "prompt"),
        PiCommand("skill:demo", "Demo", "skill"),
    )

    @Test fun ranksExactBeforePrefixBeforeSubstring() {
        assertEquals(0, commandMatchRank("usage", "USAGE"))
        assertEquals(1, commandMatchRank("usage", "u"))
        assertEquals(2, commandMatchRank("resume", "u"))
        assertEquals(3, commandMatchRank("model", "u"))
        assertTrue(commandMatchRank("usage", "u") < commandMatchRank("quit", "u"))
    }

    @Test fun resolvesExtensionCaseAndArguments() {
        assertEquals("/usage", extensionCommandInput("/Usage", commands))
        assertEquals("/usage", extensionCommandInput(" /USAGE ", commands))
        assertEquals("/usage details", extensionCommandInput("/Usage\t details", commands))
    }

    @Test fun doesNotDispatchMessagesTemplatesOrUnknownCommands() {
        assertNull(extensionCommandInput("hello usage", commands))
        assertNull(extensionCommandInput("/unknown", commands))
        assertNull(extensionCommandInput("/review", commands))
        assertNull(extensionCommandInput("/skill:demo", commands))
        assertNull(extensionCommandInput("", commands))
    }
}

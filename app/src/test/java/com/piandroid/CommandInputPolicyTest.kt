package com.piandroid

import org.junit.Assert.*
import org.junit.Test

class CommandInputPolicyTest {
    private val commands = listOf(
        PiCommand("usage", "Codex quota", "extension"),
        PiCommand("review", "Review", "prompt"),
        PiCommand("skill:demo", "Demo", "skill"),
    )

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

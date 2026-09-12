package com.piandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPresentationTest {
    @Test
    fun successfulEditIncludesNativeDiffWithoutTruncatingIt() {
        val diff = (1..24).joinToString("\n") { line ->
            if (line == 12) "+added line $line" else " $line unchanged"
        }

        val text = toolResultText(
            toolName = "edit",
            output = "Successfully applied 1 edit",
            isError = false,
            diff = diff
        )

        assertTrue(text.startsWith(" 1 unchanged"))
        assertFalse(text.contains("工具完成"))
        assertTrue(text.contains(" 24 unchanged"))
        assertTrue(text.contains("+added line 12"))
        assertTrue(text.endsWith("Successfully applied 1 edit"))
    }

    @Test
    fun editDiffIsNotDuplicatedWhenRuntimeAlreadyReturnedItAsText() {
        val diff = " 1 old\n+2 new"
        val text = toolResultText("edit", "Applied\n$diff", isError = false, diff = diff)

        assertEquals(1, Regex(Regex.escape(diff)).findAll(text).count())
    }

    @Test
    fun toolErrorsKeepTheRealErrorAndNeverAppendAStaleDiff() {
        val text = toolResultText(
            toolName = "edit",
            output = "oldText did not match",
            isError = true,
            diff = "+must not appear"
        )

        assertEquals("oldText did not match", text)
        assertFalse(text.contains("工具执行失败"))
        assertFalse(text.contains("must not appear"))
    }

    @Test
    fun collapsedToolPreviewShowsTailAndExplicitHiddenCount() {
        val output = (1..12).joinToString("\n") { "line $it" }

        assertEquals(
            (8..12).joinToString("\n") { "line $it" },
            toolOutputPreview(output)
        )
        assertEquals("… (7 earlier lines)", toolHiddenHint(output))
    }

    @Test
    fun toolDurationMatchesNativePiTenthsFormat() {
        assertEquals("0.0s", formatToolDuration(0))
        assertEquals("1.2s", formatToolDuration(1_249))
        assertEquals("12.3s", formatToolDuration(12_345))
    }

    @Test
    fun assistantErrorsAndEmptyAbortsHaveVisibleCompletionNotices() {
        assertEquals(
            "模型错误：provider unavailable",
            assistantCompletionNotice("error", "", "provider unavailable")
        )
        assertEquals("本轮任务已中止", assistantCompletionNotice("aborted", "", ""))
        assertEquals(null, assistantCompletionNotice("aborted", "partial answer", ""))
    }
}
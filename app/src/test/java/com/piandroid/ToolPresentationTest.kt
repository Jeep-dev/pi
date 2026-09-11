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

        assertTrue(text.startsWith("工具完成：edit\n\n 1 unchanged"))
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

        assertTrue(text.contains("工具执行失败：edit"))
        assertTrue(text.contains("oldText did not match"))
        assertFalse(text.contains("must not appear"))
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

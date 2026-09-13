package com.piandroid

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveredToolPresentationTest {
    @Test
    fun `legacy recovered bash args lose android chinese label`() {
        assertEquals("echo hello", normalizeRecoveredToolArgs("bash", "命令：echo hello"))
    }

    @Test
    fun `legacy recovered edit args match live compact presentation`() {
        assertEquals(
            "/tmp/a.kt  ·  2 edits",
            normalizeRecoveredToolArgs("edit", "目标：/tmp/a.kt\n修改块：2")
        )
    }

    @Test
    fun `legacy recovered write args match live compact presentation`() {
        assertEquals(
            "/tmp/a.txt  ·  12 chars",
            normalizeRecoveredToolArgs("write", "目标：/tmp/a.txt\n\n内容：12 字符\n\n写入预览：\nhello world!")
        )
    }

    @Test
    fun `json recovered read args match live presentation`() {
        assertEquals(
            "/tmp/a.txt  [offset 5, limit 20]",
            normalizeRecoveredToolArgs("read", "{\"path\":\"/tmp/a.txt\",\"offset\":5,\"limit\":20}")
        )
    }
}

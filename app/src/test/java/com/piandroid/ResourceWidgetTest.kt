package com.piandroid

import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceWidgetTest {
    @Test
    fun parsesNativeStyleSectionsAndDropsEmptyCategories() {
        assertEquals(
            listOf(
                LoadedResourceSection("Context", listOf("AGENTS.md")),
                LoadedResourceSection("Prompts", listOf("/review", "/ship")),
            ),
            parseLoadedResourceWidget(
                listOf(
                    "[Context]",
                    "  AGENTS.md",
                    "[Skills]",
                    "  ",
                    "[Prompts]",
                    "  /review, /ship",
                )
            )
        )
    }

    @Test
    fun supportsOneItemPerLineAndTrimsDuplicateItems() {
        assertEquals(
            listOf(LoadedResourceSection("Extensions", listOf("mobile.ts", "pi-exa"))),
            parseLoadedResourceWidget(
                listOf(
                    "[Extensions]",
                    "  mobile.ts",
                    "  pi-exa",
                    "  mobile.ts",
                )
            )
        )
    }
}

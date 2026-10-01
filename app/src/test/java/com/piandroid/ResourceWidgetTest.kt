package com.piandroid

import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceWidgetTest {
    @Test
    fun hidesUsageFromExtensionsOnly() {
        assertEquals(
            listOf(
                LoadedResourceSection("Extensions", listOf("pi-android-mobile.ts", "pi-exa")),
                LoadedResourceSection("Prompts", listOf("/usage")),
            ),
            parseLoadedResourceWidget(listOf(
                "[Extensions]",
                "codex-usage.ts, pi-android-mobile.ts, /home/user/.pi/agent/extensions/codex-usage.ts, pi-exa",
                "[Prompts]",
                "/usage",
            ))
        )
        assertEquals(false, visibleExtensionLabel("codex-usage.ts"))
        assertEquals(true, visibleExtensionLabel("pi-exa"))
    }

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

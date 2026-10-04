package com.piandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMathTest {
    private fun inline(text: String): List<String> {
        val found = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val math = findInlineMath(text, index)
            if (math != null) { found += math.latex; index = math.end } else index++
        }
        return found
    }

    @Test fun dollarsAroundFormulaAreMath() {
        assertEquals(listOf("N = 3", "r_3 = \\frac{\\sqrt{3}}{2} \\approx 0.866"), inline("\$N = 3\$：小圆，\$r_3 = \\frac{\\sqrt{3}}{2} \\approx 0.866\$"))
    }

    @Test fun pricesStayText() {
        assertEquals(emptyList<String>(), inline("costs \$5 and \$10 today"))
        assertEquals(emptyList<String>(), inline("US\$5 or US\$6"))
        assertEquals(emptyList<String>(), inline("pay \$ 5 \$"))
        assertEquals(emptyList<String>(), inline("\$5 和 \$10 不变，`\$x` 也不变"))
    }

    @Test fun otherDelimiters() {
        assertEquals(listOf("a+b", "x^2", "\\sum_i i"), inline("\\(a+b\\) then \\[x^2\\] and \$\$\\sum_i i\$\$"))
    }

    @Test fun escapedAndUnclosedDollarsStayText() {
        assertEquals(emptyList<String>(), inline("\\\$x\$ y"))
        assertEquals(emptyList<String>(), inline("still typing \$r_3 = \\frac{"))
    }

    @Test fun displayBlocks() {
        val lines = listOf("before", "\$\$", "E = mc^2", "\$\$", "after")
        val block = displayMathAt(lines, 1)!!
        assertEquals("E = mc^2", block.latex)
        assertTrue(block.closed)
        assertEquals(4, block.nextLine)
        assertNull(displayMathAt(lines, 0))
        assertEquals("x", displayMathAt(listOf("\\[ x \\]"), 0)!!.latex)
        assertNull(displayMathAt(listOf("\$\$a\$\$ and more"), 0))
    }

    @Test fun streamingDisplayBlockIsOpen() {
        val block = displayMathAt(listOf("\$\$", "\\frac{a"), 0)!!
        assertFalse(block.closed)
        assertEquals(2, block.nextLine)
    }
}

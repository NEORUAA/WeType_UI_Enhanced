package com.xposed.wetypehook.wetype.graphics

import androidx.core.graphics.PathParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgPathNormalizerTest {

    @Test
    fun compactArcFlagsAreSplitOutSoTheArcKeepsSevenParameters() {
        val arc = PathParser.createNodesFromPathData(QINIU_PATH).first { it.type == 'A' }

        assertEquals(5, arc.params.size)

        val normalizedArc = PathParser.createNodesFromPathData(SvgPathNormalizer.normalize(QINIU_PATH))
            .first { it.type == 'A' }

        assertEquals(7, normalizedArc.params.size)
        assertEquals(13.443f, normalizedArc.params[0], DELTA)
        assertEquals(13.443f, normalizedArc.params[1], DELTA)
        assertEquals(0f, normalizedArc.params[2], DELTA)
        assertEquals(0f, normalizedArc.params[3], DELTA)
        assertEquals(1f, normalizedArc.params[4], DELTA)
        assertEquals(7.947f, normalizedArc.params[5], DELTA)
        assertEquals(8.897f, normalizedArc.params[6], DELTA)
    }

    @Test
    fun normalizedRealWorldPathsAlignEveryCommandWithItsParameterCount() {
        listOf(QINIU_PATH, XIAOMI_PATH, QINIU_PATH.replace('A', 'a')).forEach { path ->
            val nodes = PathParser.createNodesFromPathData(SvgPathNormalizer.normalize(path))
            assertTrue(nodes.isNotEmpty())
            nodes.forEach { node ->
                val count = PARAM_COUNTS[node.type.lowercaseChar()] ?: return@forEach
                if (count > 0) {
                    assertEquals(
                        "command ${node.type} must hold a whole number of $count-parameter groups",
                        0,
                        node.params.size % count,
                    )
                }
            }
        }
    }

    @Test
    fun alreadySeparatedArcFlagsAreNotSplitAgain() {
        val separated = "M0 0A10 10 0 0 1 10 10"
        val normalized = SvgPathNormalizer.normalize(separated)

        val arc = PathParser.createNodesFromPathData(normalized).first { it.type == 'A' }

        assertEquals(7, arc.params.size)
        assertEquals(1f, arc.params[4], DELTA)
        assertEquals(10f, arc.params[5], DELTA)
        assertEquals(10f, arc.params[6], DELTA)
    }

    @Test
    fun numbersWrittenBackToBackStaySeparate() {
        val node = PathParser.createNodesFromPathData(
            SvgPathNormalizer.normalize("M0 0C2.674 5.729 1.263 4.472.89 4.6")
        ).first { it.type == 'C' }

        assertEquals(6, node.params.size)
        assertEquals(4.472f, node.params[3], DELTA)
        assertEquals(0.89f, node.params[4], DELTA)
        assertEquals(4.6f, node.params[5], DELTA)
    }

    @Test
    fun normalizationIsIdempotent() {
        val once = SvgPathNormalizer.normalize(QINIU_PATH)

        assertEquals(once, SvgPathNormalizer.normalize(once))
    }

    @Test
    fun exponentNotationIsNotTreatedAsACommand() {
        val node = PathParser.createNodesFromPathData(
            SvgPathNormalizer.normalize("M0 0L1e2 -1.5E-3 3 4")
        ).first { it.type == 'L' }

        assertEquals(4, node.params.size)
        assertEquals(100f, node.params[0], DELTA)
        assertEquals(-0.0015f, node.params[1], DELTA)
    }

    @Test
    fun blankInputIsReturnedUnchanged() {
        assertEquals("", SvgPathNormalizer.normalize(""))
        assertEquals("   ", SvgPathNormalizer.normalize("   "))
    }

    private companion object {
        const val DELTA = 0.0001f

        /** 来自 SVG 规范的参数个数表，独立于生产实现，作为对齐校验的基准。 */
        val PARAM_COUNTS = mapOf(
            'm' to 2, 'l' to 2, 'h' to 1, 'v' to 1, 'c' to 6, 's' to 4,
            'q' to 4, 't' to 2, 'a' to 7, 'z' to 0,
        )

        const val QINIU_PATH =
            "M23.111 4.6a.914.914 0 00-.861.161A13.443 13.443 0 017.947 8.897L7.38 6.831" +
                "a1.076 1.076 0 00-1.211-.698l.27 2.18c-1.816-.827-2.313-.946-3.587-2.45" +
                "C2.674 5.729 1.263 4.472.89 4.6a11.906 11.906 0 005.892 6.497l.738 5.97" +
                "s.33 2.286 2.473 2.286h4.586c2.144 0 2.474-2.286 2.474-2.286l.518-4.28" +
                "c-1.393-.11-2.268.857-2.546 1.814-.465 1.614-.465 1.716-.557 1.998" +
                "-.188.575-.806.644-.806.644h-2.753s-.617-.07-.806-.644c-.12-.371-.727-2.54" +
                "-1.335-4.74A11.877 11.877 0 0023.11 4.599V4.6z"

        const val XIAOMI_PATH =
            "m123.3 1.1c-37.1 1.7-59.5 7.5-78.6 20.3-13.6 9-23.6 20.7-30.8 36.1" +
                "-7.9 16.9-11.4 35.5-12.9 70.1-1.8 38.7.8 77.3 6.6 97.8 5.2 18.6 13.5 32.8" +
                " 26.2 44.7 15.7 14.6 33.3 22.3 60.6 26.4 25 3.7 82.9 3.9 108.2.4" +
                " 30.8-4.2 50.4-12.9 66.5-29.6 8-8.2 11.7-13.6 16.9-24.5 8.1-17.1 11.4-34.8" +
                " 13-70.2 1.2-25.3 0-61.3-2.5-78.1-5.9-39.4-22.7-65-52-79.8-15.5-7.7-38.3-12.2" +
                "-70-13.7-23.9-1.1-27.1-1.1-51.2.1zm36.9 93.2c13.2 3.1 19.6 7.5 23.8 16.5" +
                " 4.3 9.2 5 16.8 5 58.3v39l-14.2-.3-14.3-.3-.5-38c-.5-41.5-.7-42.8-6.5-47.3" +
                "-5.2-4.1-9.6-4.6-38-4.7h-27l-.5 45-.5 45h-28l-.3-56.9c-.2-44.9.1-57.2 1-57.8" +
                ".7-.5 22-.7 47.3-.5 37.1.4 47.3.8 52.7 2zm78.6-.6c.7 1.4 1 110.1.3 113.6" +
                " 0 .4-6.5.6-14.3.5l-14.3-.3-.3-56.4c-.1-40.8.1-56.8.9-57.7.9-1.1 4.4-1.4 14-1.4" +
                " 11.2 0 12.9.2 13.7 1.7zm-99.8 78.8v35.6l-14.7-.3-14.8-.3-.3-34c-.1-18.7 0-34.6" +
                ".3-35.2.3-1 4.2-1.3 15-1.3h14.5z"
    }
}

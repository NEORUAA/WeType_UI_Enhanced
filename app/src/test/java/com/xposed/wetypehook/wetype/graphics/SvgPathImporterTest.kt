package com.xposed.wetypehook.wetype.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgPathImporterTest {
    @Test
    fun plainPathsUseTheSvgNonzeroDefault() {
        val entry = SvgPathImporter.extractPaths("""
            <svg xmlns="http://www.w3.org/2000/svg"><path d="M0 0H20V20H0Z M5 5H15V15H5Z"/></svg>
        """).single()

        assertFalse(entry.evenOdd)
        assertEquals("M0 0H20V20H0Z M5 5H15V15H5Z", entry.pathData)
        assertMatrix(listOf(1f, 0f, 0f, 1f, 0f, 0f), entry.transform)
    }

    @Test
    fun nestedTransformsComposeInDocumentOrderAndDoNotLeakToSiblings() {
        val entries = SvgPathImporter.extractPaths("""
            <svg>
                <g transform="translate(10 20) scale(2)">
                    <path transform="rotate(90)" d="M0 0H10V10Z"/>
                </g>
                <path transform="translate(30)" d="M0 0H10V10Z"/>
            </svg>
        """)

        assertMatrix(listOf(0f, 2f, -2f, 0f, 10f, 20f), entries[0].transform)
        assertMatrix(listOf(1f, 0f, 0f, 1f, 30f, 0f), entries[1].transform)
    }

    @Test
    fun rotationAboutAPointPreservesItsPivot() {
        val entry = SvgPathImporter.extractPaths("""
            <svg><path transform="translate(10,-20) scale(2) rotate(90 1 1)" d="M0 0H10V10Z"/></svg>
        """).single()

        assertMatrix(listOf(0f, 2f, -2f, 0f, 14f, -20f), entry.transform)
    }

    @Test
    fun matrixSkewAndExponentArgumentsKeepTheirGeometry() {
        val entry = SvgPathImporter.extractPaths("""
            <svg><path transform="matrix(1 0 0 1 1e1 -2e1) skewX(45) skewY(45)" d="M0 0H10V10Z"/></svg>
        """).single()

        assertMatrix(listOf(2f, 1f, 1f, 1f, 10f, -20f), entry.transform)
    }

    @Test
    fun fillRulesInheritAndAllowPathAndInlineStyleOverrides() {
        val entries = SvgPathImporter.extractPaths("""
            <svg fill-rule="evenodd"><g>
                <path d="M0 0H10V10Z"/>
                <path fill-rule="nonzero" d="M0 0H10V10Z"/>
                <path fill-rule="nonzero" style="fill-rule: nonzero; fill-rule: evenodd" d="M0 0H10V10Z"/>
                <path d="M0 0H10V10Z"/>
            </g></svg>
        """)

        assertEquals(listOf(true, false, true, true), entries.map { it.evenOdd })
    }

    @Test
    fun maskClipAndDefinitionPathsAreNotForegroundArtwork() {
        val entries = SvgPathImporter.extractPaths("""
            <svg fill="none">
                <mask id="mask"><path fill="white" d="M0 0H512V512H0Z"/></mask>
                <g mask="url(#mask)">
                    <path fill="white" d="M10 10H30V30H10Z"/>
                    <path fill="white" fill-opacity="0.5" d="M15 15H25V25H15Z"/>
                    <path d="M0 0H100V100H0Z"/>
                </g>
                <defs><g><path fill="white" d="M0 0H999V999H0Z"/></g></defs>
                <clipPath id="clip"><path fill="white" d="M0 0H512V512H0Z"/></clipPath>
                <g display="none"><path fill="white" d="M0 0H999V999H0Z"/></g>
            </svg>
        """)

        assertEquals(listOf("M10 10H30V30H10Z", "M15 15H25V25H15Z"), entries.map { it.pathData })
        assertEquals(listOf(1f, 0.5f), entries.map { it.fillOpacity })
    }

    @Test
    fun fillOpacityInheritsWithoutMultiplyingAndInlineStyleOverridesAttributes() {
        val entries = SvgPathImporter.extractPaths("""
            <svg fill-opacity="0.5"><g fill-opacity="0.25">
                <path d="M0 0H10V10Z"/>
                <path fill-opacity="0.75" style="fill-opacity: 50%" d="M0 0H10V10Z"/>
                <path fill-opacity="inherit" d="M0 0H10V10Z"/>
                <path fill-opacity="0" d="M0 0H10V10Z"/>
            </g><path d="M0 0H10V10Z"/></svg>
        """)

        assertEquals(listOf(0.25f, 0.5f, 0.25f, 0f, 0.5f), entries.map { it.fillOpacity })
    }

    @Test
    fun malformedDocumentsAndTransformsAreRejectedInsteadOfChangingTheIcon() {
        listOf(
            "<svg><path d='M0 0'/>",
            "<svg><path transform='rotate(90 1)' d='M0 0'/></svg>",
            "<svg><path transform='translate(NaN)' d='M0 0'/></svg>",
            "<svg><path transform='translate(1) invalid' d='M0 0'/></svg>"
        ).forEach { assertTrue(it, SvgPathImporter.extractPaths(it).isEmpty()) }
    }

    private fun assertMatrix(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (want, value) -> assertEquals(want, value, 0.0001f) }
    }
}

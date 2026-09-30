package com.xposed.wetypehook.wetype.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class WeTypeIconColorMigrationTest {
    @Test
    fun legacyLogoColorIncludingAlphaIsInherited() {
        val oldColor = 0x80FF0000.toInt()
        val colors = readColors(mapOf("appearance_color_theme_color" to oldColor))

        assertEquals(oldColor, colors[ICON_COLOR_GROUP_ID])
    }

    @Test
    fun savedIconColorStaysIndependentWhenTheThemeChanges() {
        val colors = readColors(mapOf(
            "appearance_color_theme_color" to 0xFFFF0000.toInt(),
            "appearance_color_icon_color" to 0xFF0000FF.toInt()
        ))

        assertEquals(0xFF0000FF.toInt(), colors[ICON_COLOR_GROUP_ID])
    }

    @Test
    fun explicitlyTransparentIconColorIsNotTreatedAsMissing() {
        val colors = readColors(mapOf(
            "appearance_color_theme_color" to 0xFFFF0000.toInt(),
            "appearance_color_icon_color" to 0
        ))

        assertEquals(0, colors[ICON_COLOR_GROUP_ID])
    }

    @Test
    fun freshInstallKeepsTheBrandColor() {
        val colors = readColors(emptyMap())

        assertEquals(WeTypeAppearanceColorGroups.findById(ICON_COLOR_GROUP_ID)?.defaultColor,
            colors[ICON_COLOR_GROUP_ID])
    }

    private fun readColors(values: Map<String, Any>): Map<String, Int> =
        with(WeTypeSettings) { values.toSnapshot().appearanceColors }
}

package com.xposed.wetypehook.wetype.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WeTypeSettingsTest {
    @Test
    fun legacySettingsMigrateBothProfilesWithTheirOriginalBackgrounds() {
        val glass = GlassMaterialOverrides().withGlassEnabled(true)
        val glassParameters = requireNotNull(glass.glass)
        val bloomParameters = requireNotNull(glass.bloom)
        val blurRadii = requireNotNull(glass.blurRadii)
        val values = mutableMapOf<String, Any>(
            "light_color" to 0x10203040,
            "dark_color" to 0x50607080,
            "blur_radius" to 22,
            "corner_radius" to 14,
            "key_corner_radius" to 7,
            "edge_highlight_enabled" to false,
            "edge_highlight_intensity" to 120,
            "key_color_hook_alpha" to 37,
            "candidate_background_corner" to 11f,
            "candidate_background_left_margin_dp" to 12,
            "candidate_pinyin_left_margin_dp" to 19,
            "toolbar_icon_bg_opacity" to 30,
            "custom_icon_path" to "M0 0h10v10H0z",
            "icon_scale" to 1.2f,
            "hyper_material_enabled" to true,
            "disable_hot_update" to false,
            "key_opacity" to 64,
            "appearance_color_theme_color" to 0x80123456.toInt(),
            "glass_params_count" to glassParameters.size,
            "glass_bloom_count" to bloomParameters.size,
            "glass_blur_small" to blurRadii[0],
            "glass_blur_large" to blurRadii[1],
            "glass_material_type" to glass.materialType!!
        )
        glassParameters.forEachIndexed { index, value -> values["glass_params_$index"] = value }
        bloomParameters.forEachIndexed { index, value -> values["glass_bloom_$index"] = value }

        val profiles = readProfiles(values)
        val expectedColors = WeTypeAppearanceColorGroups.defaultColors() + mapOf(
            "theme_color" to 0x80123456.toInt(),
            ICON_COLOR_GROUP_ID to 0x80123456.toInt(),
            LIGHT_KEY_COLOR_GROUP_ID to 0x40FCFCFE,
            DARK_KEY_COLOR_GROUP_ID to 0x40707070
        )
        val expected = WeTypeSettings.defaultSnapshot(false).copy(
            backgroundColor = 0x10203040,
            blurRadius = 22,
            cornerRadius = 14,
            keyCornerRadius = 7,
            edgeHighlightEnabled = false,
            edgeHighlightIntensity = 120,
            candidateBackgroundAlpha = 37,
            candidateBackgroundCorner = 11f,
            candidateBackgroundLeftMarginDp = 12,
            candidatePinyinLeftMarginDp = 19,
            toolbarIconBgOpacity = 30,
            customIconPath = "M0 0h10v10H0z",
            iconScale = 1.2f,
            appearanceColors = expectedColors,
            hyperMaterialEnabled = true,
            glassOverrides = glass
        )
        assertEquals(expected, profiles.light)
        assertEquals(expected.copy(backgroundColor = 0x50607080), profiles.dark)
        assertFalse(profiles.disableHotUpdate)
    }

    @Test
    fun completeProfilesRoundTripIndependently() {
        val light = WeTypeSettings.defaultSnapshot(false).copy(
            backgroundColor = 0x12345678,
            blurRadius = 10,
            cornerRadius = 12,
            keyCornerRadius = 8,
            edgeHighlightEnabled = false,
            edgeHighlightIntensity = 24,
            candidateBackgroundAlpha = 50,
            candidateBackgroundCorner = 25f,
            candidateBackgroundLeftMarginDp = 14,
            candidatePinyinLeftMarginDp = 20,
            toolbarIconBgOpacity = 80,
            customIconPath = "M0 0h10v10H0z",
            iconScale = 0.8f,
            appearanceColors = WeTypeAppearanceColorGroups.groups.associate { it.id to 0x12345678 },
            hyperMaterialEnabled = true,
            glassOverrides = GlassMaterialOverrides().withGlassEnabled(true)
        )
        val dark = WeTypeSettings.defaultSnapshot(true).copy(
            backgroundColor = 0x60708090,
            blurRadius = 90,
            cornerRadius = 40,
            keyCornerRadius = 30,
            edgeHighlightEnabled = true,
            edgeHighlightIntensity = 180,
            candidateBackgroundAlpha = 240,
            candidateBackgroundCorner = 49f,
            candidateBackgroundLeftMarginDp = 40,
            candidatePinyinLeftMarginDp = 50,
            toolbarIconBgOpacity = 220,
            customIconPath = "M0 0h20v20H0z",
            iconScale = 1.3f,
            appearanceColors = WeTypeAppearanceColorGroups.groups.associate { it.id to 0x60708090 },
            hyperMaterialEnabled = false,
            glassOverrides = GlassMaterialOverrides(blurRadii = listOf(32, 8), materialType = 0)
        )
        val profiles = WeTypeSettings.Profiles(light, dark, disableHotUpdate = false)
        val values = preferenceValues(profiles)
        val restored = readProfiles(values)

        assertEquals(profiles, restored)
        assertEquals(light, restored.profile(false))
        assertEquals(dark, restored.profile(true))
        assertEquals(1, values.keys.count { it.endsWith("disable_hot_update") })
        assertFalse(restored.disableHotUpdate)
    }

    @Test
    fun savingOneProfilePreservesTheOtherProfileAndGlobalPreference() {
        val original = WeTypeSettings.defaultProfiles().copy(disableHotUpdate = false)
        val edited = original.copy(light = original.light.copy(blurRadius = 15, customIconPath = "M1 2L3 4"))
        val restored = readProfiles(preferenceValues(edited))

        assertEquals(edited.light, restored.light)
        assertEquals(original.dark, restored.dark)
        assertFalse(restored.disableHotUpdate)
        assertEquals(original.light, readProfiles(preferenceValues(original.copy(disableHotUpdate = true))).light)
    }

    @Test
    fun transparentColorsAndZeroOpacityRoundTripWithoutDefaults() {
        val transparent = WeTypeSettings.defaultSnapshot(false).copy(
            backgroundColor = 0,
            candidateBackgroundAlpha = 0,
            toolbarIconBgOpacity = 0,
            appearanceColors = WeTypeAppearanceColorGroups.groups.associate { it.id to 0 }
        )
        val profiles = WeTypeSettings.Profiles(transparent, transparent)

        assertEquals(profiles, readProfiles(preferenceValues(profiles)))
    }

    @Test
    fun clearedGlassOverridesNeverReviveLegacyValues() {
        val profiles = WeTypeSettings.defaultProfiles()
        val legacy = mapOf<String, Any>(
            "glass_params_count" to 42,
            "glass_blur_small" to 60,
            "glass_blur_large" to 16,
            "glass_material_type" to 1,
            "custom_icon_path" to "old-icon",
            "blur_radius" to 5
        ) + List(42) { "glass_params_$it" to 1f }
        val restored = readProfiles(legacy + preferenceValues(profiles))

        assertEquals(profiles, restored)
        assertEquals(GlassMaterialOverrides(), restored.light.glassOverrides)
        assertEquals(GlassMaterialOverrides(), restored.dark.glassOverrides)
    }

    @Test
    fun legacyOpacityOnlyChangesOpaqueKeyColors() {
        val profiles = readProfiles(mapOf(
            "key_opacity" to 25,
            "appearance_color_$LIGHT_KEY_COLOR_GROUP_ID" to 0,
            "appearance_color_$DARK_KEY_COLOR_GROUP_ID" to 0xFF123456.toInt()
        ))

        for (snapshot in listOf(profiles.light, profiles.dark)) {
            assertEquals(0, snapshot.appearanceColors[LIGHT_KEY_COLOR_GROUP_ID])
            assertEquals(0x19123456, snapshot.appearanceColors[DARK_KEY_COLOR_GROUP_ID])
        }
        val previouslyMigrated = readProfiles(mapOf(
            "key_opacity" to 25,
            "key_opacity_migrated" to true,
            "appearance_color_$DARK_KEY_COLOR_GROUP_ID" to 0xFF123456.toInt()
        ))
        assertEquals(0xFF123456.toInt(), previouslyMigrated.light.appearanceColors[DARK_KEY_COLOR_GROUP_ID])
    }

    @Test
    fun legacyLogoColorIncludingAlphaIsInherited() {
        val oldColor = 0x80FF0000.toInt()
        val profiles = readProfiles(mapOf("appearance_color_theme_color" to oldColor))

        assertEquals(oldColor, profiles.light.appearanceColors[ICON_COLOR_GROUP_ID])
        assertEquals(oldColor, profiles.dark.appearanceColors[ICON_COLOR_GROUP_ID])
    }

    @Test
    fun savedIconColorStaysIndependentWhenTheThemeChanges() {
        val profiles = readProfiles(mapOf(
            "appearance_color_theme_color" to 0xFFFF0000.toInt(),
            "appearance_color_icon_color" to 0xFF0000FF.toInt()
        ))

        assertEquals(0xFF0000FF.toInt(), profiles.light.appearanceColors[ICON_COLOR_GROUP_ID])
        assertEquals(0xFF0000FF.toInt(), profiles.dark.appearanceColors[ICON_COLOR_GROUP_ID])
    }

    @Test
    fun explicitlyTransparentLegacyIconColorIsNotTreatedAsMissing() {
        val profiles = readProfiles(mapOf(
            "appearance_color_theme_color" to 0xFFFF0000.toInt(),
            "appearance_color_icon_color" to 0
        ))

        assertEquals(0, profiles.light.appearanceColors[ICON_COLOR_GROUP_ID])
        assertEquals(0, profiles.dark.appearanceColors[ICON_COLOR_GROUP_ID])
    }

    @Test
    fun freshInstallKeepsDefaultsForBothProfiles() {
        val profiles = readProfiles(emptyMap())

        assertEquals(WeTypeSettings.defaultProfiles(), profiles)
        assertTrue(profiles.disableHotUpdate)
    }

    @Test
    fun followingColorsUseUpdatedSourceArgbWithoutChangingIndependentSettings() {
        val light = WeTypeSettings.defaultSnapshot(false).copy(
            backgroundColor = 0x40123456,
            appearanceColors = WeTypeAppearanceColorGroups.defaultColors() + (ICON_COLOR_GROUP_ID to 0)
        )
        val dark = WeTypeSettings.defaultSnapshot(true).copy(
            blurRadius = 13,
            followingColors = setOf(WeTypeSettings.BACKGROUND_COLOR_ID, ICON_COLOR_GROUP_ID)
        )
        val profiles = WeTypeSettings.Profiles(light, dark)
        val resolved = profiles.profile(true)

        assertEquals(light.backgroundColor, resolved.backgroundColor)
        assertEquals(0, resolved.appearanceColors[ICON_COLOR_GROUP_ID])
        assertEquals(13, resolved.blurRadius)
        assertEquals(dark, profiles.dark)
        assertSame(resolved, profiles.profile(true))
        assertSame(resolved.appearanceColors, profiles.profile(true).appearanceColors)

        val changed = profiles.copy(light = light.copy(
            backgroundColor = 0,
            appearanceColors = light.appearanceColors + (ICON_COLOR_GROUP_ID to 0x80112233.toInt())
        ))
        assertEquals(0, changed.profile(true).backgroundColor)
        assertEquals(0x80112233.toInt(), changed.profile(true).appearanceColors[ICON_COLOR_GROUP_ID])
        assertEquals(profiles.light.backgroundColor, profiles.profile(true).backgroundColor)
    }

    @Test
    fun keyColorFollowingMapsTheOppositeModesKeyIdInBothDirections() {
        for (isDark in listOf(false, true)) {
            val targetId = if (isDark) DARK_KEY_COLOR_GROUP_ID else LIGHT_KEY_COLOR_GROUP_ID
            val sourceId = WeTypeSettings.oppositeColorId(targetId)
            val source = WeTypeSettings.defaultSnapshot(!isDark).copy(
                appearanceColors = WeTypeAppearanceColorGroups.defaultColors() + mapOf(
                    sourceId to 0x60112233,
                    targetId to 0x7099AABB
                )
            )
            val target = WeTypeSettings.defaultSnapshot(isDark).copy(followingColors = setOf(targetId))
            val profiles = if (isDark) WeTypeSettings.Profiles(source, target)
                else WeTypeSettings.Profiles(target, source)

            assertEquals(0x60112233, profiles.profile(isDark).appearanceColors[targetId])
            assertEquals(target.appearanceColors[sourceId], profiles.profile(isDark).appearanceColors[sourceId])
        }
        assertEquals(ICON_COLOR_GROUP_ID, WeTypeSettings.oppositeColorId(ICON_COLOR_GROUP_ID))
        assertEquals(WeTypeSettings.BACKGROUND_COLOR_ID, WeTypeSettings.oppositeColorId(WeTypeSettings.BACKGROUND_COLOR_ID))
    }

    @Test
    fun followingColorsRoundTripAndCorruptMutualLinksKeepLightAsTheSource() {
        val profiles = WeTypeSettings.defaultProfiles().let { defaults ->
            defaults.copy(
                light = defaults.light.copy(followingColors = setOf(ICON_COLOR_GROUP_ID)),
                dark = defaults.dark.copy(followingColors = setOf(WeTypeSettings.BACKGROUND_COLOR_ID, DARK_KEY_COLOR_GROUP_ID))
            )
        }
        val restored = readProfiles(preferenceValues(profiles))
        assertEquals(profiles, restored)
        assertEquals(profiles.profile(false), restored.profile(false))
        assertEquals(profiles.profile(true), restored.profile(true))

        val corrupted = preferenceValues(profiles) + mapOf(
            "appearance_light_following_colors" to "background_color,$LIGHT_KEY_COLOR_GROUP_ID,unknown",
            "appearance_dark_following_colors" to "background_color,$DARK_KEY_COLOR_GROUP_ID,unknown"
        )
        val repaired = readProfiles(corrupted)
        assertTrue(repaired.light.followingColors.isEmpty())
        assertEquals(setOf(WeTypeSettings.BACKGROUND_COLOR_ID, DARK_KEY_COLOR_GROUP_ID), repaired.dark.followingColors)
        assertEquals(repaired.light.backgroundColor, repaired.profile(true).backgroundColor)
        assertEquals(repaired.light.appearanceColors[LIGHT_KEY_COLOR_GROUP_ID],
            repaired.profile(true).appearanceColors[DARK_KEY_COLOR_GROUP_ID])
        assertEquals(repaired, readProfiles(preferenceValues(repaired)))
    }

    private fun readProfiles(values: Map<String, Any>): WeTypeSettings.Profiles =
        with(WeTypeSettings) { values.toProfiles() }

    private fun preferenceValues(profiles: WeTypeSettings.Profiles): Map<String, Any> =
        with(WeTypeSettings) { profiles.toPreferenceValues() }
}

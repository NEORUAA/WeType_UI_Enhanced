package com.xposed.wetypehook.wetype.settings

import androidx.annotation.StringRes
import com.xposed.wetypehook.R

const val LIGHT_KEY_COLOR_GROUP_ID = "transparent_h6"
const val DARK_KEY_COLOR_GROUP_ID = "transparent_l0"

data class WeTypeAppearanceColorGroup(
    val id: String,
    val displayName: String,
    val defaultColor: Int,
    val colorResourceNames: Set<String> = emptySet(),
    val themeAttributeNames: Set<String> = emptySet()
) {
    val entryCount: Int
        get() = colorResourceNames.size + themeAttributeNames.size

    val isKeyColorGroup: Boolean
        get() = id == LIGHT_KEY_COLOR_GROUP_ID || id == DARK_KEY_COLOR_GROUP_ID
}

object WeTypeAppearanceColorGroups {
    val groups: List<WeTypeAppearanceColorGroup> = listOf(
        WeTypeAppearanceColorGroup(
            id = "theme_color",
            displayName = "品牌强调色",
            defaultColor = 0xFF23c891.toInt(),
            colorResourceNames = setOf(
                "Brand", "Brand_100", "Brand_100_CARE", "Brand_120", "Brand_170",
                "Brand_80", "Brand_80_CARE", "Brand_90", "Brand_90_CARE",
                "Brand_Alpha_0_1", "Brand_Alpha_0_2", "Brand_Alpha_0_2_CARE",
                "Brand_Alpha_0_3", "Brand_Alpha_0_5", "Brand_BG_100",
                "Brand_BG_100_CARE", "Brand_BG_110", "Brand_BG_130", "Brand_BG_90",
                "Green", "Green_100", "Green_100_CARE", "Green_120", "Green_170",
                "Green_80", "Green_80_CARE", "Green_90", "Green_90_CARE",
                "Green_BG_100", "Green_BG_110", "Green_BG_130", "Green_BG_90",
                "LightGreen", "LightGreen_100", "LightGreen_100_CARE", "LightGreen_120",
                "LightGreen_170", "LightGreen_80", "LightGreen_80_CARE",
                "LightGreen_90", "LightGreen_90_CARE", "LightGreen_BG_100",
                "LightGreen_BG_110", "LightGreen_BG_130", "LightGreen_BG_90",
                "abc_search_url_text_normal", "btn_green_text_color", "ime_candidate_content_select_bg_color_dark",
                "ime_candidate_content_select_bg_color_light", "ime_candidate_syllable_select_bg_color_dark", "ime_candidate_syllable_select_bg_color_light",
                "ime_color_14_Alpha_10", "ime_color_14_Alpha_10_light",
                "ime_color_14_Alpha_20", "ime_color_14_Alpha_20_light",
                "ime_color_14_Alpha_30", "ime_color_14_Alpha_30_light",
                "ime_color_14_Alpha_50_dark", "ime_color_14_Alpha_50_light",
                "ime_color_14_dark", "ime_color_14_light",
                "ime_skin_candidate_content_select_bg_color", "ime_skin_color_14", "ime_skin_color_14_Alpha_20",
                "ime_skin_color_14_Alpha_50", "ime_skin_color_19", "ime_skin_color_btn_white_text",
                "ime_skin_color_btn_white_text_pressed", "ime_skin_color_divider", "ime_skin_color_emoji_enter_btn_bg_disabled", "ime_skin_dark_Brand_90",
                "ime_skin_dark_candidate_content_select_bg_color", "ime_skin_dark_color_14", "ime_skin_dark_color_14_Alpha_20", "ime_skin_dark_color_14_Alpha_50",
                "ime_skin_dark_color_emoji_enter_btn_bg_disabled", "level_best_color", "level_middle_color", "level_normal_color", "material_deep_teal_500", "toasterro"
            )
        ),
        WeTypeAppearanceColorGroup(
            id = LIGHT_KEY_COLOR_GROUP_ID,
            displayName = "浅色模式按键色",
            defaultColor = 0xFFfcfcfe.toInt(),
            colorResourceNames = setOf("ime_skin_color_12")
        ),
        WeTypeAppearanceColorGroup(
            id = DARK_KEY_COLOR_GROUP_ID,
            displayName = "深色模式按键色",
            defaultColor = 0xFF707070.toInt(),
            colorResourceNames = setOf("ime_skin_dark_color_12")
        )
    )

    val obsoleteGroupIds: Set<String> = setOf(
        "transparent",
        "dark_gray",
        "accent_blue",
        "accent_blue_overlay",
        "medium_gray",
        "pale_blue",
        "pale_surface",
        "off_white",
        "white"
    )

    private val groupsById = groups.associateBy { it.id }
    private val colorNameToGroup = groups.flatMap { group ->
        group.colorResourceNames.map { name -> name to group }
    }.toMap()
    private val themeAttributeToGroup = groups.flatMap { group ->
        group.themeAttributeNames.map { name -> name to group }
    }.toMap()

    fun defaultColors(): Map<String, Int> = groups.associate { it.id to it.defaultColor }

    fun findById(id: String): WeTypeAppearanceColorGroup? = groupsById[id]

    fun findByColorName(name: String): WeTypeAppearanceColorGroup? = colorNameToGroup[name]

    fun findByThemeAttributeName(name: String): WeTypeAppearanceColorGroup? = themeAttributeToGroup[name]
}

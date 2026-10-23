package com.xposed.wetypehook.wetype.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.FileObserver
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object WeTypeSettings {
    const val PREF_GROUP = "wetype_settings"
    private const val TAG = "MIUIIME.Settings"
    private const val WETYPE_PACKAGE_NAME = "com.tencent.wetype"
    private const val KEY_WRITE_TOKEN = "settings_write_token"
    private const val KEY_PROFILES_VERSION = "appearance_profiles_version"
    private const val LIGHT_PROFILE_PREFIX = "appearance_light_"
    private const val DARK_PROFILE_PREFIX = "appearance_dark_"
    const val BACKGROUND_COLOR_ID = "background_color"
    private const val KEY_FOLLOWING_COLORS = "following_colors"
    private const val KEY_LIGHT_COLOR = "light_color"
    private const val KEY_DARK_COLOR = "dark_color"
    private const val KEY_HYPER_MATERIAL_ENABLED = "hyper_material_enabled"
    private const val KEY_BLUR_RADIUS = "blur_radius"
    private const val KEY_CORNER_RADIUS = "corner_radius"
    private const val KEY_KEY_CORNER_RADIUS = "key_corner_radius"
    private const val KEY_EDGE_HIGHLIGHT_ENABLED = "edge_highlight_enabled"
    private const val KEY_EDGE_HIGHLIGHT_INTENSITY = "edge_highlight_intensity"
    private const val KEY_KEY_OPACITY = "key_opacity"
    private const val KEY_KEY_OPACITY_MIGRATED = "key_opacity_migrated"
    // Keep the original suffix so legacy appearance values migrate without translation.
    private const val KEY_CANDIDATE_BACKGROUND_ALPHA = "key_color_hook_alpha"
    private const val KEY_CANDIDATE_BACKGROUND_CORNER = "candidate_background_corner"
    private const val KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP =
        "candidate_background_left_margin_dp"
    private const val KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP = "candidate_pinyin_left_margin_dp"
    private const val KEY_APPEARANCE_COLOR_PREFIX = "appearance_color_"
    private const val KEY_DISABLE_HOT_UPDATE = "disable_hot_update"
    private const val KEY_TOOLBAR_ICON_BG_OPACITY = "toolbar_icon_bg_opacity"
    private const val KEY_CUSTOM_ICON_PATH = "custom_icon_path"
    private const val KEY_ICON_SCALE = "icon_scale"
    const val DEFAULT_LIGHT_COLOR = 0xBDD4D4D4.toInt()
    const val DEFAULT_DARK_COLOR = 0x40000000
    const val DEFAULT_HYPER_MATERIAL_ENABLED = false
    const val DEFAULT_BLUR_RADIUS = 60
    const val DEFAULT_CORNER_RADIUS = 28
    const val MAX_CORNER_RADIUS = DEFAULT_CORNER_RADIUS * 2
    const val DEFAULT_KEY_CORNER_RADIUS = 10
    const val MAX_KEY_CORNER_RADIUS = 40
    private const val HOST_KEY_CORNER_OFFSET = 10
    const val DEFAULT_EDGE_HIGHLIGHT_ENABLED = true
    const val DEFAULT_EDGE_HIGHLIGHT_INTENSITY = 80
    const val DEFAULT_CANDIDATE_BACKGROUND_ALPHA = 150
    const val DEFAULT_CANDIDATE_BACKGROUND_CORNER = 60f
    const val MAX_CANDIDATE_BACKGROUND_CORNER = 60
    const val DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP = 6
    const val DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP = 16
    const val DEFAULT_TOOLBAR_ICON_BG_OPACITY = 150
    const val DEFAULT_DISABLE_HOT_UPDATE = true
    const val DEFAULT_CUSTOM_ICON_PATH = ""
    const val DEFAULT_ICON_SCALE = 1.0f
    const val MIN_ICON_SCALE = 0.6f
    const val MAX_ICON_SCALE = 1.4f

    private val legacyKeyColorDefaults = mapOf(
        LIGHT_KEY_COLOR_GROUP_ID to 0xFFfcfcfe.toInt(),
        DARK_KEY_COLOR_GROUP_ID to 0xFF707070.toInt()
    )
    private val colorIds = setOf(BACKGROUND_COLOR_ID) + WeTypeAppearanceColorGroups.groups.map { it.id }

    private val settingsLock = Any()
    private val snapshotDirty = AtomicBoolean(true)

    @Volatile
    private var cachedXposedProfiles: Profiles? = null
    @Volatile
    private var hostContext: Context? = null
    @Volatile
    private var hostDarkModeProvider: (() -> Boolean?)? = null
    private var hostFile: HostPreferencesFile? = null
    private var hostFileObserver: FileObserver? = null

    data class Snapshot(
        val backgroundColor: Int,
        val blurRadius: Int,
        val cornerRadius: Int,
        val keyCornerRadius: Int,
        val edgeHighlightEnabled: Boolean,
        val edgeHighlightIntensity: Int,
        val candidateBackgroundAlpha: Int,
        val candidateBackgroundCorner: Float,
        val candidateBackgroundLeftMarginDp: Int,
        val candidatePinyinLeftMarginDp: Int,
        val appearanceColors: Map<String, Int>,
        val toolbarIconBgOpacity: Int,
        val customIconPath: String,
        val iconScale: Float,
        val hyperMaterialEnabled: Boolean,
        val glassOverrides: GlassMaterialOverrides = GlassMaterialOverrides(),
        val followingColors: Set<String> = emptySet()
    )

    data class Profiles(
        val light: Snapshot,
        val dark: Snapshot,
        val disableHotUpdate: Boolean = DEFAULT_DISABLE_HOT_UPDATE
    ) {
        // Stable map identities keep the resource-color cache valid across draw calls.
        private val resolvedLight by lazy { resolveProfile(light, dark, isDark = false) }
        private val resolvedDark by lazy { resolveProfile(dark, light, isDark = true) }

        fun profile(isDark: Boolean): Snapshot = if (isDark) resolvedDark else resolvedLight
    }

    fun oppositeColorId(colorId: String): String = when (colorId) {
        LIGHT_KEY_COLOR_GROUP_ID -> DARK_KEY_COLOR_GROUP_ID
        DARK_KEY_COLOR_GROUP_ID -> LIGHT_KEY_COLOR_GROUP_ID
        else -> colorId
    }

    private fun resolveProfile(own: Snapshot, opposite: Snapshot, isDark: Boolean): Snapshot {
        val following = own.followingColors.filterTo(mutableSetOf()) { id ->
            id in colorIds && (isDark || oppositeColorId(id) !in opposite.followingColors)
        }
        if (following.isEmpty()) {
            return if (own.followingColors.isEmpty()) own else own.copy(followingColors = emptySet())
        }
        val colors = if (following.any { it != BACKGROUND_COLOR_ID }) {
            WeTypeAppearanceColorGroups.groups.associate { group ->
                val color = if (group.id in following) {
                    val sourceId = oppositeColorId(group.id)
                    opposite.appearanceColors[sourceId]
                        ?: WeTypeAppearanceColorGroups.findById(sourceId)?.defaultColor
                } else own.appearanceColors[group.id]
                group.id to (color ?: group.defaultColor)
            }
        } else own.appearanceColors
        return own.copy(
            backgroundColor = if (BACKGROUND_COLOR_ID in following) opposite.backgroundColor else own.backgroundColor,
            appearanceColors = colors,
            followingColors = following
        )
    }

    fun getLightColor(context: Context): Int = readProfiles(context).profile(false).backgroundColor

    fun getDarkColor(context: Context): Int = readProfiles(context).profile(true).backgroundColor

    fun getBlurRadius(context: Context): Int = readSnapshot(context).blurRadius

    fun getCornerRadius(context: Context): Int = readSnapshot(context).cornerRadius

    fun getKeyCornerRadius(context: Context): Int = readSnapshot(context).keyCornerRadius

    // Preserve the existing setting offset. The host scales this design-unit value with its keys.
    fun keyCornerRadiusToHostUnits(radius: Int): Float = (radius + HOST_KEY_CORNER_OFFSET).toFloat()

    fun isEdgeHighlightEnabled(context: Context): Boolean = readSnapshot(context).edgeHighlightEnabled

    fun getEdgeHighlightIntensity(context: Context): Int = readSnapshot(context).edgeHighlightIntensity

    fun getCandidateBackgroundAlpha(context: Context): Int =
        readSnapshot(context).candidateBackgroundAlpha

    fun getCandidateBackgroundCorner(context: Context): Float =
        readSnapshot(context).candidateBackgroundCorner

    fun getCandidateBackgroundLeftMarginDp(context: Context): Int =
        readSnapshot(context).candidateBackgroundLeftMarginDp

    fun getCandidatePinyinLeftMarginDp(context: Context): Int =
        readSnapshot(context).candidatePinyinLeftMarginDp

    fun getAppearanceColors(context: Context): Map<String, Int> = readSnapshot(context).appearanceColors

    fun isDisableHotUpdate(context: Context): Boolean = readProfiles(context).disableHotUpdate

    fun prepareForHotReload() = synchronized(settingsLock) {
        hostFileObserver?.stopWatching()
        hostFileObserver = null
        hostFile = null
        hostContext = null
        hostDarkModeProvider = null
        cachedXposedProfiles = null
        snapshotDirty.set(true)
    }

    internal fun setHostDarkModeProvider(provider: (() -> Boolean?)?) {
        hostDarkModeProvider = provider
    }

    fun isHostDarkMode(resources: Resources? = null): Boolean {
        runCatching { hostDarkModeProvider?.invoke() }.getOrNull()?.let { return it }
        val configuration = (resources ?: hostContext?.resources)?.configuration ?: return false
        return configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun ensureHostSnapshot(context: Context) {
        val appContext = context.applicationContext ?: context
        if (appContext.packageName != WETYPE_PACKAGE_NAME) return
        synchronized(settingsLock) {
            hostContext = appContext
            if (hostFile == null) {
                val directory = File(appContext.applicationInfo.dataDir, "shared_prefs")
                directory.mkdirs()
                val file = File(directory, "$PREF_GROUP.xml")
                hostFile = HostPreferencesFile(file)
                hostFileObserver = object : FileObserver(
                    directory, CLOSE_WRITE or MOVED_TO or MOVED_FROM or DELETE
                ) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path == file.name || path == "${file.name}.bak") {
                            snapshotDirty.set(true)
                        }
                    }
                }.also { it.startWatching() }
            }
            // Refresh at input/view entry too, including after a process was frozen.
            snapshotDirty.set(true)
            readProfilesXposed()
        }
    }

    fun save(
        context: Context,
        profiles: Profiles,
        onPersisted: (Boolean) -> Unit = {}
    ): Boolean {
        val normalized = profiles.normalized()
        val appContext = context.applicationContext ?: context
        if (appContext.packageName != WETYPE_PACKAGE_NAME) {
            onPersisted(false)
            return false
        }
        ensureHostSnapshot(appContext)
        val persisted = runCatching {
            synchronized(settingsLock) {
                checkNotNull(hostFile).withLock {
                    val preferences = appContext.getSharedPreferences(PREF_GROUP, Context.MODE_PRIVATE)
                    writeProfiles(preferences, normalized)
                }.also { saved ->
                    if (saved) cachedXposedProfiles = normalized
                    snapshotDirty.set(true)
                }
            }
        }.onFailure { Log.e(TAG, "Failed to save host preferences", it) }.getOrDefault(false)
        onPersisted(persisted)
        return persisted
    }

    fun getCurrentBackgroundColorXposed(context: Context): Int =
        readSnapshotXposed(context.resources).backgroundColor

    fun getBlurRadiusXposed(context: Context): Int = readSnapshotXposed(context.resources).blurRadius

    fun getCornerRadiusXposed(context: Context): Int = readSnapshotXposed(context.resources).cornerRadius

    fun getKeyCornerRadiusXposed(resources: Resources? = null): Int =
        readSnapshotXposed(resources).keyCornerRadius

    fun isEdgeHighlightEnabledXposed(context: Context): Boolean =
        readSnapshotXposed(context.resources).edgeHighlightEnabled

    fun getEdgeHighlightIntensityXposed(context: Context): Int =
        readSnapshotXposed(context.resources).edgeHighlightIntensity

    fun getCandidateBackgroundAlphaXposed(resources: Resources? = null): Int =
        readSnapshotXposed(resources).candidateBackgroundAlpha

    fun getCandidateBackgroundCornerXposed(resources: Resources? = null): Float =
        readSnapshotXposed(resources).candidateBackgroundCorner

    fun getCandidateBackgroundLeftMarginDpXposed(resources: Resources? = null): Int =
        readSnapshotXposed(resources).candidateBackgroundLeftMarginDp

    fun getToolbarIconBgOpacityXposed(resources: Resources? = null): Int =
        readSnapshotXposed(resources).toolbarIconBgOpacity

    /** SVG foreground paths separated by newlines; an empty value selects the built-in logo. */
    fun getCustomIconPathXposed(resources: Resources? = null): String =
        readSnapshotXposed(resources).customIconPath

    fun getIconScaleXposed(resources: Resources? = null): Float =
        readSnapshotXposed(resources).iconScale

    fun getCandidatePinyinLeftMarginDpXposed(resources: Resources? = null): Int =
        readSnapshotXposed(resources).candidatePinyinLeftMarginDp

    fun isDisableHotUpdateXposed(): Boolean = readProfilesXposed().disableHotUpdate

    fun getAppearanceColorXposed(groupId: String, resources: Resources? = null): Int =
        readSnapshotXposed(resources).appearanceColors[groupId]
            ?: WeTypeAppearanceColorGroups.findById(groupId)?.defaultColor
            ?: 0

    fun getAppearanceColorsXposed(resources: Resources? = null): Map<String, Int> =
        readSnapshotXposed(resources).appearanceColors

    fun readProfiles(context: Context): Profiles {
        ensureHostSnapshot(context)
        return readProfilesXposed()
    }

    fun readSnapshot(context: Context): Snapshot =
        readProfiles(context).profile(isHostDarkMode(context.resources))

    internal fun readSnapshotXposed(resources: Resources? = null): Snapshot =
        readProfilesXposed().profile(isHostDarkMode(resources))

    internal fun readProfilesXposed(): Profiles {
        if (!snapshotDirty.get()) cachedXposedProfiles?.let { return it }
        return synchronized(settingsLock) {
            val file = hostFile ?: return@synchronized defaultProfiles()
            if (snapshotDirty.getAndSet(false) || cachedXposedProfiles == null) {
                runCatching { file.read().toProfiles() }
                    .onSuccess { cachedXposedProfiles = it }
                    .onFailure {
                        if (cachedXposedProfiles == null) cachedXposedProfiles = defaultProfiles()
                        Log.e(TAG, "Failed to read host preferences", it)
                    }
            }
            cachedXposedProfiles ?: defaultProfiles()
        }
    }

    private fun writeProfiles(preferences: SharedPreferences, profiles: Profiles): Boolean {
        val editor = preferences.edit()
        for (prefix in listOf(LIGHT_PROFILE_PREFIX, DARK_PROFILE_PREFIX)) {
            // Null overrides must erase any previous profile value without reviving legacy values.
            for ((key, count) in listOf("glass_params" to 42, "glass_bloom" to 16)) {
                editor.remove("$prefix${key}_count")
                repeat(count) { editor.remove("$prefix${key}_$it") }
            }
            editor.remove("${prefix}glass_blur_small")
                .remove("${prefix}glass_blur_large")
                .remove("${prefix}glass_material_type")
            WeTypeAppearanceColorGroups.obsoleteGroupIds.forEach { groupId ->
                editor.remove("$prefix$KEY_APPEARANCE_COLOR_PREFIX$groupId")
            }
        }
        profiles.toPreferenceValues().forEach { (key, value) ->
            when (value) {
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
            }
        }
        // A fresh token forces a disk write even if another process changed the file
        // while this process still has the same values in its SharedPreferences cache.
        editor.putString(KEY_WRITE_TOKEN, UUID.randomUUID().toString())
            .remove("host_sync_pending")
            .remove("host_sync_revision")
            .remove("remote_sync_pending")
            .remove("last_imported_revision")
        return editor.commit()
    }

    internal fun Profiles.toPreferenceValues(): Map<String, Any> = buildMap {
        val profiles = this@toPreferenceValues.normalized()
        put(KEY_PROFILES_VERSION, 1)
        put(KEY_DISABLE_HOT_UPDATE, disableHotUpdate)
        fun writeProfile(prefix: String, profile: Snapshot) {
            val snapshot = profile
            fun putValue(key: String, value: Any) { put(prefix + key, value) }
            putValue(BACKGROUND_COLOR_ID, snapshot.backgroundColor)
            putValue(KEY_FOLLOWING_COLORS, snapshot.followingColors.sorted().joinToString(","))
            putValue(KEY_BLUR_RADIUS, snapshot.blurRadius)
            putValue(KEY_CORNER_RADIUS, snapshot.cornerRadius)
            putValue(KEY_KEY_CORNER_RADIUS, snapshot.keyCornerRadius)
            putValue(KEY_EDGE_HIGHLIGHT_ENABLED, snapshot.edgeHighlightEnabled)
            putValue(KEY_EDGE_HIGHLIGHT_INTENSITY, snapshot.edgeHighlightIntensity)
            putValue(KEY_CANDIDATE_BACKGROUND_ALPHA, snapshot.candidateBackgroundAlpha)
            putValue(KEY_CANDIDATE_BACKGROUND_CORNER, snapshot.candidateBackgroundCorner)
            putValue(KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP, snapshot.candidateBackgroundLeftMarginDp)
            putValue(KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP, snapshot.candidatePinyinLeftMarginDp)
            putValue(KEY_TOOLBAR_ICON_BG_OPACITY, snapshot.toolbarIconBgOpacity)
            putValue(KEY_CUSTOM_ICON_PATH, snapshot.customIconPath)
            putValue(KEY_ICON_SCALE, snapshot.iconScale)
            putValue(KEY_HYPER_MATERIAL_ENABLED, snapshot.hyperMaterialEnabled)
            snapshot.appearanceColors.forEach { (id, color) ->
                putValue(KEY_APPEARANCE_COLOR_PREFIX + id, color)
            }
            fun writeFloats(key: String, values: List<Float>?) {
                values?.let {
                    putValue("${key}_count", it.size)
                    it.forEachIndexed { index, value -> putValue("${key}_$index", value) }
                }
            }
            writeFloats("glass_params", snapshot.glassOverrides.glass)
            writeFloats("glass_bloom", snapshot.glassOverrides.bloom)
            snapshot.glassOverrides.blurRadii?.let {
                putValue("glass_blur_small", it[0])
                putValue("glass_blur_large", it[1])
            }
            snapshot.glassOverrides.materialType?.let { putValue("glass_material_type", it) }
        }
        writeProfile(LIGHT_PROFILE_PREFIX, profiles.light)
        writeProfile(DARK_PROFILE_PREFIX, profiles.dark)
    }

    internal fun Map<String, Any>.toProfiles(): Profiles {
        val hasProfiles = getInt(KEY_PROFILES_VERSION, 0) >= 1
        fun readProfile(isDark: Boolean): Snapshot {
            if (!hasProfiles) return toSnapshot(isDark, legacy = true)
            val prefix = if (isDark) DARK_PROFILE_PREFIX else LIGHT_PROFILE_PREFIX
            val values = entries.filter { it.key.startsWith(prefix) }
                .associate { it.key.removePrefix(prefix) to it.value }
            return values.toSnapshot(isDark, legacy = false)
        }
        val profiles = Profiles(
            light = readProfile(false),
            dark = readProfile(true),
            disableHotUpdate = getBoolean(KEY_DISABLE_HOT_UPDATE, DEFAULT_DISABLE_HOT_UPDATE)
        ).normalized()
        fun migrateCandidateColor(isDark: Boolean): Snapshot {
            val profile = if (isDark) profiles.dark else profiles.light
            val prefix = if (!hasProfiles) "" else if (isDark) DARK_PROFILE_PREFIX else LIGHT_PROFILE_PREFIX
            if (this["$prefix$KEY_APPEARANCE_COLOR_PREFIX$CANDIDATE_COLOR_GROUP_ID"] is Int) return profile
            // The old candidate hook used the effective brand color, including cross-mode links.
            val previousColor = profiles.profile(isDark).appearanceColors.getValue("theme_color")
            return profile.copy(
                appearanceColors = profile.appearanceColors + (CANDIDATE_COLOR_GROUP_ID to previousColor),
                followingColors = profile.followingColors - CANDIDATE_COLOR_GROUP_ID
            )
        }
        return profiles.copy(light = migrateCandidateColor(false), dark = migrateCandidateColor(true))
    }

    private fun Map<String, Any>.toSnapshot(isDark: Boolean, legacy: Boolean): Snapshot {
        val legacyKeyOpacity = if (legacy && contains(KEY_KEY_OPACITY) &&
            !getBoolean(KEY_KEY_OPACITY_MIGRATED, false)
        ) {
            getInt(KEY_KEY_OPACITY, 255).coerceIn(0, 255)
        } else null
        val backgroundKey = if (legacy) {
            if (isDark) KEY_DARK_COLOR else KEY_LIGHT_COLOR
        } else BACKGROUND_COLOR_ID
        return Snapshot(
            backgroundColor = getInt(backgroundKey, if (isDark) DEFAULT_DARK_COLOR else DEFAULT_LIGHT_COLOR),
            blurRadius = getInt(KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS),
            cornerRadius = getInt(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS),
            keyCornerRadius = getInt(KEY_KEY_CORNER_RADIUS, DEFAULT_KEY_CORNER_RADIUS),
            edgeHighlightEnabled = getBoolean(KEY_EDGE_HIGHLIGHT_ENABLED, DEFAULT_EDGE_HIGHLIGHT_ENABLED),
            edgeHighlightIntensity = getInt(KEY_EDGE_HIGHLIGHT_INTENSITY, DEFAULT_EDGE_HIGHLIGHT_INTENSITY),
            candidateBackgroundAlpha = getInt(KEY_CANDIDATE_BACKGROUND_ALPHA, DEFAULT_CANDIDATE_BACKGROUND_ALPHA),
            candidateBackgroundCorner = getFloat(KEY_CANDIDATE_BACKGROUND_CORNER, DEFAULT_CANDIDATE_BACKGROUND_CORNER),
            candidateBackgroundLeftMarginDp = getInt(KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP, DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP),
            candidatePinyinLeftMarginDp = getInt(KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP, DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP),
            toolbarIconBgOpacity = getInt(KEY_TOOLBAR_ICON_BG_OPACITY, DEFAULT_TOOLBAR_ICON_BG_OPACITY),
            customIconPath = getString(KEY_CUSTOM_ICON_PATH, DEFAULT_CUSTOM_ICON_PATH),
            iconScale = getFloat(KEY_ICON_SCALE, DEFAULT_ICON_SCALE),
            appearanceColors = WeTypeAppearanceColorGroups.groups.associate { group ->
                val fallbackColor = if (legacy && group.id == ICON_COLOR_GROUP_ID) {
                    getInt("${KEY_APPEARANCE_COLOR_PREFIX}theme_color", group.defaultColor)
                } else if (legacyKeyOpacity != null) {
                    legacyKeyColorDefaults[group.id] ?: group.defaultColor
                } else group.defaultColor
                val color = getInt("$KEY_APPEARANCE_COLOR_PREFIX${group.id}", fallbackColor)
                group.id to migrateLegacyKeyOpacity(group, color, legacyKeyOpacity)
            },
            hyperMaterialEnabled = getBoolean(KEY_HYPER_MATERIAL_ENABLED, DEFAULT_HYPER_MATERIAL_ENABLED),
            glassOverrides = GlassMaterialOverrides.read(this),
            followingColors = if (legacy) emptySet() else getString(KEY_FOLLOWING_COLORS, "")
                .split(',').toSet()
        ).normalized()
    }

    private fun Profiles.normalized(): Profiles {
        val normalizedLight = light.normalized()
        val normalizedDark = dark.normalized()
        // Corrupt mutual links resolve to the light profile's original color, never recurse.
        val lightFollowing = normalizedLight.followingColors.filterTo(mutableSetOf()) { id ->
            oppositeColorId(id) !in normalizedDark.followingColors
        }
        return copy(
            light = normalizedLight.copy(followingColors = lightFollowing),
            dark = normalizedDark
        )
    }

    private fun Snapshot.normalized(): Snapshot = copy(
        blurRadius = blurRadius.coerceIn(0, 100),
        cornerRadius = cornerRadius.coerceIn(0, MAX_CORNER_RADIUS),
        keyCornerRadius = keyCornerRadius.coerceIn(0, MAX_KEY_CORNER_RADIUS),
        edgeHighlightIntensity = edgeHighlightIntensity.coerceIn(0, 200),
        candidateBackgroundAlpha = candidateBackgroundAlpha.coerceIn(0, 255),
        candidateBackgroundCorner = candidateBackgroundCorner.coerceIn(0f, MAX_CANDIDATE_BACKGROUND_CORNER.toFloat()),
        candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp.coerceIn(0, 64),
        candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp.coerceIn(0, 64),
        toolbarIconBgOpacity = toolbarIconBgOpacity.coerceIn(0, 255),
        iconScale = iconScale.coerceIn(MIN_ICON_SCALE, MAX_ICON_SCALE),
        appearanceColors = WeTypeAppearanceColorGroups.groups.associate { group ->
            group.id to (appearanceColors[group.id] ?: group.defaultColor)
        },
        followingColors = followingColors.filterTo(mutableSetOf()) { it in colorIds }
    )

    private fun migrateLegacyKeyOpacity(
        group: WeTypeAppearanceColorGroup,
        color: Int,
        legacyKeyOpacity: Int?
    ): Int {
        if (!group.isKeyColorGroup || legacyKeyOpacity == null || color ushr 24 != 0xFF) return color
        return (legacyKeyOpacity shl 24) or (color and 0x00FFFFFF)
    }

    private fun Map<String, Any>.getInt(key: String, default: Int): Int = this[key] as? Int ?: default
    private fun Map<String, Any>.getFloat(key: String, default: Float): Float = this[key] as? Float ?: default
    private fun Map<String, Any>.getBoolean(key: String, default: Boolean): Boolean = this[key] as? Boolean ?: default
    private fun Map<String, Any>.getString(key: String, default: String): String = this[key] as? String ?: default

    fun defaultSnapshot(isDark: Boolean): Snapshot = Snapshot(
        backgroundColor = if (isDark) DEFAULT_DARK_COLOR else DEFAULT_LIGHT_COLOR,
        blurRadius = DEFAULT_BLUR_RADIUS,
        cornerRadius = DEFAULT_CORNER_RADIUS,
        keyCornerRadius = DEFAULT_KEY_CORNER_RADIUS,
        edgeHighlightEnabled = DEFAULT_EDGE_HIGHLIGHT_ENABLED,
        edgeHighlightIntensity = DEFAULT_EDGE_HIGHLIGHT_INTENSITY,
        candidateBackgroundAlpha = DEFAULT_CANDIDATE_BACKGROUND_ALPHA,
        candidateBackgroundCorner = DEFAULT_CANDIDATE_BACKGROUND_CORNER,
        candidateBackgroundLeftMarginDp = DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
        candidatePinyinLeftMarginDp = DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP,
        toolbarIconBgOpacity = DEFAULT_TOOLBAR_ICON_BG_OPACITY,
        customIconPath = DEFAULT_CUSTOM_ICON_PATH,
        iconScale = DEFAULT_ICON_SCALE,
        appearanceColors = WeTypeAppearanceColorGroups.defaultColors(),
        hyperMaterialEnabled = DEFAULT_HYPER_MATERIAL_ENABLED
    )

    fun defaultProfiles(): Profiles = Profiles(defaultSnapshot(false), defaultSnapshot(true))
}

package com.xposed.wetypehook.wetype.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import de.robv.android.xposed.XSharedPreferences

object WeTypeSettings {
    private const val MODULE_PACKAGE_NAME = "com.xposed.wetypehook"
    private const val PREF_NAME = "wetype_settings"
    private const val KEY_LIGHT_COLOR = "light_color"
    private const val KEY_DARK_COLOR = "dark_color"
    private const val KEY_BLUR_RADIUS = "blur_radius"
    private const val KEY_CORNER_RADIUS = "corner_radius"
    private const val KEY_EDGE_HIGHLIGHT_ENABLED = "edge_highlight_enabled"
    private const val KEY_EDGE_HIGHLIGHT_INTENSITY = "edge_highlight_intensity"
    private const val KEY_KEY_OPACITY = "key_opacity"
    // Keep the original preference key so existing saved values still migrate cleanly.
    private const val KEY_CANDIDATE_BACKGROUND_ALPHA = "key_color_hook_alpha"
    private const val KEY_CANDIDATE_BACKGROUND_CORNER = "candidate_background_corner"
    private const val KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP =
        "candidate_background_left_margin_dp"
    private const val KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP = "candidate_pinyin_left_margin_dp"
    private const val KEY_APPEARANCE_COLOR_PREFIX = "appearance_color_"
    private const val KEY_DISABLE_HOT_UPDATE = "disable_hot_update"
    private const val KEY_TOOLBAR_ICON_BG_OPACITY = "toolbar_icon_bg_opacity"
    const val DEFAULT_LIGHT_COLOR = 0xA0D1D3D8.toInt()
    const val DEFAULT_DARK_COLOR = 0x90101010.toInt()
    const val DEFAULT_BLUR_RADIUS = 60
    const val DEFAULT_CORNER_RADIUS = 28
    const val MAX_CORNER_RADIUS = DEFAULT_CORNER_RADIUS * 2
    const val DEFAULT_EDGE_HIGHLIGHT_ENABLED = true
    const val DEFAULT_EDGE_HIGHLIGHT_INTENSITY = 50
    const val DEFAULT_KEY_OPACITY = 200
    const val DEFAULT_CANDIDATE_BACKGROUND_ALPHA = 200
    const val DEFAULT_CANDIDATE_BACKGROUND_CORNER = 60f
    const val MAX_CANDIDATE_BACKGROUND_CORNER = 60
    const val DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP = 6
    const val DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP = 16
    const val DEFAULT_TOOLBAR_ICON_BG_OPACITY = 200
    const val DEFAULT_DISABLE_HOT_UPDATE = true

    private val xposedPrefsLock = Any()
    private val xposedPrefsCache = HashMap<String, XSharedPreferences>()

    @Volatile
    private var cachedXposedSnapshot: Snapshot? = null
    @Volatile
    private var xposedPrefsPackageName: String = MODULE_PACKAGE_NAME

    private val xposedPrefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        cachedXposedSnapshot = null
        synchronized(xposedPrefsLock) {
            xposedPrefsCache.values.forEach { prefs ->
                runCatching { prefs.reload() }
            }
        }
    }

    data class Snapshot(
        val lightColor: Int,
        val darkColor: Int,
        val blurRadius: Int,
        val cornerRadius: Int,
        val edgeHighlightEnabled: Boolean,
        val edgeHighlightIntensity: Int,
        val keyOpacity: Int,
        val candidateBackgroundAlpha: Int,
        val candidateBackgroundCorner: Float,
        val candidateBackgroundLeftMarginDp: Int,
        val candidatePinyinLeftMarginDp: Int,
        val appearanceColors: Map<String, Int>,
        val toolbarIconBgOpacity: Int,
        val disableHotUpdate: Boolean
    )

    fun getLightColor(context: Context): Int = readSnapshot(context).lightColor

    fun getDarkColor(context: Context): Int = readSnapshot(context).darkColor

    fun getBlurRadius(context: Context): Int = readSnapshot(context).blurRadius

    fun getCornerRadius(context: Context): Int = readSnapshot(context).cornerRadius

    fun isEdgeHighlightEnabled(context: Context): Boolean = readSnapshot(context).edgeHighlightEnabled

    fun getEdgeHighlightIntensity(context: Context): Int = readSnapshot(context).edgeHighlightIntensity

    fun getKeyOpacity(context: Context): Int = readSnapshot(context).keyOpacity

    fun getCandidateBackgroundAlpha(context: Context): Int =
        readSnapshot(context).candidateBackgroundAlpha

    fun getCandidateBackgroundCorner(context: Context): Float =
        readSnapshot(context).candidateBackgroundCorner

    fun getCandidateBackgroundLeftMarginDp(context: Context): Int =
        readSnapshot(context).candidateBackgroundLeftMarginDp

    fun getCandidatePinyinLeftMarginDp(context: Context): Int =
        readSnapshot(context).candidatePinyinLeftMarginDp

    fun getAppearanceColors(context: Context): Map<String, Int> = readSnapshot(context).appearanceColors

    fun isDisableHotUpdate(context: Context): Boolean = readSnapshot(context).disableHotUpdate

    fun initXposed() {
        cachedXposedSnapshot = null
        synchronized(xposedPrefsLock) {
            xposedPrefsCache[xposedPrefsPackageName]?.reload()
            xposedPrefsCache[MODULE_PACKAGE_NAME]?.reload()
        }
    }

    fun configureStorage(hostPackageName: String) {
        if (hostPackageName.isBlank() || xposedPrefsPackageName == hostPackageName) return
        synchronized(xposedPrefsLock) {
            if (xposedPrefsPackageName == hostPackageName) return
            xposedPrefsPackageName = hostPackageName
        }
        cachedXposedSnapshot = null
    }

    fun ensureHostSnapshot(context: Context) {
        val prefs = appPreferences(context)
        if (prefs.containsAnyPersistedSetting()) return
        val snapshot = readSnapshotXposed()
        saveDirect(
            context = context,
            lightColor = snapshot.lightColor,
            darkColor = snapshot.darkColor,
            blurRadius = snapshot.blurRadius,
            cornerRadius = snapshot.cornerRadius,
            edgeHighlightEnabled = snapshot.edgeHighlightEnabled,
            edgeHighlightIntensity = snapshot.edgeHighlightIntensity,
            keyOpacity = snapshot.keyOpacity,
            candidateBackgroundAlpha = snapshot.candidateBackgroundAlpha,
            candidateBackgroundCorner = snapshot.candidateBackgroundCorner,
            candidateBackgroundLeftMarginDp = snapshot.candidateBackgroundLeftMarginDp,
            candidatePinyinLeftMarginDp = snapshot.candidatePinyinLeftMarginDp,
            toolbarIconBgOpacity = snapshot.toolbarIconBgOpacity,
            appearanceColors = snapshot.appearanceColors,
            disableHotUpdate = snapshot.disableHotUpdate
        )
    }

    fun save(
        context: Context,
        lightColor: Int,
        darkColor: Int,
        blurRadius: Int,
        cornerRadius: Int,
        edgeHighlightEnabled: Boolean,
        edgeHighlightIntensity: Int,
        keyOpacity: Int,
        candidateBackgroundAlpha: Int,
        candidateBackgroundCorner: Float,
        candidateBackgroundLeftMarginDp: Int,
        candidatePinyinLeftMarginDp: Int,
        toolbarIconBgOpacity: Int,
        appearanceColors: Map<String, Int>,
        disableHotUpdate: Boolean = DEFAULT_DISABLE_HOT_UPDATE
    ) {
        val sanitizedAppearanceColors = WeTypeAppearanceColorGroups.groups.associate { group ->
            group.id to (appearanceColors[group.id] ?: group.defaultColor)
        }
        saveDirect(
            context = context,
            lightColor = lightColor,
            darkColor = darkColor,
            blurRadius = blurRadius,
            cornerRadius = cornerRadius,
            edgeHighlightEnabled = edgeHighlightEnabled,
            edgeHighlightIntensity = edgeHighlightIntensity,
            keyOpacity = keyOpacity,
            candidateBackgroundAlpha = candidateBackgroundAlpha,
            candidateBackgroundCorner = candidateBackgroundCorner,
            candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp,
            candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp,
            toolbarIconBgOpacity = toolbarIconBgOpacity,
            appearanceColors = sanitizedAppearanceColors,
            disableHotUpdate = disableHotUpdate
        )
    }

    fun getCurrentBackgroundColorXposed(context: Context): Int {
        val snapshot = readSnapshotXposed()
        val isDarkMode =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        return if (isDarkMode) snapshot.darkColor else snapshot.lightColor
    }

    fun getBlurRadiusXposed(context: Context): Int = readSnapshotXposed().blurRadius

    fun getCornerRadiusXposed(context: Context): Int = readSnapshotXposed().cornerRadius

    fun isEdgeHighlightEnabledXposed(context: Context): Boolean =
        readSnapshotXposed().edgeHighlightEnabled

    fun getEdgeHighlightIntensityXposed(context: Context): Int =
        readSnapshotXposed().edgeHighlightIntensity

    fun getKeyOpacityXposed(context: Context): Int = readSnapshotXposed().keyOpacity

    fun getCandidateBackgroundAlphaXposed(): Int =
        readSnapshotXposed().candidateBackgroundAlpha

    fun getCandidateBackgroundCornerXposed(): Float =
        readSnapshotXposed().candidateBackgroundCorner

    fun getCandidateBackgroundLeftMarginDpXposed(): Int =
        readSnapshotXposed().candidateBackgroundLeftMarginDp

    fun getToolbarIconBgOpacityXposed(): Int =
        readSnapshotXposed().toolbarIconBgOpacity

    fun getCandidatePinyinLeftMarginDpXposed(): Int =
        readSnapshotXposed().candidatePinyinLeftMarginDp

    fun isDisableHotUpdateXposed(): Boolean = readSnapshotXposed().disableHotUpdate

    fun getAppearanceColorXposed(groupId: String): Int =
        readSnapshotXposed().appearanceColors[groupId]
            ?: WeTypeAppearanceColorGroups.findById(groupId)?.defaultColor
            ?: 0

    fun readSnapshot(context: Context): Snapshot {
        val prefs = appPreferences(context)
        return prefs.toSnapshotOrNull() ?: readSnapshotXposed()
    }

    private fun readSnapshotXposed(): Snapshot {
        cachedXposedSnapshot?.let { return it }

        synchronized(xposedPrefsLock) {
            cachedXposedSnapshot?.let { return it }

            val hostPrefs = getXposedPrefsLocked(xposedPrefsPackageName)
            val modulePrefs = if (xposedPrefsPackageName == MODULE_PACKAGE_NAME) {
                hostPrefs
            } else {
                getXposedPrefsLocked(MODULE_PACKAGE_NAME)
            }

            val resolvedSnapshot = hostPrefs.loadSnapshotOrNull()
                ?: modulePrefs.loadSnapshotOrNull()
                ?: defaultSnapshot()
            cachedXposedSnapshot = resolvedSnapshot
            return resolvedSnapshot
        }
    }

    @Suppress("DEPRECATION")
    private fun appPreferences(context: Context): SharedPreferences {
        val appContext = context.applicationContext ?: context
        return try {
            appContext.getSharedPreferences(PREF_NAME, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun saveDirect(
        context: Context,
        lightColor: Int,
        darkColor: Int,
        blurRadius: Int,
        cornerRadius: Int,
        edgeHighlightEnabled: Boolean,
        edgeHighlightIntensity: Int,
        keyOpacity: Int,
        candidateBackgroundAlpha: Int,
        candidateBackgroundCorner: Float,
        candidateBackgroundLeftMarginDp: Int,
        candidatePinyinLeftMarginDp: Int,
        toolbarIconBgOpacity: Int,
        appearanceColors: Map<String, Int>,
        disableHotUpdate: Boolean
    ) {
        val editor = appPreferences(context)
            .edit()
            .putInt(KEY_LIGHT_COLOR, lightColor)
            .putInt(KEY_DARK_COLOR, darkColor)
            .putInt(KEY_BLUR_RADIUS, blurRadius.coerceIn(0, 100))
            .putInt(KEY_CORNER_RADIUS, cornerRadius.coerceIn(0, MAX_CORNER_RADIUS))
            .putBoolean(KEY_EDGE_HIGHLIGHT_ENABLED, edgeHighlightEnabled)
            .putInt(KEY_EDGE_HIGHLIGHT_INTENSITY, edgeHighlightIntensity.coerceIn(0, 200))
            .putInt(KEY_KEY_OPACITY, keyOpacity.coerceIn(0, 255))
            .putInt(
                KEY_CANDIDATE_BACKGROUND_ALPHA,
                candidateBackgroundAlpha.coerceIn(0, 255)
            )
            .putFloat(
                KEY_CANDIDATE_BACKGROUND_CORNER,
                candidateBackgroundCorner.coerceIn(0f, MAX_CANDIDATE_BACKGROUND_CORNER.toFloat())
            )
            .putInt(
                KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
                candidateBackgroundLeftMarginDp.coerceIn(0, 64)
            )
            .putInt(
                KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP,
                candidatePinyinLeftMarginDp.coerceIn(0, 64)
            )
            .putInt(KEY_TOOLBAR_ICON_BG_OPACITY, toolbarIconBgOpacity.coerceIn(0, 255))
            .putBoolean(KEY_DISABLE_HOT_UPDATE, disableHotUpdate)
        WeTypeAppearanceColorGroups.groups.forEach { group ->
            editor.putInt(
                "$KEY_APPEARANCE_COLOR_PREFIX${group.id}",
                appearanceColors[group.id] ?: group.defaultColor
            )
        }
        WeTypeAppearanceColorGroups.obsoleteGroupIds.forEach { groupId ->
            editor.remove("$KEY_APPEARANCE_COLOR_PREFIX$groupId")
        }
        editor.commit()
        cachedXposedSnapshot = Snapshot(
            lightColor = lightColor,
            darkColor = darkColor,
            blurRadius = blurRadius.coerceIn(0, 100),
            cornerRadius = cornerRadius.coerceIn(0, MAX_CORNER_RADIUS),
            edgeHighlightEnabled = edgeHighlightEnabled,
            edgeHighlightIntensity = edgeHighlightIntensity.coerceIn(0, 200),
            keyOpacity = keyOpacity.coerceIn(0, 255),
            candidateBackgroundAlpha = candidateBackgroundAlpha.coerceIn(0, 255),
            candidateBackgroundCorner = candidateBackgroundCorner.coerceIn(
                0f,
                MAX_CANDIDATE_BACKGROUND_CORNER.toFloat()
            ),
            candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp.coerceIn(0, 64),
            candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp.coerceIn(0, 64),
            toolbarIconBgOpacity = toolbarIconBgOpacity.coerceIn(0, 255),
            appearanceColors = WeTypeAppearanceColorGroups.groups.associate { group ->
                group.id to (appearanceColors[group.id] ?: group.defaultColor)
            },
            disableHotUpdate = disableHotUpdate
        )
        synchronized(xposedPrefsLock) {
            xposedPrefsCache.values.forEach { prefs ->
                runCatching { prefs.reload() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getXposedPrefs(packageName: String): XSharedPreferences? {
        synchronized(xposedPrefsLock) {
            return getXposedPrefsLocked(packageName)
        }
    }

    @Suppress("DEPRECATION")
    private fun getXposedPrefsLocked(packageName: String): XSharedPreferences? {
        xposedPrefsCache[packageName]?.let { return it }
        val prefs = runCatching {
            XSharedPreferences(packageName, PREF_NAME).also { createdPrefs ->
                createdPrefs.reload()
            }
        }.getOrNull() ?: return null

        xposedPrefsCache[packageName] = prefs
        runCatching {
            prefs.registerOnSharedPreferenceChangeListener(xposedPrefChangeListener)
        }
        return prefs
    }

    private fun SharedPreferences.toSnapshot(): Snapshot {
        return Snapshot(
            lightColor = getInt(KEY_LIGHT_COLOR, DEFAULT_LIGHT_COLOR),
            darkColor = getInt(KEY_DARK_COLOR, DEFAULT_DARK_COLOR),
            blurRadius = getInt(KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS),
            cornerRadius = getInt(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS)
                .coerceIn(0, MAX_CORNER_RADIUS),
            edgeHighlightEnabled = getBoolean(
                KEY_EDGE_HIGHLIGHT_ENABLED,
                DEFAULT_EDGE_HIGHLIGHT_ENABLED
            ),
            edgeHighlightIntensity = getInt(
                KEY_EDGE_HIGHLIGHT_INTENSITY,
                DEFAULT_EDGE_HIGHLIGHT_INTENSITY
            ),
            keyOpacity = getInt(KEY_KEY_OPACITY, DEFAULT_KEY_OPACITY),
            candidateBackgroundAlpha = getInt(
                KEY_CANDIDATE_BACKGROUND_ALPHA,
                DEFAULT_CANDIDATE_BACKGROUND_ALPHA
            ),
            candidateBackgroundCorner = getFloat(
                KEY_CANDIDATE_BACKGROUND_CORNER,
                DEFAULT_CANDIDATE_BACKGROUND_CORNER
            ).coerceIn(0f, MAX_CANDIDATE_BACKGROUND_CORNER.toFloat()),
            candidateBackgroundLeftMarginDp = getInt(
                KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
                DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP
            ).coerceIn(0, 64),
            candidatePinyinLeftMarginDp = getInt(
                KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP,
                DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP
            ).coerceIn(0, 64),
            toolbarIconBgOpacity = getInt(KEY_TOOLBAR_ICON_BG_OPACITY, DEFAULT_TOOLBAR_ICON_BG_OPACITY).coerceIn(0, 255),
            appearanceColors = WeTypeAppearanceColorGroups.groups.associate { group ->
                group.id to getInt(
                    "$KEY_APPEARANCE_COLOR_PREFIX${group.id}",
                    group.defaultColor
                )
            },
            disableHotUpdate = getBoolean(KEY_DISABLE_HOT_UPDATE, DEFAULT_DISABLE_HOT_UPDATE)
        )
    }

    private fun SharedPreferences.toSnapshotOrNull(): Snapshot? {
        if (!containsAnyPersistedSetting()) return null
        return toSnapshot()
    }

    private fun defaultSnapshot(): Snapshot = Snapshot(
        lightColor = DEFAULT_LIGHT_COLOR,
        darkColor = DEFAULT_DARK_COLOR,
        blurRadius = DEFAULT_BLUR_RADIUS,
        cornerRadius = DEFAULT_CORNER_RADIUS,
        edgeHighlightEnabled = DEFAULT_EDGE_HIGHLIGHT_ENABLED,
        edgeHighlightIntensity = DEFAULT_EDGE_HIGHLIGHT_INTENSITY,
        keyOpacity = DEFAULT_KEY_OPACITY,
        candidateBackgroundAlpha = DEFAULT_CANDIDATE_BACKGROUND_ALPHA,
        candidateBackgroundCorner = DEFAULT_CANDIDATE_BACKGROUND_CORNER,
        candidateBackgroundLeftMarginDp = DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
        candidatePinyinLeftMarginDp = DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP,
        toolbarIconBgOpacity = DEFAULT_TOOLBAR_ICON_BG_OPACITY,
        appearanceColors = WeTypeAppearanceColorGroups.defaultColors(),
        disableHotUpdate = DEFAULT_DISABLE_HOT_UPDATE
    )

    private fun SharedPreferences.containsAnyPersistedSetting(): Boolean {
        if (contains(KEY_LIGHT_COLOR) ||
            contains(KEY_DARK_COLOR) ||
            contains(KEY_BLUR_RADIUS) ||
            contains(KEY_CORNER_RADIUS) ||
            contains(KEY_EDGE_HIGHLIGHT_ENABLED) ||
            contains(KEY_EDGE_HIGHLIGHT_INTENSITY) ||
            contains(KEY_KEY_OPACITY) ||
            contains(KEY_CANDIDATE_BACKGROUND_ALPHA) ||
            contains(KEY_CANDIDATE_BACKGROUND_CORNER) ||
            contains(KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP) ||
            contains(KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP) ||
            contains(KEY_TOOLBAR_ICON_BG_OPACITY) ||
            contains(KEY_DISABLE_HOT_UPDATE)
        ) {
            return true
        }
        return WeTypeAppearanceColorGroups.groups.any { group ->
            contains("$KEY_APPEARANCE_COLOR_PREFIX${group.id}")
        }
    }

    private fun XSharedPreferences?.loadSnapshotOrNull(): Snapshot? {
        this ?: return null
        runCatching { reload() }
        return toSnapshotOrNull()
    }
}

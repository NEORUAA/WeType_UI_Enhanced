package com.xposed.wetypehook.wetype.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
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
    // Keep the original preference key so existing saved values still migrate cleanly.
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

    private val settingsLock = Any()
    private val snapshotDirty = AtomicBoolean(true)

    @Volatile
    private var cachedXposedSnapshot: Snapshot? = null
    private var hostFile: HostPreferencesFile? = null
    private var hostFileObserver: FileObserver? = null

    data class Snapshot(
        val lightColor: Int,
        val darkColor: Int,
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
        val disableHotUpdate: Boolean,
        val hyperMaterialEnabled: Boolean,
        val glassOverrides: GlassMaterialOverrides = GlassMaterialOverrides()
    )

    fun getLightColor(context: Context): Int = readSnapshot(context).lightColor

    fun getDarkColor(context: Context): Int = readSnapshot(context).darkColor

    fun getBlurRadius(context: Context): Int = readSnapshot(context).blurRadius

    fun getCornerRadius(context: Context): Int = readSnapshot(context).cornerRadius

    fun getKeyCornerRadius(context: Context): Int = readSnapshot(context).keyCornerRadius

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

    fun isDisableHotUpdate(context: Context): Boolean = readSnapshot(context).disableHotUpdate

    fun prepareForHotReload() = synchronized(settingsLock) {
        hostFileObserver?.stopWatching()
        hostFileObserver = null
        hostFile = null
        cachedXposedSnapshot = null
        snapshotDirty.set(true)
    }

    fun ensureHostSnapshot(context: Context) {
        val appContext = context.applicationContext ?: context
        if (appContext.packageName != WETYPE_PACKAGE_NAME) return
        synchronized(settingsLock) {
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
            readSnapshotXposed()
        }
    }

    fun save(
        context: Context,
        lightColor: Int,
        darkColor: Int,
        blurRadius: Int,
        cornerRadius: Int,
        keyCornerRadius: Int,
        edgeHighlightEnabled: Boolean,
        edgeHighlightIntensity: Int,
        candidateBackgroundAlpha: Int,
        candidateBackgroundCorner: Float,
        candidateBackgroundLeftMarginDp: Int,
        candidatePinyinLeftMarginDp: Int,
        toolbarIconBgOpacity: Int,
        appearanceColors: Map<String, Int>,
        customIconPath: String = DEFAULT_CUSTOM_ICON_PATH,
        iconScale: Float = DEFAULT_ICON_SCALE,
        disableHotUpdate: Boolean = DEFAULT_DISABLE_HOT_UPDATE,
        hyperMaterialEnabled: Boolean = DEFAULT_HYPER_MATERIAL_ENABLED,
        glassOverrides: GlassMaterialOverrides = GlassMaterialOverrides(),
        onPersisted: (Boolean) -> Unit = {}
    ): Boolean {
        val snapshot = Snapshot(
            lightColor = lightColor,
            darkColor = darkColor,
            blurRadius = blurRadius.coerceIn(0, 100),
            cornerRadius = cornerRadius.coerceIn(0, MAX_CORNER_RADIUS),
            keyCornerRadius = keyCornerRadius.coerceIn(0, MAX_KEY_CORNER_RADIUS),
            edgeHighlightEnabled = edgeHighlightEnabled,
            edgeHighlightIntensity = edgeHighlightIntensity.coerceIn(0, 200),
            candidateBackgroundAlpha = candidateBackgroundAlpha.coerceIn(0, 255),
            candidateBackgroundCorner = candidateBackgroundCorner.coerceIn(
                0f,
                MAX_CANDIDATE_BACKGROUND_CORNER.toFloat()
            ),
            candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp.coerceIn(0, 64),
            candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp.coerceIn(0, 64),
            toolbarIconBgOpacity = toolbarIconBgOpacity.coerceIn(0, 255),
            customIconPath = customIconPath,
            iconScale = iconScale.coerceIn(MIN_ICON_SCALE, MAX_ICON_SCALE),
            appearanceColors = WeTypeAppearanceColorGroups.groups.associate { group ->
                group.id to (appearanceColors[group.id] ?: group.defaultColor)
            },
            disableHotUpdate = disableHotUpdate,
            hyperMaterialEnabled = hyperMaterialEnabled,
            glassOverrides = glassOverrides
        )
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
                    writeSnapshot(preferences, snapshot)
                }.also { saved ->
                    if (saved) cachedXposedSnapshot = snapshot
                    snapshotDirty.set(true)
                }
            }
        }.onFailure { Log.e(TAG, "Failed to save host preferences", it) }.getOrDefault(false)
        onPersisted(persisted)
        return persisted
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

    fun getKeyCornerRadiusXposed(): Int = readSnapshotXposed().keyCornerRadius

    fun isEdgeHighlightEnabledXposed(context: Context): Boolean =
        readSnapshotXposed().edgeHighlightEnabled

    fun getEdgeHighlightIntensityXposed(context: Context): Int =
        readSnapshotXposed().edgeHighlightIntensity

    fun getCandidateBackgroundAlphaXposed(): Int =
        readSnapshotXposed().candidateBackgroundAlpha

    fun getCandidateBackgroundCornerXposed(): Float =
        readSnapshotXposed().candidateBackgroundCorner

    fun getCandidateBackgroundLeftMarginDpXposed(): Int =
        readSnapshotXposed().candidateBackgroundLeftMarginDp

    fun getToolbarIconBgOpacityXposed(): Int =
        readSnapshotXposed().toolbarIconBgOpacity

    /**
     * 自定义 logo 前景的 SVG path 数据，多条 path 以换行分隔；空串表示使用内置徽标。
     * Xposed 侧每次读取，配合 FileObserver 失效机制可在保存后即时生效。
     */
    fun getCustomIconPathXposed(): String = readSnapshotXposed().customIconPath

    /**
     * 键盘图标缩放系数（0.6~1.4，1.0 为原始大小）。
     * Xposed 侧每次读取，保存后由 FileObserver 失效机制即时生效。
     */
    fun getIconScaleXposed(): Float = readSnapshotXposed().iconScale

    fun getCandidatePinyinLeftMarginDpXposed(): Int =
        readSnapshotXposed().candidatePinyinLeftMarginDp

    fun isDisableHotUpdateXposed(): Boolean = readSnapshotXposed().disableHotUpdate

    fun getAppearanceColorXposed(groupId: String): Int =
        readSnapshotXposed().appearanceColors[groupId]
            ?: WeTypeAppearanceColorGroups.findById(groupId)?.defaultColor
            ?: 0

    fun getAppearanceColorsXposed(): Map<String, Int> = readSnapshotXposed().appearanceColors

    fun readSnapshot(context: Context): Snapshot {
        ensureHostSnapshot(context)
        return readSnapshotXposed()
    }

    internal fun readSnapshotXposed(): Snapshot {
        if (!snapshotDirty.get()) cachedXposedSnapshot?.let { return it }
        return synchronized(settingsLock) {
            val file = hostFile ?: return@synchronized defaultSnapshot()
            if (snapshotDirty.getAndSet(false) || cachedXposedSnapshot == null) {
                runCatching { file.read().toSnapshot() }
                    .onSuccess { cachedXposedSnapshot = it }
                    .onFailure {
                        if (cachedXposedSnapshot == null) cachedXposedSnapshot = defaultSnapshot()
                        Log.e(TAG, "Failed to read host preferences", it)
                    }
            }
            cachedXposedSnapshot ?: defaultSnapshot()
        }
    }

    private fun writeSnapshot(
        preferences: SharedPreferences,
        snapshot: Snapshot
    ): Boolean {
        val editor = preferences.edit()
            .putInt(KEY_LIGHT_COLOR, snapshot.lightColor)
            .putInt(KEY_DARK_COLOR, snapshot.darkColor)
            .putInt(KEY_BLUR_RADIUS, snapshot.blurRadius)
            .putInt(KEY_CORNER_RADIUS, snapshot.cornerRadius)
            .putInt(KEY_KEY_CORNER_RADIUS, snapshot.keyCornerRadius)
            .putBoolean(KEY_EDGE_HIGHLIGHT_ENABLED, snapshot.edgeHighlightEnabled)
            .putInt(KEY_EDGE_HIGHLIGHT_INTENSITY, snapshot.edgeHighlightIntensity)
            .putInt(KEY_CANDIDATE_BACKGROUND_ALPHA, snapshot.candidateBackgroundAlpha)
            .putFloat(KEY_CANDIDATE_BACKGROUND_CORNER, snapshot.candidateBackgroundCorner)
            .putInt(
                KEY_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
                snapshot.candidateBackgroundLeftMarginDp
            )
            .putInt(
                KEY_CANDIDATE_PINYIN_LEFT_MARGIN_DP,
                snapshot.candidatePinyinLeftMarginDp
            )
            .putInt(KEY_TOOLBAR_ICON_BG_OPACITY, snapshot.toolbarIconBgOpacity)
            .putString(KEY_CUSTOM_ICON_PATH, snapshot.customIconPath)
            .putFloat(KEY_ICON_SCALE, snapshot.iconScale)
            .putBoolean(KEY_HYPER_MATERIAL_ENABLED, snapshot.hyperMaterialEnabled)
            .putBoolean(KEY_DISABLE_HOT_UPDATE, snapshot.disableHotUpdate)
            .putBoolean(KEY_KEY_OPACITY_MIGRATED, true)
            .remove(KEY_KEY_OPACITY)
        fun writeFloatParameters(key: String, values: List<Float>?, maxCount: Int) {
            editor.remove("${key}_count")
            repeat(maxCount) { editor.remove("${key}_$it") }
            values?.let {
                editor.putInt("${key}_count", it.size)
                it.forEachIndexed { index, value -> editor.putFloat("${key}_$index", value) }
            }
        }
        writeFloatParameters("glass_params", snapshot.glassOverrides.glass, 42)
        writeFloatParameters("glass_bloom", snapshot.glassOverrides.bloom, 16)
        editor.remove("glass_blur_small").remove("glass_blur_large").remove("glass_material_type")
        snapshot.glassOverrides.blurRadii?.let {
            editor.putInt("glass_blur_small", it[0]).putInt("glass_blur_large", it[1])
        }
        snapshot.glassOverrides.materialType?.let { editor.putInt("glass_material_type", it) }
        WeTypeAppearanceColorGroups.groups.forEach { group ->
            editor.putInt(
                "$KEY_APPEARANCE_COLOR_PREFIX${group.id}",
                snapshot.appearanceColors[group.id] ?: group.defaultColor
            )
        }
        WeTypeAppearanceColorGroups.obsoleteGroupIds.forEach { groupId ->
            editor.remove("$KEY_APPEARANCE_COLOR_PREFIX$groupId")
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

    private fun Map<String, Any>.toSnapshot(): Snapshot {
        val shouldMigrateLegacyKeyOpacity = contains(KEY_KEY_OPACITY) &&
            !getBoolean(KEY_KEY_OPACITY_MIGRATED, false)
        val legacyKeyOpacity = if (shouldMigrateLegacyKeyOpacity) {
            getInt(KEY_KEY_OPACITY, 255).coerceIn(0, 255)
        } else {
            null
        }
        return Snapshot(
            lightColor = getInt(KEY_LIGHT_COLOR, DEFAULT_LIGHT_COLOR),
            darkColor = getInt(KEY_DARK_COLOR, DEFAULT_DARK_COLOR),
            blurRadius = getInt(KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS),
            cornerRadius = getInt(KEY_CORNER_RADIUS, DEFAULT_CORNER_RADIUS)
                .coerceIn(0, MAX_CORNER_RADIUS),
            keyCornerRadius = getInt(KEY_KEY_CORNER_RADIUS, DEFAULT_KEY_CORNER_RADIUS)
                .coerceIn(0, MAX_KEY_CORNER_RADIUS),
            edgeHighlightEnabled = getBoolean(
                KEY_EDGE_HIGHLIGHT_ENABLED,
                DEFAULT_EDGE_HIGHLIGHT_ENABLED
            ),
            edgeHighlightIntensity = getInt(
                KEY_EDGE_HIGHLIGHT_INTENSITY,
                DEFAULT_EDGE_HIGHLIGHT_INTENSITY
            ),
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
            customIconPath = getString(KEY_CUSTOM_ICON_PATH, DEFAULT_CUSTOM_ICON_PATH),
            iconScale = getFloat(KEY_ICON_SCALE, DEFAULT_ICON_SCALE)
                .coerceIn(MIN_ICON_SCALE, MAX_ICON_SCALE),
            appearanceColors = WeTypeAppearanceColorGroups.groups.associate { group ->
                val key = "$KEY_APPEARANCE_COLOR_PREFIX${group.id}"
                val fallbackColor = if (legacyKeyOpacity != null) {
                    legacyKeyColorDefaults[group.id] ?: group.defaultColor
                } else {
                    group.defaultColor
                }
                val color = getInt(key, fallbackColor)
                group.id to migrateLegacyKeyOpacity(group, color, legacyKeyOpacity)
            },
            hyperMaterialEnabled = getBoolean(KEY_HYPER_MATERIAL_ENABLED, DEFAULT_HYPER_MATERIAL_ENABLED),
            glassOverrides = GlassMaterialOverrides.read(this),
            disableHotUpdate = getBoolean(KEY_DISABLE_HOT_UPDATE, DEFAULT_DISABLE_HOT_UPDATE)
        )
    }

    private fun migrateLegacyKeyOpacity(
        group: WeTypeAppearanceColorGroup,
        color: Int,
        legacyKeyOpacity: Int?
    ): Int {
        if (!group.isKeyColorGroup || legacyKeyOpacity == null || Color.alpha(color) != 0xFF) {
            return color
        }
        return (legacyKeyOpacity shl 24) or (color and 0x00FFFFFF)
    }

    private fun Map<String, Any>.getInt(key: String, default: Int): Int = this[key] as? Int ?: default

    private fun Map<String, Any>.getFloat(key: String, default: Float): Float = this[key] as? Float ?: default

    private fun Map<String, Any>.getBoolean(key: String, default: Boolean): Boolean = this[key] as? Boolean ?: default

    private fun Map<String, Any>.getString(key: String, default: String): String = this[key] as? String ?: default

    private fun defaultSnapshot(): Snapshot = Snapshot(
        lightColor = DEFAULT_LIGHT_COLOR,
        darkColor = DEFAULT_DARK_COLOR,
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
        disableHotUpdate = DEFAULT_DISABLE_HOT_UPDATE,
        hyperMaterialEnabled = DEFAULT_HYPER_MATERIAL_ENABLED
    )

}

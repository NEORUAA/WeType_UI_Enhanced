package com.xposed.wetypehook

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.kyant.capsule.ContinuousRoundedRectangle
import com.xposed.wetypehook.wetype.graphics.WeTypeHyperMaterial
import com.xposed.wetypehook.wetype.graphics.SvgPathImporter
import com.xposed.wetypehook.wetype.graphics.WeTypeBloomStrokeDrawable
import com.xposed.wetypehook.wetype.graphics.WeTypeCornerRadii
import com.xposed.wetypehook.wetype.graphics.createWeTypeContinuousRoundedPath
import com.xposed.wetypehook.wetype.settings.DARK_KEY_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.LIGHT_KEY_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.WeTypeAppearanceColorGroups
import com.xposed.wetypehook.wetype.settings.GlassMaterialOverrides
import com.xposed.wetypehook.wetype.settings.GlassSliderParameter
import com.xposed.wetypehook.wetype.settings.GlassOverrideField
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

const val EXTRA_OPEN_WETYPE_EMBEDDED_SETTINGS = "com.xposed.wetypehook.extra.OPEN_WETYPE_EMBEDDED_SETTINGS"
private const val ACTIVATION_HEARTBEAT_WINDOW_MS = 4_000L
private const val ACTIVATION_KEYBOARD_RETRY_COUNT = 3
private const val ACTIVATION_KEYBOARD_RETRY_DELAY_MS = 450L

private fun ModuleActivationTracker.ActivationStatus.hasFreshHeartbeat(
    now: Long = System.currentTimeMillis()
): Boolean {
    if (!isActive || lastActivatedAt <= 0L) return false
    return now - lastActivatedAt <= ACTIVATION_HEARTBEAT_WINDOW_MS
}

class MainActivity : ComponentActivity() {
    private var hasAttemptedEmbeddedLaunch = false
    private var activationStatusListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var activationStatus by mutableStateOf(
        ModuleActivationTracker.ActivationStatus(
            isActive = false,
            sourcePackage = null,
            sourceProcess = null,
            lastActivatedAt = 0L
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activationStatus = ModuleActivationTracker.resolveStatusForUi(this)
        activationStatusListener = ModuleActivationTracker.registerStatusListener(this) { status ->
            activationStatus = status
            if (!status.hasFreshHeartbeat()) return@registerStatusListener
            runOnUiThread {
                launchEmbeddedSettingsAndFinish()
            }
        }
        setContent {
            ActivationEntryApp(
                isActive = activationStatus.hasFreshHeartbeat(),
                onOpenEmbeddedSettings = ::launchEmbeddedSettingsAndFinish
            )
        }
        launchEmbeddedSettingsIfActive()
    }

    override fun onResume() {
        super.onResume()
        activationStatus = ModuleActivationTracker.resolveStatusForUi(this)
        launchEmbeddedSettingsIfActive()
    }

    override fun onDestroy() {
        activationStatusListener?.let {
            ModuleActivationTracker.unregisterStatusListener(this, it)
            activationStatusListener = null
        }
        super.onDestroy()
    }

    private fun launchEmbeddedSettingsIfActive(): Boolean {
        if (!hasAttemptedEmbeddedLaunch && activationStatus.hasFreshHeartbeat()) {
            return launchEmbeddedSettingsAndFinish()
        }
        return false
    }

    private fun launchEmbeddedSettingsAndFinish(): Boolean {
        if (hasAttemptedEmbeddedLaunch) return false
        hasAttemptedEmbeddedLaunch = true
        val launched = openEmbeddedWeTypeSettings()
        if (launched) {
            finish()
        } else {
            hasAttemptedEmbeddedLaunch = false
        }
        return launched
    }

    private fun openEmbeddedWeTypeSettings(): Boolean {
        val launchIntents = listOfNotNull(
            packageManager.getLaunchIntentForPackage("com.tencent.wetype")?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_WETYPE_EMBEDDED_SETTINGS, true)
            },
            Intent(Intent.ACTION_MAIN).apply {
                setPackage("com.tencent.wetype")
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_WETYPE_EMBEDDED_SETTINGS, true)
            },
            Intent().apply {
                component = ComponentName(
                    "com.tencent.wetype",
                    "com.tencent.wetype.plugin.hld.ui.ImeAboutActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_WETYPE_EMBEDDED_SETTINGS, true)
            }
        )

        for (intent in launchIntents) {
            val launched = runCatching {
                startActivity(intent)
                true
            }.getOrElse { false }
            if (launched) return true
        }

        Toast.makeText(this, "Failed to open WeType", Toast.LENGTH_SHORT).show()
        return false
    }
}

@Composable
private fun ActivationEntryApp(
    isActive: Boolean,
    onOpenEmbeddedSettings: () -> Unit
) {
    val darkMode = isSystemInDarkTheme()
    MiuixTheme(colors = if (darkMode) darkColorScheme() else lightColorScheme()) {
        SyncSystemBars(darkMode = darkMode)
        ActivationEntryScreen(
            isActive = isActive,
            onOpenEmbeddedSettings = onOpenEmbeddedSettings
        )
    }
}

@Composable
private fun ActivationEntryScreen(
    isActive: Boolean,
    onOpenEmbeddedSettings: () -> Unit
) {
    var probeText by rememberSaveable { mutableStateOf("") }
    var isCheckingHeartbeat by rememberSaveable { mutableStateOf(!isActive) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(isActive) {
        if (isActive) {
            isCheckingHeartbeat = false
            return@LaunchedEffect
        }

        isCheckingHeartbeat = true
        repeat(ACTIVATION_KEYBOARD_RETRY_COUNT) {
            delay(ACTIVATION_KEYBOARD_RETRY_DELAY_MS)
            focusRequester.requestFocus()
            keyboardController?.show()
        }
        isCheckingHeartbeat = false
    }

    val backgroundColor = if (isSystemInDarkTheme()) {
        ComposeColor.Black
    } else {
        ComposeColor(0xFFF7F7F7)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = MiuixIcons.Info,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .width(72.dp)
                        .height(72.dp)
                )
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = stringResource(
                        if (isActive) {
                            R.string.activation_active_title
                        } else {
                            R.string.activation_required_title
                        }
                    ),
                    style = MiuixTheme.textStyles.headline1,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(
                        if (isActive) {
                            R.string.activation_active_summary
                        } else {
                            R.string.activation_required_summary
                        }
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.main,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(0.dp)
        ) {
            if (isActive) {
                BasicComponent(
                    title = stringResource(R.string.activation_open_embedded_settings),
                    titleColor = BasicComponentDefaults.titleColor(
                        color = MiuixTheme.colorScheme.primary
                    ),
                    onClick = onOpenEmbeddedSettings
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (isCheckingHeartbeat) {
                                R.string.activation_detecting_summary
                            } else {
                                R.string.activation_probe_summary
                            }
                        ),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = probeText,
                        onValueChange = { probeText = it },
                        label = stringResource(R.string.activation_probe_label),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }
            }
        }


    }
}

@Composable
internal fun WeTypeSettingsApp(
    settingsContext: Context
) {
    val darkMode = isSystemInDarkTheme()
    MiuixTheme(colors = if (darkMode) darkColorScheme() else lightColorScheme()) {
        SyncSystemBars(darkMode = darkMode)
        WeTypeSettingsScreen(
            settingsContext = settingsContext
        )
    }
}

@Composable
private fun SyncSystemBars(darkMode: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return

    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val systemBarColor = if (darkMode) Color.BLACK else Color.parseColor("#F7F7F7")
        window.statusBarColor = systemBarColor
        window.navigationBarColor = systemBarColor
        val insetsController = WindowCompat.getInsetsController(window, view)
        insetsController.isAppearanceLightStatusBars = !darkMode
        insetsController.isAppearanceLightNavigationBars = !darkMode
    }
}

@Composable
private fun WeTypeSettingsScreen(
    settingsContext: Context
) {
    val context = LocalContext.current
    val preferencesContext = remember(settingsContext) { settingsContext }
    val isEmbeddedHost = remember(settingsContext) {
        (context.applicationContext ?: context).packageName != "com.xposed.wetypehook"
    }
    val snapshot = remember(preferencesContext) { WeTypeSettings.readSnapshot(preferencesContext) }
    var activationStatus by remember(preferencesContext) {
        mutableStateOf(ModuleActivationTracker.resolveStatusForUi(preferencesContext))
    }
    val systemDarkMode = isSystemInDarkTheme()
    val appearanceGroups = remember { WeTypeAppearanceColorGroups.groups }
    val appearanceSectionGroups = remember(appearanceGroups) {
        appearanceGroups.filterNot { it.isKeyColorGroup }
    }

    var lightColor by rememberSaveable { mutableIntStateOf(snapshot.lightColor) }
    var darkColor by rememberSaveable { mutableIntStateOf(snapshot.darkColor) }
    val glassInput = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { mutableStateListOf(*it.toTypedArray()) })
    ) { mutableStateListOf(*GlassOverrideField.entries.map { snapshot.glassOverrides.text(it) }.toTypedArray()) }
    val glassSupported = remember { WeTypeHyperMaterial.areGlassOverridesAvailable() }
    val parsedGlassOverrides = runCatching {
        GlassMaterialOverrides.parse(GlassOverrideField.entries.associateWith { glassInput[it.ordinal] })
    }.getOrNull()
    var previewGlassOverrides by remember { mutableStateOf(snapshot.glassOverrides) }
    LaunchedEffect(parsedGlassOverrides) {
        // Recreating a native material during every slider tick would flash its fallback tint.
        delay(120)
        parsedGlassOverrides?.let { previewGlassOverrides = it }
    }
    var hyperMaterialEnabled by rememberSaveable { mutableStateOf(snapshot.hyperMaterialEnabled) }
    var hyperMaterialAvailable by remember(preferencesContext) {
        mutableStateOf(WeTypeHyperMaterial.isAvailable(preferencesContext))
    }
    DisposableEffect(preferencesContext) {
        val stopObserving = WeTypeHyperMaterial.observeAvailability(preferencesContext) {
            hyperMaterialAvailable = WeTypeHyperMaterial.isAvailable(preferencesContext)
        }
        onDispose { stopObserving() }
    }
    var blurRadius by rememberSaveable { mutableIntStateOf(snapshot.blurRadius) }
    var cornerRadius by rememberSaveable { mutableIntStateOf(snapshot.cornerRadius) }
    var keyCornerRadius by rememberSaveable { mutableIntStateOf(snapshot.keyCornerRadius) }
    var edgeHighlightEnabled by rememberSaveable { mutableStateOf(snapshot.edgeHighlightEnabled) }
    var edgeHighlightIntensity by rememberSaveable { mutableIntStateOf(snapshot.edgeHighlightIntensity) }
    var candidateBackgroundAlpha by rememberSaveable {
        mutableIntStateOf(snapshot.candidateBackgroundAlpha)
    }
    var candidateBackgroundCorner by rememberSaveable {
        mutableIntStateOf(snapshot.candidateBackgroundCorner.roundToInt())
    }
    var candidateBackgroundLeftMarginDp by rememberSaveable {
        mutableStateOf(snapshot.candidateBackgroundLeftMarginDp.toString())
    }
    var candidatePinyinLeftMarginDp by rememberSaveable {
        mutableStateOf(snapshot.candidatePinyinLeftMarginDp.toString())
    }
    var toolbarIconBgOpacity by rememberSaveable {
        mutableIntStateOf(snapshot.toolbarIconBgOpacity)
    }
    var customIconPath by rememberSaveable { mutableStateOf(snapshot.customIconPath) }
    var iconScale by rememberSaveable { mutableFloatStateOf(snapshot.iconScale) }
    var disableHotUpdate by rememberSaveable {
        mutableStateOf(snapshot.disableHotUpdate)
    }
    val appearanceGroupColors = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { restored -> mutableStateListOf(*restored.toTypedArray()) }
        )
    ) {
        mutableStateListOf(
            *appearanceGroups.map { group ->
                snapshot.appearanceColors[group.id] ?: group.defaultColor
            }.toTypedArray()
        )
    }
    var currentModeIsDark by rememberSaveable { mutableStateOf(systemDarkMode) }
    var colorInput by rememberSaveable {
        mutableStateOf(formatRgb(if (currentModeIsDark) darkColor else lightColor))
    }
    var alphaValue by rememberSaveable {
        mutableIntStateOf(Color.alpha(if (currentModeIsDark) darkColor else lightColor))
    }

    fun currentColor(): Int = if (currentModeIsDark) darkColor else lightColor

    fun syncEditorFromState() {
        alphaValue = Color.alpha(currentColor())
        colorInput = formatRgb(currentColor())
    }

    fun updateColorFromArgb(argb: Int) {
        if (currentModeIsDark) darkColor = argb else lightColor = argb
    }

    fun currentAppearanceColors(): Map<String, Int> = appearanceGroups.mapIndexed { index, group ->
        group.id to appearanceGroupColors[index]
    }.toMap()

    fun groupIndex(groupId: String): Int =
        appearanceGroups.indexOfFirst { it.id == groupId }

    fun keyColorGroup(isDark: Boolean) = appearanceGroups.first {
        it.id == if (isDark) {
            DARK_KEY_COLOR_GROUP_ID
        } else {
            LIGHT_KEY_COLOR_GROUP_ID
        }
    }

    fun keyColorValue(isDark: Boolean): Int {
        val group = keyColorGroup(isDark)
        return appearanceGroupColors[groupIndex(group.id)]
    }

    fun saveSettings(
        successMessage: Int = R.string.settings_saved,
        glassOverridesToSave: GlassMaterialOverrides? = parsedGlassOverrides
    ): Boolean {
        if (glassOverridesToSave == null) {
            Toast.makeText(context, R.string.settings_glass_invalid, Toast.LENGTH_SHORT).show()
            return false
        }
        return WeTypeSettings.save(
            context = preferencesContext,
            lightColor = lightColor,
            darkColor = darkColor,
            blurRadius = blurRadius,
            cornerRadius = cornerRadius,
            keyCornerRadius = keyCornerRadius,
            edgeHighlightEnabled = edgeHighlightEnabled,
            edgeHighlightIntensity = edgeHighlightIntensity,
            candidateBackgroundAlpha = candidateBackgroundAlpha,
            candidateBackgroundCorner = candidateBackgroundCorner.toFloat(),
            candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp.toIntOrNull()
                ?: WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
            candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp.toIntOrNull()
                ?: WeTypeSettings.DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP,
            toolbarIconBgOpacity = toolbarIconBgOpacity,
            appearanceColors = currentAppearanceColors(),
            customIconPath = customIconPath,
            iconScale = iconScale,
            disableHotUpdate = disableHotUpdate,
            hyperMaterialEnabled = hyperMaterialEnabled,
            glassOverrides = glassOverridesToSave,
            onPersisted = { saved ->
                Toast.makeText(
                    context,
                    if (saved) successMessage else R.string.settings_save_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    fun restoreDefaults() {
        lightColor = WeTypeSettings.DEFAULT_LIGHT_COLOR
        darkColor = WeTypeSettings.DEFAULT_DARK_COLOR
        glassInput.indices.forEach { glassInput[it] = "" }
        previewGlassOverrides = GlassMaterialOverrides()
        hyperMaterialEnabled = WeTypeSettings.DEFAULT_HYPER_MATERIAL_ENABLED
        blurRadius = WeTypeSettings.DEFAULT_BLUR_RADIUS
        cornerRadius = WeTypeSettings.DEFAULT_CORNER_RADIUS
        keyCornerRadius = WeTypeSettings.DEFAULT_KEY_CORNER_RADIUS
        edgeHighlightEnabled = WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_ENABLED
        edgeHighlightIntensity = WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_INTENSITY
        candidateBackgroundAlpha = WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_ALPHA
        candidateBackgroundCorner = WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_CORNER.roundToInt()
        candidateBackgroundLeftMarginDp =
            WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP.toString()
        candidatePinyinLeftMarginDp = WeTypeSettings.DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP.toString()
        toolbarIconBgOpacity = WeTypeSettings.DEFAULT_TOOLBAR_ICON_BG_OPACITY
        customIconPath = WeTypeSettings.DEFAULT_CUSTOM_ICON_PATH
        iconScale = WeTypeSettings.DEFAULT_ICON_SCALE
        disableHotUpdate = WeTypeSettings.DEFAULT_DISABLE_HOT_UPDATE
        appearanceGroups.forEachIndexed { index, group ->
            appearanceGroupColors[index] = group.defaultColor
        }
        syncEditorFromState()
        saveSettings(successMessage = R.string.settings_reset_toast, glassOverridesToSave = GlassMaterialOverrides())
    }

    if (isEmbeddedHost) {
        LaunchedEffect(preferencesContext) {
            ModuleActivationTracker.syncActivationFromUiContext(preferencesContext)
        }
    } else {
        DisposableEffect(preferencesContext) {
            val listener = ModuleActivationTracker.registerStatusListener(preferencesContext) {
                activationStatus = it
            }
            onDispose {
                ModuleActivationTracker.unregisterStatusListener(preferencesContext, listener)
            }
        }
    }

    val previewColor = currentColor()
    val scrollBehavior = MiuixScrollBehavior(state = rememberTopAppBarState())

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    ModuleActivationTag(
                        status = activationStatus,
                    )
                },
                actions = {
                    IconButton(
                        onClick = { saveSettings() }
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Ok,
                            contentDescription = stringResource(R.string.settings_save_title)
                        )
                    }
                },
                bottomContent = {
                    PreviewSection(
                        glassOverrides = previewGlassOverrides,
                        hyperMaterialEnabled = hyperMaterialEnabled,
                        color = previewColor,
                        blurRadius = blurRadius,
                        cornerRadius = cornerRadius,
                        keyCornerRadius = keyCornerRadius,
                        edgeHighlightEnabled = edgeHighlightEnabled,
                        edgeHighlightIntensity = edgeHighlightIntensity,
                        lightKeyColor = keyColorValue(false),
                        darkKeyColor = keyColorValue(true),
                        isDark = currentModeIsDark
                    )
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 颜色分组
            item {
                SmallTitle(
                    text = stringResource(R.string.settings_group_color)
                )
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column {
                        // 模式切换 - 使用 TabRow
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_section_mode),
                                style = MiuixTheme.textStyles.main
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val tabs = listOf(
                                stringResource(R.string.settings_light_mode),
                                stringResource(R.string.settings_dark_mode)
                            )
                            TabRowWithContour(
                                tabs = tabs,
                                selectedTabIndex = if (currentModeIsDark) 1 else 0,
                                onTabSelected = { index ->
                                    val newDarkMode = index == 1
                                    if (newDarkMode != currentModeIsDark) {
                                        currentModeIsDark = newDarkMode
                                        syncEditorFromState()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // 透明度滑块
                        SliderPreferenceItem(
                            title = stringResource(R.string.settings_alpha_title),
                            value = alphaValue,
                            max = 255,
                            onValueChange = {
                                alphaValue = it
                                val rgb = currentColor() and 0xFFFFFF
                                updateColorFromArgb((alphaValue shl 24) or rgb)
                                colorInput = formatRgb(currentColor())
                            }
                        )

                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_custom_color),
                                style = MiuixTheme.textStyles.main
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.settings_color_helper),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.body2
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            TextField(
                                value = colorInput,
                                onValueChange = { input ->
                                    val trimmed = input.trim()
                                    val hasPrefix = trimmed.startsWith("#")
                                    val body = trimmed.removePrefix("#")
                                    if (body.length > 6 || !body.matches(Regex("^[0-9a-fA-F]*$"))) {
                                        return@TextField
                                    }

                                    colorInput = if (hasPrefix || body.isNotEmpty()) "#$body" else ""

                                    if (body.length == 6) {
                                        runCatching {
                                            val opaque = Color.parseColor("#$body")
                                            val argb = Color.argb(
                                                alphaValue.coerceIn(0, 255),
                                                Color.red(opaque),
                                                Color.green(opaque),
                                                Color.blue(opaque)
                                            )
                                            updateColorFromArgb(argb)
                                        }
                                    }
                                },
                                label = stringResource(R.string.settings_color_label),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        val currentKeyGroup = keyColorGroup(currentModeIsDark)
                        val currentKeyGroupIndex = groupIndex(currentKeyGroup.id)

                        KeyColorEditor(
                            title = if (currentModeIsDark) {
                                stringResource(R.string.settings_dark_key_color_title)
                            } else {
                                stringResource(R.string.settings_light_key_color_title)
                            },
                            summary = stringResource(
                                R.string.settings_key_color_group_summary,
                                Color.alpha(appearanceGroupColors[currentKeyGroupIndex]),
                                currentKeyGroup.entryCount
                            ),
                            color = appearanceGroupColors[currentKeyGroupIndex],
                            onColorChange = { appearanceGroupColors[currentKeyGroupIndex] = it }
                        )
                    }
                }
            }

            // 外观分组
            item {
                SmallTitle(
                    text = stringResource(R.string.settings_group_appearance)
                )
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column {
                        MiuixSwitchWidget(
                            title = stringResource(R.string.settings_hyper_material_title),
                            description = stringResource(
                                if (hyperMaterialAvailable) R.string.settings_hyper_material_desc
                                else R.string.settings_hyper_material_unavailable
                            ),
                            checked = hyperMaterialEnabled,
                            enabled = hyperMaterialAvailable,
                            onCheckedChange = { hyperMaterialEnabled = it }
                        )
                        if (hyperMaterialEnabled) {
                            HorizontalDivider()
                            GlassOverrideEditor(
                                values = glassInput,
                                enabled = hyperMaterialAvailable && glassSupported,
                                onValueChange = { index, value -> glassInput[index] = value },
                                onReset = {
                                    val defaults = GlassMaterialOverrides().withGlassEnabled(true)
                                    GlassOverrideField.entries.forEach { glassInput[it.ordinal] = defaults.text(it) }
                                }
                            )
                        }
                        HorizontalDivider()
                        MiuixSwitchWidget(
                            enabled = !hyperMaterialEnabled,
                            title = stringResource(R.string.settings_edge_highlight_title),
                            description = stringResource(R.string.settings_edge_highlight_desc),
                            checked = edgeHighlightEnabled,
                            onCheckedChange = { edgeHighlightEnabled = it }
                        )

                        if (edgeHighlightEnabled) {
                            SliderPreferenceItem(
                                title = stringResource(R.string.settings_edge_highlight_intensity_title),
                                enabled = !hyperMaterialEnabled,
                                value = edgeHighlightIntensity,
                                max = 200,
                                onValueChange = { edgeHighlightIntensity = it }
                            )
                        }

                        HorizontalDivider()

                        // 模糊滑块
                        SliderPreferenceItem(
                            title = stringResource(R.string.settings_blur_title),
                            enabled = !hyperMaterialEnabled,
                            value = blurRadius,
                            max = 100,
                            onValueChange = { blurRadius = it }
                        )

                        // 圆角滑块
                        SliderPreferenceItem(
                            title = stringResource(R.string.settings_corner_title),
                            value = cornerRadius,
                            max = WeTypeSettings.MAX_CORNER_RADIUS,
                            onValueChange = { cornerRadius = it }
                        )

                        SliderPreferenceItem(
                            title = stringResource(R.string.settings_key_corner_title),
                            value = keyCornerRadius,
                            max = WeTypeSettings.MAX_KEY_CORNER_RADIUS,
                            onValueChange = { keyCornerRadius = it }
                        )

                        SliderPreferenceItem(
                            title = stringResource(R.string.settings_toolbar_icon_bg_opacity_title),
                            value = toolbarIconBgOpacity,
                            max = 255,
                            onValueChange = { toolbarIconBgOpacity = it }
                        )

                        SliderPreferenceItem(
                            title = stringResource(R.string.settings_icon_scale_title),
                            value = iconScale,
                            range = WeTypeSettings.MIN_ICON_SCALE..WeTypeSettings.MAX_ICON_SCALE,
                            step = 0.05f,
                            format = { "${(it * 100).roundToInt()}%" },
                            onValueChange = { iconScale = it }
                        )

                        IconShapePreferenceItem(
                            value = customIconPath,
                            onValueChange = { customIconPath = it },
                            onReset = { customIconPath = WeTypeSettings.DEFAULT_CUSTOM_ICON_PATH }
                        )

                        appearanceSectionGroups.forEach { group ->
                            val index = groupIndex(group.id)
                            AppearanceColorGroupEditor(
                                title = group.displayName,
                                summary = if (group.entryCount == 0) {
                                    stringResource(
                                        R.string.settings_module_color_summary,
                                        formatArgb(group.defaultColor)
                                    )
                                } else {
                                    stringResource(
                                        R.string.settings_appearance_color_group_summary,
                                        group.entryCount,
                                        formatArgb(group.defaultColor)
                                    )
                                },
                                color = appearanceGroupColors[index],
                                onColorChange = { appearanceGroupColors[index] = it }
                            )
                        }

                        NumericTextSettingItem(
                            title = stringResource(R.string.settings_candidate_background_left_margin_title),
                            summary = stringResource(R.string.settings_candidate_background_left_margin_desc),
                            value = candidateBackgroundLeftMarginDp,
                            onValueChange = { input ->
                                if (sanitizeIntegerInput(input, maxLength = 2) != null) {
                                    candidateBackgroundLeftMarginDp = input
                                }
                            }
                        )

                        NumericTextSettingItem(
                            title = stringResource(R.string.settings_candidate_pinyin_margin_title),
                            summary = stringResource(R.string.settings_candidate_pinyin_margin_desc),
                            value = candidatePinyinLeftMarginDp,
                            onValueChange = { input ->
                                if (sanitizeIntegerInput(input, maxLength = 2) != null) {
                                    candidatePinyinLeftMarginDp = input
                                }
                            }
                        )

                        SliderPreferenceItem(
                            title = stringResource(R.string.settings_key_color_hook_alpha_title),
                            value = candidateBackgroundAlpha,
                            max = 255,
                            onValueChange = { candidateBackgroundAlpha = it }
                        )

                        SliderPreferenceItem(
                            title = stringResource(R.string.settings_candidate_corner_title),
                            value = candidateBackgroundCorner,
                            max = WeTypeSettings.MAX_CANDIDATE_BACKGROUND_CORNER,
                            onValueChange = { candidateBackgroundCorner = it }
                        )
                    }
                }
            }

            // 其他分组
            item {
                SmallTitle(
                    text = stringResource(R.string.settings_group_other)
                )
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column {
                        MiuixSwitchWidget(
                            title = stringResource(R.string.settings_disable_hot_update_title),
                            description = stringResource(R.string.settings_disable_hot_update_desc),
                            checked = disableHotUpdate,
                            onCheckedChange = { disableHotUpdate = it }
                        )
                    }
                }
            }

            // 操作分组
            item {
                SmallTitle(
                    text = stringResource(R.string.settings_group_actions)
                )
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column {
                        ArrowPreference(
                            title = stringResource(R.string.settings_reset_title),
                            summary = stringResource(R.string.settings_reset_desc),
                            onClick = ::restoreDefaults
                        )

                        HorizontalDivider()

                        BasicComponent(
                            title = stringResource(R.string.settings_visit_github_title),
                            titleColor = BasicComponentDefaults.titleColor(
                                color = MiuixTheme.colorScheme.primary
                            ),
                            onClick = {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/NEORUAA/MIUI_IME_Unlock")
                                )
                                context.startActivity(intent)
                            }
                        )
                    }
                }
            }

        }
    }
}

@Composable
private fun ModuleActivationTag(
    status: ModuleActivationTracker.ActivationStatus,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (status.isActive) {
        ComposeColor(0xFF4F9A71)
    } else {
        ComposeColor(0xFFC86F67)
    }
    val label = if (status.isActive) {
        stringResource(R.string.settings_module_active_tag)
    } else {
        stringResource(R.string.settings_module_inactive_tag)
    }
    Box(
        modifier = modifier
            .clip(ContinuousRoundedRectangle(999.dp))
            .background(backgroundColor)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = ComposeColor.White,
            style = MiuixTheme.textStyles.body2
        )
    }
}

@Composable
private fun PreviewSection(
    glassOverrides: GlassMaterialOverrides,
    hyperMaterialEnabled: Boolean,
    color: Int,
    blurRadius: Int,
    cornerRadius: Int,
    keyCornerRadius: Int,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    lightKeyColor: Int,
    darkKeyColor: Int,
    isDark: Boolean
) {
    Column(
        modifier = Modifier.padding(bottom = 16.dp)
    ) {
        SmallTitle(
            text = stringResource(R.string.settings_preview_title)
        )
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp),
            colors = CardDefaults.defaultColors(
                color = ComposeColor.Transparent
            )
        ) {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                Image(
                    painter = painterResource(R.drawable.natural_texture_004),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
                Column {
                    PreviewCard(
                        glassOverrides = glassOverrides,
                        hyperMaterialEnabled = hyperMaterialEnabled,
                        color = color,
                        blurRadius = blurRadius,
                        cornerRadius = cornerRadius,
                        keyCornerRadius = keyCornerRadius,
                        edgeHighlightEnabled = edgeHighlightEnabled,
                        edgeHighlightIntensity = edgeHighlightIntensity,
                        lightKeyColor = lightKeyColor,
                        darkKeyColor = darkKeyColor,
                        isDark = isDark
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(
    glassOverrides: GlassMaterialOverrides,
    hyperMaterialEnabled: Boolean,
    color: Int,
    blurRadius: Int,
    cornerRadius: Int,
    keyCornerRadius: Int,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    lightKeyColor: Int,
    darkKeyColor: Int,
    isDark: Boolean
) {
    val context = LocalContext.current
    val displayColor = if (hyperMaterialEnabled && glassOverrides.glass == null) WeTypeHyperMaterial.fallbackColor(isDark) else color
    val weTypeFontFamily = remember(context) {
        FontFamily(
            Font(
                path = "WE-Regular.ttf",
                assetManager = context.assets
            )
        )
    }
    val previewCornerValue = cornerRadius.coerceIn(0, WeTypeSettings.MAX_CORNER_RADIUS)
    val previewCorner = previewCornerValue.dp
    val previewMinHeight = maxOf(88.dp, (previewCornerValue * 2).dp)
    val previewShape = ContinuousRoundedRectangle(
        topStart = CornerSize(previewCorner),
        topEnd = CornerSize(previewCorner),
        bottomEnd = CornerSize(0.dp),
        bottomStart = CornerSize(0.dp)
    )
    val previewKeyColor = if (isDark) darkKeyColor else lightKeyColor
    val previewKeyShape = ContinuousRoundedRectangle(keyCornerRadius.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 20.dp, end = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = previewMinHeight)
                    .weTypePreviewBloom(
                        color = color,
                        cornerRadius = previewCorner,
                        edgeHighlightEnabled = edgeHighlightEnabled && !hyperMaterialEnabled,
                        edgeHighlightIntensity = edgeHighlightIntensity,
                        isDark = isDark
                    )
                    .clip(previewShape)
            ) {
                if (hyperMaterialEnabled) {
                    HyperMaterialPreview(
                        overrides = glassOverrides,
                        tintColor = color,
                        isDark = isDark,
                        cornerRadius = cornerRadius,
                        modifier = Modifier.matchParentSize()
                    )
                } else {
                    Image(
                        painter = painterResource(R.drawable.natural_texture_004),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .blur((blurRadius / 3f).coerceAtLeast(0f).dp)
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(ComposeColor(color))
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isDark) stringResource(R.string.settings_preview_mode_dark) else stringResource(R.string.settings_preview_mode_light),
                            color = previewTextColor(displayColor).copy(alpha = 0.7f),
                            style = MiuixTheme.textStyles.body2
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(previewKeyShape)
                                    .background(ComposeColor(previewKeyColor))
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (hyperMaterialEnabled) stringResource(R.string.settings_hyper_material_preview) else formatArgb(color),
                                    color = previewTextColor(displayColor),
                                    style = MiuixTheme.textStyles.headline1,
                                    fontFamily = weTypeFontFamily
                                )
                            }

                            for (i in 'A'..'C') {
                                Box(
                                    modifier = Modifier
                                        .clip(previewKeyShape)
                                        .background(ComposeColor(previewKeyColor))
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = i.toString(),
                                        color = previewTextColor(displayColor),
                                        style = MiuixTheme.textStyles.headline1,
                                        fontFamily = weTypeFontFamily
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (hyperMaterialEnabled) {
                                "${stringResource(R.string.settings_corner_label)} $cornerRadius"
                            } else {
                                "${stringResource(R.string.settings_blur_label)} $blurRadius · ${stringResource(R.string.settings_corner_label)} $cornerRadius"
                            },
                            color = previewTextColor(displayColor).copy(alpha = 0.7f),
                            style = MiuixTheme.textStyles.body2
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun GlassOverrideEditor(
    values: List<String>,
    enabled: Boolean,
    onValueChange: (Int, String) -> Unit,
    onReset: () -> Unit
) {
    fun read(field: GlassOverrideField) = runCatching {
        GlassMaterialOverrides.parse(mapOf(field to values[field.ordinal]))
    }.getOrNull()
    val glass = read(GlassOverrideField.GLASS)?.glass
    val radii = read(GlassOverrideField.BLUR_RADII)?.blurRadii ?: GlassSliderParameter.startingBlurRadii()
    val bloom = read(GlassOverrideField.BLOOM)?.bloom ?: GlassSliderParameter.startingBloom()
    val valid = GlassOverrideField.entries.all { read(it) != null }
    MiuixSwitchWidget(
        title = stringResource(R.string.settings_glass_custom),
        description = stringResource(
            if (enabled) R.string.settings_glass_custom_desc else R.string.settings_glass_unavailable
        ),
        checked = glass != null,
        enabled = enabled && valid,
        onCheckedChange = { checked ->
            val current = GlassMaterialOverrides(glass, radii, bloom)
            val updated = current.withGlassEnabled(checked)
            GlassOverrideField.entries.forEach { onValueChange(it.ordinal, updated.text(it)) }
        }
    )
    if (glass == null && valid) return
    HorizontalDivider()
    if (glass != null) {
        val labels = mapOf(
            GlassSliderParameter.LIGHT_ANGLE to R.string.settings_glass_light_angle,
            GlassSliderParameter.LIGHT_INTENSITY to R.string.settings_glass_light_intensity,
            GlassSliderParameter.REFRACTION to R.string.settings_glass_refraction,
            GlassSliderParameter.DEPTH to R.string.settings_glass_depth,
            GlassSliderParameter.SPLAY to R.string.settings_glass_splay,
            GlassSliderParameter.THICKNESS to R.string.settings_glass_thickness,
            GlassSliderParameter.EDGE_CURVE to R.string.settings_glass_edge_curve
        )
        @Composable
        fun ParameterSlider(parameter: GlassSliderParameter, bloomMode: Boolean = false) {
            val label = stringResource(
                if (!bloomMode && parameter == GlassSliderParameter.LIGHT_INTENSITY) R.string.settings_glass_edge_lighten
                else if (!bloomMode && parameter == GlassSliderParameter.SPLAY) R.string.settings_glass_lighten_angle
                else labels.getValue(parameter)
            )
            SliderPreferenceItem(
                title = if (bloomMode) stringResource(R.string.settings_glass_highlight_parameter, label) else label,
                value = if (bloomMode) parameter.readBloom(bloom) else parameter.read(glass),
                range = parameter.range,
                step = parameter.step,
                enabled = enabled && valid,
                format = { value ->
                    when (parameter) {
                        GlassSliderParameter.LIGHT_ANGLE, GlassSliderParameter.SPLAY -> "${value.roundToInt()}°"
                        GlassSliderParameter.LIGHT_INTENSITY -> "${value.roundToInt()}%"
                        GlassSliderParameter.DEPTH, GlassSliderParameter.THICKNESS -> "${value.roundToInt()} px"
                        else -> String.format(java.util.Locale.ROOT, "%.2f", value)
                    }
                },
                onValueChange = {
                    val field = if (bloomMode) GlassOverrideField.BLOOM else GlassOverrideField.GLASS
                    val updated = if (bloomMode) parameter.writeBloom(bloom, it) else parameter.write(glass, it)
                    onValueChange(field.ordinal, updated.joinToString(", "))
                }
            )
        }
        GlassSliderParameter.entries.forEach { ParameterSlider(it) }
        val unsupported = stringResource(R.string.settings_glass_unsupported)
        SliderPreferenceItem(
            title = stringResource(R.string.settings_glass_dispersion),
            value = 0f, range = 0f..100f, step = 1f,
            enabled = false, format = { unsupported }, onValueChange = {}
        )
        listOf(R.string.settings_glass_frost_small, R.string.settings_glass_frost_large).forEachIndexed { index, title ->
            SliderPreferenceItem(
                title = stringResource(title), value = radii[index].toFloat(),
                range = 0f..400f, step = 1f, enabled = enabled && valid,
                format = { "${it.roundToInt()} px" },
                onValueChange = { value ->
                    val updated = radii.toMutableList().apply { this[index] = value.roundToInt() }
                    onValueChange(GlassOverrideField.BLUR_RADII.ordinal, updated.joinToString(", "))
                }
            )
        }
        GlassSliderParameter.entries.filter { it.bloomIndex != null }.forEach {
            ParameterSlider(it, bloomMode = true)
        }
    }
    HorizontalDivider()
    var rawExpanded by rememberSaveable { mutableStateOf(false) }
    val showRaw = rawExpanded || !valid
    val arrowRotation by animateFloatAsState(
        targetValue = if (showRaw) 90f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "GlassRawArrowRotation"
    )
    BasicComponent(
        title = stringResource(R.string.settings_glass_raw),
        summary = stringResource(if (showRaw) R.string.settings_glass_collapse else R.string.settings_glass_expand),
        onClick = { rawExpanded = !rawExpanded },
        endActions = {
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.size(width = 10.dp, height = 16.dp).rotate(arrowRotation)
            )
        }
    )
    if (showRaw) GlassRawOverrideEditor(values, enabled, onValueChange)
    ArrowPreference(
        title = stringResource(R.string.settings_glass_reset),
        summary = stringResource(R.string.settings_glass_reset_desc),
        onClick = onReset
    )
}

@Composable
private fun GlassRawOverrideEditor(
    values: List<String>,
    enabled: Boolean,
    onValueChange: (Int, String) -> Unit
) {
    val titles = listOf(R.string.settings_glass_params, R.string.settings_glass_blur,
        R.string.settings_glass_bloom, R.string.settings_glass_type)
    val descriptions = listOf(R.string.settings_glass_params_desc, R.string.settings_glass_blur_desc,
        R.string.settings_glass_bloom_desc, R.string.settings_glass_type_desc)
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = stringResource(R.string.settings_glass_raw_summary),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        GlassOverrideField.entries.filter { it != GlassOverrideField.MATERIAL_TYPE }.forEach { field ->
            val index = field.ordinal
            val valid = GlassMaterialOverrides.isValid(field, values[index])
            Column(Modifier.fillMaxWidth().padding(top = 16.dp).alpha(if (enabled) 1f else 0.38f)) {
                Text(stringResource(titles[index]), style = MiuixTheme.textStyles.main)
                Text(stringResource(descriptions[index]), style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = values[index],
                    enabled = enabled,
                    onValueChange = { if (it.length <= 4096) onValueChange(index, it) },
                    label = stringResource(R.string.settings_glass_default),
                    singleLine = field == GlassOverrideField.MATERIAL_TYPE || field == GlassOverrideField.BLUR_RADII,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!valid) {
                    Text(stringResource(R.string.settings_glass_invalid), color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.body2)
                }
            }
        }
    }

}


@Composable
private fun HyperMaterialPreview(
    overrides: GlassMaterialOverrides,
    tintColor: Int,
    isDark: Boolean,
    cornerRadius: Int,
    modifier: Modifier
) {
    key(overrides) {
        AndroidView(
            modifier = modifier,
            factory = { context -> HyperMaterialPreviewLayout(context, overrides) },
            update = { it.updateStyle(isDark, cornerRadius, tintColor) },
            onRelease = { it.releaseMaterial() }
        )
    }
}

private class HyperMaterialPreviewLayout(context: Context, overrides: GlassMaterialOverrides) : FrameLayout(context) {
    private val backdrop = ImageView(context).apply {
        setImageResource(R.drawable.natural_texture_004)
        scaleType = ImageView.ScaleType.CENTER_CROP
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val panel = FrameLayout(context)
    private val material = WeTypeHyperMaterial(panel, overrides, sampleBehindWindow = false)
    private var isDark = false
    private var cornerRadius = 0
    private var tintColor = Color.TRANSPARENT
    private var outlineGeometry: Triple<Int, Int, WeTypeCornerRadii>? = null
    private var outlinePath: Path? = null

    init {
        clipChildren = false
        clipToPadding = false
        // Native siblings give MIUI a local sampling source before the material RenderNode.
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panel.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outlinePath?.let(outline::setPath)
            }
        }
        panel.clipToOutline = true
    }

    fun updateStyle(isDark: Boolean, cornerRadius: Int, tintColor: Int) {
        this.isDark = isDark
        this.cornerRadius = cornerRadius
        this.tintColor = tintColor
        renderMaterial()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderMaterial()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // The expanded effect sibling must never determine this preview's size.
        backdrop.layout(0, 0, width, height)
        panel.layout(0, 0, width, height)
        renderMaterial()
    }

    private fun renderMaterial() {
        if (!isAttachedToWindow || width <= 0 || height <= 0) return
        val radius = cornerRadius * resources.displayMetrics.density
        val radii = WeTypeCornerRadii(radius, radius, 0f, 0f)
        val geometry = Triple(width, height, radii)
        if (outlineGeometry != geometry) {
            outlinePath = createWeTypeContinuousRoundedPath(width.toFloat(), height.toFloat(), radii)
            outlineGeometry = geometry
            panel.invalidateOutline()
        }
        if (material.apply(isDark, tintColor)) material.updateGeometry(radii)
        else panel.setBackgroundColor(WeTypeHyperMaterial.fallbackColor(isDark))
    }

    fun releaseMaterial() = material.clear()

    override fun onDetachedFromWindow() {
        material.clear()
        super.onDetachedFromWindow()
    }
}


@Composable
private fun Modifier.weTypePreviewBloom(
    color: Int,
    cornerRadius: androidx.compose.ui.unit.Dp,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    isDark: Boolean
): Modifier {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val previewContext = remember(context, configuration, isDark) {
        createPreviewContext(context, isDark)
    }
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }
    val previewCornerRadii = remember(cornerRadiusPx) {
        WeTypeCornerRadii(cornerRadiusPx, cornerRadiusPx, 0f, 0f)
    }
    // Color/intensity changes reuse the expensive shadow paths and blur filters.
    val bloomDrawable = remember(previewContext, previewCornerRadii) {
        WeTypeBloomStrokeDrawable(previewContext, previewCornerRadii, color, edgeHighlightIntensity / 100f)
    }
    val bloomModifier = remember(
        bloomDrawable, color, edgeHighlightEnabled, edgeHighlightIntensity
    ) {
        Modifier.drawWithCache {
            val widthPx = size.width.roundToInt()
            val heightPx = size.height.roundToInt()
            // The bloom overlay relies on clipPath + BlurMaskFilter + Path.op, which are not reliably
            // supported on Compose's hardware-accelerated recording canvas and crash the preview. Render
            // it once into an offscreen software bitmap (which supports every operation) and blit the
            // result, keeping the preview pixel-accurate.
            val overlayBitmap = if (edgeHighlightEnabled && widthPx > 0 && heightPx > 0) {
                runCatching {
                    bloomDrawable.updateStyle(color, edgeHighlightIntensity / 100f)
                    bloomDrawable.setBounds(0, 0, widthPx, heightPx)
                    val clipPath = createWeTypeContinuousRoundedPath(
                        width = widthPx.toFloat(),
                        height = heightPx.toFloat(),
                        cornerRadii = previewCornerRadii
                    )
                    Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { bitmap ->
                        val bitmapCanvas = Canvas(bitmap)
                        bitmapCanvas.clipPath(clipPath)
                        bloomDrawable.draw(bitmapCanvas)
                    }
                }.getOrNull()
            } else {
                null
            }

            onDrawWithContent {
                drawContent()
                val bitmap = overlayBitmap ?: return@onDrawWithContent
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawBitmap(bitmap, 0f, 0f, null)
                }
            }
        }
    }
    return this.then(bloomModifier)
}

private fun createPreviewContext(baseContext: Context, isDark: Boolean): Context {
    val configuration = Configuration(baseContext.resources.configuration).apply {
        uiMode =
            (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    return baseContext.createConfigurationContext(configuration)
}

@Composable
private fun IconShapePreferenceItem(
    value: String,
    onValueChange: (String) -> Unit,
    onReset: () -> Unit
) {
    val context = LocalContext.current
    // 设置界面也会以 ComponentDialog 形式渲染在宿主进程中，此时没有 ActivityResultRegistryOwner，
    // 文件选择改由宿主 Activity 发起，结果经 HostActivityResultBridge 回传。
    val registryOwner = LocalActivityResultRegistryOwner.current
    val hostActivity = remember(context) { context.findHostActivity() }

    fun applyPickedSvg(uri: Uri?) {
        val text = uri?.let { picked ->
            runCatching {
                context.contentResolver.openInputStream(picked)
                    ?.use { stream -> stream.readBytes().decodeToString() }
            }.getOrNull()
        }
        val paths = text?.let(SvgPathImporter::extractPathData).orEmpty()
        if (paths.isEmpty()) {
            Toast.makeText(
                context,
                R.string.settings_icon_shape_import_empty,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        onValueChange(paths.joinToString("\n"))
        Toast.makeText(
            context,
            context.getString(R.string.settings_icon_shape_imported, paths.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    val svgPicker = registryOwner?.let {
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            applyPickedSvg(uri)
        }
    }
    val hostSvgLauncher: (() -> Unit)? = if (svgPicker == null && hostActivity != null) {
        {
            val requestCode = HostActivityResultBridge.register { resultCode, uri ->
                if (resultCode == Activity.RESULT_OK) applyPickedSvg(uri)
            }
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            runCatching { hostActivity.startActivityForResult(intent, requestCode) }
                .onFailure { error ->
                    HostActivityResultBridge.dispatch(requestCode, Activity.RESULT_CANCELED, null)
                    Toast.makeText(
                        context,
                        error.message ?: error.javaClass.simpleName,
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }
    } else {
        null
    }
    val launchSvgPicker: (() -> Unit)? = when {
        svgPicker != null -> ({ svgPicker.launch(arrayOf("image/svg+xml", "image/*")) })
        hostSvgLauncher != null -> hostSvgLauncher
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_icon_shape_title),
            style = MiuixTheme.textStyles.main
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.settings_icon_shape_desc),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = stringResource(R.string.settings_icon_shape_label),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (launchSvgPicker != null) {
            BasicComponent(
                title = stringResource(R.string.settings_icon_shape_import),
                titleColor = BasicComponentDefaults.titleColor(
                    color = MiuixTheme.colorScheme.primary
                ),
                onClick = launchSvgPicker
            )
        }
        BasicComponent(
            title = stringResource(R.string.settings_icon_shape_reset),
            titleColor = BasicComponentDefaults.titleColor(
                color = MiuixTheme.colorScheme.primary
            ),
            onClick = onReset
        )
    }
}

@Composable
private fun AppearanceColorGroupEditor(
    title: String,
    summary: String,
    color: Int,
    onColorChange: (Int) -> Unit
) {
    var input by rememberSaveable(title) { mutableStateOf(formatArgb(color)) }

    LaunchedEffect(color) {
        val formatted = formatArgb(color)
        if (!input.equals(formatted, ignoreCase = true) && parseHexColor(input) != color) {
            input = formatted
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(32.dp)
                    .clip(ContinuousRoundedRectangle(999.dp))
                    .background(ComposeColor(color))
                    .border(1.dp, MiuixTheme.colorScheme.outline, ContinuousRoundedRectangle(999.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.main
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextField(
            value = input,
            onValueChange = { raw ->
                val sanitized = sanitizeHexColorInput(raw) ?: return@TextField
                input = sanitized
                parseHexColor(sanitized)?.let(onColorChange)
            },
            label = stringResource(R.string.settings_appearance_color_input_label),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun KeyColorEditor(
    title: String,
    summary: String,
    color: Int,
    onColorChange: (Int) -> Unit
) {
    var input by rememberSaveable(title) { mutableStateOf(formatRgb(color)) }

    LaunchedEffect(color) {
        val formatted = formatRgb(color)
        val normalizedColor = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        if (!input.equals(formatted, ignoreCase = true) && parseRgbColor(input) != normalizedColor) {
            input = formatted
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.main
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = summary,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2
            )
        }
        // Per-mode key opacity, baked directly into the key color's alpha channel (same logic as
        // the background opacity slider above).
        SliderPreferenceItem(
            title = stringResource(R.string.settings_key_opacity_title),
            value = Color.alpha(color),
            max = 255,
            onValueChange = { alpha ->
                onColorChange((alpha shl 24) or (color and 0xFFFFFF))
            }
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            TextField(
                value = input,
                onValueChange = { raw ->
                    val sanitized = sanitizeRgbColorInput(raw) ?: return@TextField
                    input = sanitized
                    parseRgbColor(sanitized)?.let { rgb ->
                        onColorChange((color and 0xFF000000.toInt()) or (rgb and 0xFFFFFF))
                    }
                },
                label = stringResource(R.string.settings_key_color_input_label),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun NumericTextSettingItem(
    title: String,
    summary: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.main
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = summary,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = "DP"
        )
    }
}

@Composable
private fun SliderPreferenceItem(
    title: String,
    value: Int,
    max: Int,
    enabled: Boolean = true,
    onValueChange: (Int) -> Unit
) = SliderPreferenceItem(
    title = title,
    value = value.toFloat(),
    range = 0f..max.toFloat(),
    step = 1f,
    enabled = enabled,
    format = { it.roundToInt().toString() },
    onValueChange = { onValueChange(it.roundToInt()) }
)

@Composable
private fun SliderPreferenceItem(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    enabled: Boolean = true,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.main,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = format(value),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.main
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            enabled = enabled,
            value = value.coerceIn(range),
            onValueChange = { onValueChange(((it / step).roundToInt() * step).coerceIn(range)) },
            valueRange = range,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = title }
        )
    }
}

@Composable
private fun MiuixSwitchWidget(
    title: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val toggleAction = {
        if (enabled) onCheckedChange(!checked)
    }

    BasicComponent(
        title = title,
        modifier = Modifier.alpha(if (enabled) 1f else 0.38f),
        enabled = enabled,
        summary = description,
        onClick = toggleAction,
        endActions = {
            Switch(
                enabled = enabled,
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

private fun previewTextColor(color: Int): ComposeColor =
    if (isLightColor(color)) ComposeColor.Black else ComposeColor.White

private fun formatRgb(color: Int): String = String.format("#%06X", color and 0xFFFFFF)

private fun formatArgb(color: Int): String = String.format("#%08X", color)

private fun sanitizeHexColorInput(input: String): String? {
    val trimmed = input.trim()
    val hasPrefix = trimmed.startsWith("#")
    val body = trimmed.removePrefix("#")
    if (body.length > 8 || !body.matches(Regex("^[0-9a-fA-F]*$"))) {
        return null
    }
    return if (hasPrefix || body.isNotEmpty()) "#$body" else ""
}

private fun sanitizeRgbColorInput(input: String): String? {
    val trimmed = input.trim()
    val hasPrefix = trimmed.startsWith("#")
    val body = trimmed.removePrefix("#")
    if (body.length > 6 || !body.matches(Regex("^[0-9a-fA-F]*$"))) {
        return null
    }
    return if (hasPrefix || body.isNotEmpty()) "#$body" else ""
}

private fun parseHexColor(input: String): Int? {
    val body = input.trim().removePrefix("#")
    return when (body.length) {
        6, 8 -> runCatching { Color.parseColor("#$body") }.getOrNull()
        else -> null
    }
}

private fun parseRgbColor(input: String): Int? {
    val body = input.trim().removePrefix("#")
    if (body.length != 6) return null
    return runCatching { Color.parseColor("#$body") }.getOrNull()
}

private fun sanitizeIntegerInput(input: String, maxLength: Int): String? {
    val trimmed = input.trim()
    if (trimmed.length > maxLength) return null
    if (trimmed.isNotEmpty() && !trimmed.matches(Regex("^\\d*$"))) return null
    return trimmed
}

private fun isLightColor(color: Int): Boolean {
    val luminance =
        (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114) / 255
    return luminance > 0.5
}

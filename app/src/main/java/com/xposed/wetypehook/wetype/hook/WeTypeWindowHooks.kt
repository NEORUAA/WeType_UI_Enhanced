package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.graphics.drawable.toDrawable
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.HookEnvironment
import com.xposed.wetypehook.xposed.findMethodInHierarchy
import com.xposed.wetypehook.xposed.getObjectAs
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.invokeMethodAs
import com.xposed.wetypehook.xposed.loadClassOrNull
import com.xposed.wetypehook.wetype.graphics.WeTypeHyperMaterial
import com.xposed.wetypehook.wetype.graphics.WeTypeBloomStrokeDrawable
import com.xposed.wetypehook.wetype.graphics.WeTypeCornerRadii
import com.xposed.wetypehook.wetype.graphics.createWeTypeContinuousRoundedPath
import com.xposed.wetypehook.wetype.settings.GlassMaterialOverrides
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val WETYPE_COLLAPSED_IME_HEIGHT_THRESHOLD_PX = 2
private const val WETYPE_FLOATING_VIEW_CLASS_PREFIX = "com.tencent.wetype.plugin.hld.float."
private const val WETYPE_HARDWARE_VIEW_CLASS_PREFIX = "com.tencent.wetype.plugin.hld.hardware."
private val WETYPE_HARDWARE_VIEW_ID_NAMES = arrayOf(
    "hardware_keyboard_candidate_container_view",
    "hardware_keyboard_pending_container_view",
    "hardware_keyboard_alternative_container_view",
    "hardware_keyboard_candidate_recyclerview",
    "hardware_keyboard_candidate_right_container"
)

internal object WeTypeWindowHooks {
    private data class BackgroundStyle(
        val color: Int,
        val blurRadius: Int,
        val edgeHighlightEnabled: Boolean,
        val edgeHighlightIntensity: Int,
        val cornerRadii: WeTypeCornerRadii,
        val nightMode: Int,
        val density: Float,
        val hyperMaterialEnabled: Boolean,
        val hyperMaterialAvailable: Boolean
    )

    private class ContinuousCornerOutline(val cornerRadii: WeTypeCornerRadii) : ViewOutlineProvider() {
        private var cachedWidth = 0
        private var cachedHeight = 0
        private var cachedPath: Path? = null

        override fun getOutline(target: View, outline: Outline) {
            val width = target.width
            val height = target.height
            if (width <= 0 || height <= 0) return
            if (cachedPath == null || width != cachedWidth || height != cachedHeight) {
                cachedPath = createWeTypeContinuousRoundedPath(width.toFloat(), height.toFloat(), cornerRadii)
                cachedWidth = width
                cachedHeight = height
            }
            runCatching { outline.setPath(checkNotNull(cachedPath)) }.onFailure {
                outline.setRoundRect(0, 0, width, height, cornerRadii.maxRadius())
            }
        }
    }

    private data class WeTypeWindowState(
        var windowVisible: Boolean = false,
        var backgroundCarrier: View? = null,
        var carrierOverrides: GlassMaterialOverrides = GlassMaterialOverrides(),
        var hyperMaterial: WeTypeHyperMaterial? = null,
        var stopMaterialObserver: (() -> Unit)? = null,
        var window: WeakReference<Window>? = null,
        var resourceReconcilePending: Boolean = false,
        var backgroundDecorView: WeakReference<View>? = null,
        var backgroundObserver: WeakReference<ViewTreeObserver>? = null,
        var backgroundLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null,
        var backgroundPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null,
        var backgroundUpdatePending: Boolean = false,
        var backgroundStyleDirty: Boolean = true,
        var backgroundStyle: BackgroundStyle? = null,
        var backgroundViewRoot: Any? = null,
        val backgroundBounds: Rect = Rect(),
        var awaitingBackgroundTexture: Boolean = false,
        var backgroundTextureWaiter: BackgroundTextureWaiter? = null,
        var startupFill: ColorDrawable? = null,
        var transparentWindowBackground: Drawable? = null,
        val locationBuffer: IntArray = IntArray(2),
        var inputViewMethodResolved: Boolean = false,
        var inputViewMethod: Method? = null,
        var computedVisibleImeHeightPx: Int? = null,
        var bottomLeftHardwareCornerRadius: Float? = null,
        var bottomRightHardwareCornerRadius: Float? = null,
        var hardwareViewIds: IntArray? = null,
        var originalWindowStateCaptured: Boolean = false,
        var originalWindowBackground: Drawable? = null,
        var originalWindowBlurRadius: Int? = null
    )

    private class BackgroundTextureWaiter(
        state: WeTypeWindowState,
        viewRoot: Any,
        val listener: Any
    ) {
        val state = WeakReference(state)
        val viewRoot = WeakReference(viewRoot)
        val dispatchPending = AtomicBoolean(false)
        @Volatile var readyTexture: SurfaceTexture? = null
    }

    private val backgroundTextureWaiters = IdentityHashMap<Any, BackgroundTextureWaiter>()
    private val hookedTextureListeners = HashSet<Method>()
    @Volatile private var hasBackgroundTextureWaiters = false
    private val weTypeWindowStates = WeakHashMap<Any, WeTypeWindowState>()
    private val floatingBackgroundStates = WeakHashMap<ViewGroup, WeTypeWindowState>()
    private var miuiClipboardVisibilityProvider: (() -> Boolean)? = null

    fun setMiuiClipboardVisibilityProvider(provider: () -> Boolean) {
        miuiClipboardVisibilityProvider = provider
    }

    fun isMiuiClipboardActive(): Boolean = runCatching {
        miuiClipboardVisibilityProvider?.invoke() == true
    }.getOrDefault(false)

    fun prepareForHotReload(): Boolean {
        val states = synchronized(weTypeWindowStates) {
            weTypeWindowStates.values.toList()
        }
        val cleaned = runOnMainThreadBlocking {
            miuiClipboardVisibilityProvider = null
            WeTypeFloatingBackgrounds.clearAll()
            WeTypeOverlayCompositor.clearAll()
            states.forEach { state ->
                state.windowVisible = false
                removeBackgroundListeners(state)
                restoreWindowState(state)
                removeBackgroundCarrier(state)
            }
        }
        if (cleaned) {
            synchronized(weTypeWindowStates) {
                weTypeWindowStates.clear()
            }
        }
        return cleaned
    }

    fun hookTransparentOverlayUnderlay() {
        WeTypeOverlayCompositor.install()
    }

    fun reconcileCurrentOverlayUnderlays(rootViews: List<View>) {
        WeTypeOverlayCompositor.reconcile(rootViews)
        WeTypeFloatingBackgrounds.reconcile(rootViews)
    }

    fun hookWindowBlur() {
        WeTypeFloatingBackgrounds.install(::applyFloatingBackground, ::removeFloatingBackground)
        runCatching {
            val inputMethodService = loadClassOrNull("android.inputmethodservice.InputMethodService")
                ?: error("Failed to load InputMethodService")

            inputMethodService.getMethod(
                "onStartInputView",
                EditorInfo::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                onWindowStage(param.thisObject, "onStartInputView")
                reconcileCurrentResourceViews(param.thisObject)
            }
            runCatching {
                inputMethodService.getMethod("onWindowShown").hookAfter { param ->
                    onWindowStage(param.thisObject, "onWindowShown")
                    reconcileCurrentResourceViews(param.thisObject)
                }
            }
            runCatching {
                inputMethodService.getMethod("updateFullscreenMode").hookAfter { param ->
                    onWindowStage(param.thisObject, "updateFullscreenMode")
                }
            }
            // Observe the final host result, including its normal/floating/hardware proxy.
            val insetService = loadClassOrNull("com.tencent.wetype.plugin.hld.WxHldService")
                ?: inputMethodService
            insetService.getMethod(
                "onComputeInsets",
                InputMethodService.Insets::class.java
            ).hookAfter { param ->
                onComputeInsets(param.thisObject, param.argumentOrNull(0) as? InputMethodService.Insets)
            }
            runCatching {
                inputMethodService.getMethod("onWindowHidden").hookAfter { param ->
                    onWindowInactive(param.thisObject, removeCarrier = false)
                }
            }
            runCatching {
                inputMethodService.getMethod("hideWindow").hookAfter { param ->
                    onWindowInactive(param.thisObject, removeCarrier = false)
                }
            }
            runCatching {
                inputMethodService.getMethod("onDestroy").hookAfter { param ->
                    onWindowInactive(param.thisObject, removeCarrier = true)
                }
            }
            Log.i("Success: Hook WeType window blur")
        }.onFailure {
            Log.i("Failed: Hook WeType window blur")
            Log.i(it)
        }
    }

    fun reconcileCurrentInputMethodService(inputMethodService: InputMethodService) {
        // Hidden windows are initialized by their next onWindowShown callback.
        if (!inputMethodService.isInputViewShown) return
        val state = getWindowState(inputMethodService)
        state.windowVisible = true
        state.computedVisibleImeHeightPx = null
        scheduleWindowBlur(inputMethodService)
        reconcileCurrentResourceViews(inputMethodService)
    }

    private fun reconcileCurrentResourceViews(inputMethodService: Any) {
        val decorView = resolveInputMethodDecorView(inputMethodService) ?: return
        val state = getWindowState(inputMethodService)
        if (state.resourceReconcilePending) return
        state.resourceReconcilePending = true
        // Both lifecycle callbacks can run in the same turn. Reconcile once after the
        // host has finished installing its views; newly bound logos have their own hooks.
        if (!HookEnvironment.postTracked(decorView) {
                state.resourceReconcilePending = false
                if (state.windowVisible) {
                    WeTypeResourceHooks.reconcileCurrentKeyboardLogos(listOf(decorView))
                }
            }) {
            state.resourceReconcilePending = false
        }
    }

    private fun resolveInputMethodDecorView(inputMethodService: Any): View? = runCatching {
        (inputMethodService as? InputMethodService)?.window?.window?.decorView
    }.getOrNull()

    private fun onComputeInsets(inputMethodService: Any, insets: InputMethodService.Insets?) {
        runCatching {
            val state = getWindowState(inputMethodService)
            val window = (inputMethodService as? InputMethodService)?.window?.window ?: return@runCatching
            val rootHeight = window.decorView.rootView?.height?.takeIf { it > 0 }
                ?: window.decorView.height.takeIf { it > 0 }
                ?: return@runCatching
            insets ?: return@runCatching
            if (rootHeight - insets.visibleTopInsets <= WETYPE_COLLAPSED_IME_HEIGHT_THRESHOLD_PX &&
                state.windowVisible && isMiuiClipboardActive()
            ) {
                val inputFrame = readViewField(inputMethodService, "mInputFrame")
                if (inputFrame?.visibility == View.INVISIBLE && inputFrame.isAttachedToWindow &&
                    inputFrame.isLaidOut && inputFrame.height > 0
                ) {
                    // MIUI hides only the keys. Keep the visible and touchable IME area
                    // at its real frame so the system bottom bar does not collapse too.
                    inputFrame.getLocationInWindow(state.locationBuffer)
                    val top = state.locationBuffer[1]
                    if (top in 0 until rootHeight) {
                        insets.contentTopInsets = minOf(insets.contentTopInsets, top)
                        insets.visibleTopInsets = top
                    }
                }
            }
            val visibleImeHeight = (rootHeight - insets.visibleTopInsets).coerceAtLeast(0)
            val previousVisibleImeHeight = state.computedVisibleImeHeightPx
            state.computedVisibleImeHeightPx = visibleImeHeight

            if (!state.windowVisible) return@runCatching
            if (visibleImeHeight <= WETYPE_COLLAPSED_IME_HEIGHT_THRESHOLD_PX) {
                hideBackgroundCarrier(state)
                return@runCatching
            }
            if (visibleImeHeight == previousVisibleImeHeight) return@runCatching
            scheduleWindowBlur(inputMethodService, refreshStyle = false)
        }.onFailure {
            Log.i("Failed: Track WeType visible IME height")
            Log.i(it)
        }
    }

    private fun onWindowStage(inputMethodService: Any, stage: String) {
        runCatching {
            val state = getWindowState(inputMethodService)
            when (stage) {
                "onStartInputView" -> {
                    state.computedVisibleImeHeightPx = null
                }
                "onWindowShown" -> {
                    if (!state.windowVisible) prepareBackgroundShow(state)
                    state.windowVisible = true
                    state.computedVisibleImeHeightPx = null
                }
                "updateFullscreenMode" -> {
                    if (!state.windowVisible) return@runCatching
                }
            }

            scheduleWindowBlur(inputMethodService)
        }.onFailure {
            Log.i("Failed: Handle WeType window stage")
            Log.i(it)
        }
    }

    private fun prepareBackgroundShow(state: WeTypeWindowState) {
        cancelBackgroundTextureWait(state)
        state.awaitingBackgroundTexture = true
        val carrier = state.backgroundCarrier ?: return
        val style = state.backgroundStyle ?: return
        if (!style.hyperMaterialEnabled) return
        val viewRoot = runCatching { carrier.invokeMethodAs<Any>("getViewRootImpl") }.getOrNull() ?: return
        if (awaitBackgroundTexture(state, viewRoot)) applyStartupFill(state, style)
    }

    private fun awaitBackgroundTexture(state: WeTypeWindowState, viewRoot: Any): Boolean {
        if (state.backgroundTextureWaiter?.viewRoot?.get() === viewRoot) return true
        return runCatching {
            val listener = checkNotNull(viewRoot.getObjectAs<Any>("mUpdateListener"))
            val method = listener.javaClass.getMethod("onFrameAvailable", SurfaceTexture::class.java)
            if (method !in hookedTextureListeners) {
                HookEnvironment.registerHook(method, "wetype-background-texture") { chain ->
                    // The steady-state callback does not allocate or inspect any views.
                    if (!hasBackgroundTextureWaiters) return@registerHook chain.proceed()
                    val result = chain.proceed()
                    runCatching {
                        val waiter = synchronized(backgroundTextureWaiters) {
                            backgroundTextureWaiters[chain.thisObject]
                        } ?: return@runCatching
                        val root = waiter.viewRoot.get() ?: return@runCatching
                        val texture = chain.args.firstOrNull() as? SurfaceTexture ?: return@runCatching
                        // Reject a late callback from the texture destroyed by the previous hide.
                        if (texture.isReleased || root.getObjectAs<Any>("mSurTex") !== texture) return@runCatching
                        val owner = waiter.state.get() ?: return@runCatching
                        waiter.readyTexture = texture
                        if (!waiter.dispatchPending.compareAndSet(false, true)) return@runCatching
                        val decor = owner.backgroundDecorView?.get() ?: owner.backgroundCarrier
                        if (decor == null || !HookEnvironment.postTracked(decor) {
                            waiter.dispatchPending.set(false)
                            if (owner.backgroundTextureWaiter !== waiter) return@postTracked
                            val ready = waiter.readyTexture ?: return@postTracked
                            // Material changes may replace the native texture before this task runs.
                            if (ready.isReleased || root.getObjectAs<Any>("mSurTex") !== ready) return@postTracked
                            synchronized(backgroundTextureWaiters) {
                                if (backgroundTextureWaiters[waiter.listener] !== waiter) return@postTracked
                                backgroundTextureWaiters.remove(waiter.listener)
                                hasBackgroundTextureWaiters = backgroundTextureWaiters.isNotEmpty()
                            }
                            owner.backgroundTextureWaiter = null
                            owner.awaitingBackgroundTexture = false
                            clearStartupFill(owner)
                        }) waiter.dispatchPending.set(false)
                    }.onFailure { Log.e(it) }
                    result
                }
                hookedTextureListeners.add(method)
            }
            state.backgroundTextureWaiter?.let { old ->
                synchronized(backgroundTextureWaiters) {
                    if (backgroundTextureWaiters[old.listener] === old) backgroundTextureWaiters.remove(old.listener)
                }
            }
            val waiter = BackgroundTextureWaiter(state, viewRoot, listener)
            state.backgroundTextureWaiter = waiter
            synchronized(backgroundTextureWaiters) {
                backgroundTextureWaiters[listener] = waiter
                hasBackgroundTextureWaiters = true
            }
            true
        }.getOrElse {
            cancelBackgroundTextureWait(state)
            Log.i("Failed: Observe WeType background texture")
            Log.e(it)
            false
        }
    }

    private fun applyStartupFill(state: WeTypeWindowState, style: BackgroundStyle) {
        if (!state.awaitingBackgroundTexture || state.backgroundTextureWaiter == null) return
        val carrier = state.backgroundCarrier ?: return
        val fill = state.startupFill ?: return
        if (carrier.foreground !== fill) return
        val color = if (state.carrierOverrides.glass != null) style.color else {
            WeTypeHyperMaterial.fallbackColor(style.nightMode == Configuration.UI_MODE_NIGHT_YES)
        }
        if (fill.color != color) fill.color = color
    }

    private fun clearStartupFill(state: WeTypeWindowState) {
        state.startupFill?.takeIf { it.color != Color.TRANSPARENT }?.color = Color.TRANSPARENT
    }

    private fun cancelBackgroundTextureWait(state: WeTypeWindowState) {
        state.backgroundTextureWaiter?.let { waiter ->
            synchronized(backgroundTextureWaiters) {
                if (backgroundTextureWaiters[waiter.listener] === waiter) backgroundTextureWaiters.remove(waiter.listener)
                hasBackgroundTextureWaiters = backgroundTextureWaiters.isNotEmpty()
            }
        }
        state.backgroundTextureWaiter = null
        state.awaitingBackgroundTexture = false
        clearStartupFill(state)
    }

    private fun scheduleWindowBlur(inputMethodService: Any, refreshStyle: Boolean = true) {
        val state = getWindowState(inputMethodService)
        if (!state.windowVisible) return
        val window = (inputMethodService as? InputMethodService)?.window?.window ?: return
        val decorView = window.decorView
        val observer = decorView.viewTreeObserver
        if (!observer.isAlive) return
        state.window = WeakReference(window)
        if (state.stopMaterialObserver == null) {
            val serviceReference = WeakReference(inputMethodService)
            state.stopMaterialObserver = WeTypeHyperMaterial.observeAvailability(decorView.context) {
                serviceReference.get()?.let { scheduleWindowBlur(it) }
            }
        }
        val updateAlreadyPending = state.backgroundUpdatePending
        state.backgroundUpdatePending = true
        state.backgroundStyleDirty = state.backgroundStyleDirty || refreshStyle
        if (state.backgroundObserver?.get() === observer) {
            if (!updateAlreadyPending && refreshStyle) decorView.invalidate()
            return
        }
        removeBackgroundListeners(state)
        state.backgroundUpdatePending = true

        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            state.backgroundUpdatePending = true
        }
        val serviceReference = WeakReference(inputMethodService)
        val preDrawListener = ViewTreeObserver.OnPreDrawListener {
            if (!state.backgroundUpdatePending) {
                true
            } else {
                // A new layout/inset/lifecycle event will re-arm this work. An invalid
                // snapshot must not turn an unrelated animation into a per-frame retry loop.
                state.backgroundUpdatePending = false
                runCatching {
                    if (!state.windowVisible) {
                        restoreInactiveBackgroundGeometry(state)
                        return@runCatching true
                    }
                    val service = serviceReference.get() as? InputMethodService ?: return@runCatching true
                    val context: Context = service
                    val window = service.window?.window ?: return@runCatching true
                    val latestDecorView = window.decorView
                    if (shouldHideBackground(latestDecorView, state)) {
                        hideBackgroundCarrier(state)
                        return@runCatching true
                    }
                    when (val update = collectBackgroundBounds(service, latestDecorView, state)) {
                        WeTypeBackgroundUpdate.PendingLayout -> Unit
                        WeTypeBackgroundUpdate.Hidden -> hideBackgroundCarrier(state)
                        is WeTypeBackgroundUpdate.Ready ->
                            applyBackgroundCarrier(window, latestDecorView, context, state, update.bounds)
                    }
                    true
                }.getOrElse {
                    Log.i("Failed: Apply WeType background before drawing")
                    Log.i(it)
                    true
                }
            }
        }
        state.backgroundDecorView = WeakReference(decorView)
        state.backgroundObserver = WeakReference(observer)
        state.backgroundLayoutListener = layoutListener
        state.backgroundPreDrawListener = preDrawListener
        observer.addOnGlobalLayoutListener(layoutListener)
        observer.addOnPreDrawListener(preDrawListener)
        decorView.invalidate()
    }

    private fun removeBackgroundListeners(state: WeTypeWindowState) {
        // Attaching a window can merge its floating observer into a new live observer.
        listOfNotNull(
            state.backgroundObserver?.get(),
            state.backgroundDecorView?.get()?.viewTreeObserver
        ).distinct().filter { it.isAlive }.forEach { observer ->
            state.backgroundLayoutListener?.let(observer::removeOnGlobalLayoutListener)
            state.backgroundPreDrawListener?.let(observer::removeOnPreDrawListener)
        }
        state.backgroundDecorView = null
        state.backgroundObserver = null
        state.backgroundLayoutListener = null
        state.backgroundPreDrawListener = null
        state.backgroundUpdatePending = false
    }

    private fun getWindowState(inputMethodService: Any): WeTypeWindowState =
        synchronized(weTypeWindowStates) {
            weTypeWindowStates.getOrPut(inputMethodService) { WeTypeWindowState() }
        }

    private fun onWindowInactive(inputMethodService: Any, removeCarrier: Boolean) {
        runCatching {
            val state = getWindowState(inputMethodService)
            if (!state.windowVisible && !removeCarrier) return@runCatching
            state.windowVisible = false
            state.stopMaterialObserver?.invoke()
            state.stopMaterialObserver = null
            state.computedVisibleImeHeightPx = null
            // These callbacks precede the system's IME exit animation. Keep the
            // submitted background with the keys until their window leaves the screen.
            // A final framework relayout can reset our zero-height decorative children.
            // Repair geometry only on layout events, without polling or material reinitialization.
            state.backgroundUpdatePending = true
            if (removeCarrier) {
                removeBackgroundListeners(state)
                removeBackgroundCarrier(state)
                synchronized(weTypeWindowStates) {
                    weTypeWindowStates.remove(inputMethodService)
                }
            }
        }.onFailure {
            Log.i("Failed: Cleanup WeType window background")
            Log.i(it)
        }
    }

    private fun resolveCornerRadii(targetView: View, context: Context, state: WeTypeWindowState, cornerRadiusDp: Int): WeTypeCornerRadii {
        val topRadius = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            cornerRadiusDp.toFloat(),
            context.resources.displayMetrics
        )
        val insets = targetView.rootWindowInsets
        if (insets != null) {
            state.bottomLeftHardwareCornerRadius = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius?.toFloat()
            state.bottomRightHardwareCornerRadius = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius?.toFloat()
        }
        return WeTypeCornerRadii(
            topLeft = topRadius,
            topRight = topRadius,
            bottomRight = state.bottomRightHardwareCornerRadius ?: topRadius,
            bottomLeft = state.bottomLeftHardwareCornerRadius ?: topRadius
        )
    }

    private fun collectBackgroundBounds(
        inputMethodService: Any,
        decorView: View,
        state: WeTypeWindowState
    ): WeTypeBackgroundUpdate {
        if (!state.inputViewMethodResolved) {
            // Current WeType versions expose only the framework input/candidate frames.
            // Cache a missing optional accessor too, instead of throwing on every layout.
            state.inputViewMethod = runCatching {
                inputMethodService.javaClass.findMethodInHierarchy {
                    name == "getInputView" && parameterCount == 0 && View::class.java.isAssignableFrom(returnType)
                }
            }.getOrNull()
            state.inputViewMethodResolved = true
        }
        val location = state.locationBuffer
        val inputFrame = readViewField(inputMethodService, "mInputFrame")
        val clipboardShowing = isMiuiClipboardActive()
        val contentViews = listOfNotNull(
            readViewField(inputMethodService, "mCandidatesFrame"),
            inputFrame,
            runCatching { state.inputViewMethod?.invoke(inputMethodService) as? View }.getOrNull()
        )
        return resolveWeTypeBackgroundUpdate(
            decorView.toBackgroundLayout(location),
            contentViews.map { view ->
                // MIUI hides the keys while its transparent clipboard popup is open.
                // Keep their frame geometry so the separate material carrier stays visible.
                view.toBackgroundLayout(location).let { layout ->
                    if (clipboardShowing && view === inputFrame && view.visibility == View.INVISIBLE) {
                        layout.copy(isShown = true)
                    } else layout
                }
            }
        )
    }

    private fun readViewField(inputMethodService: Any, fieldName: String): View? =
        runCatching { inputMethodService.getObjectAs<View>(fieldName) }.getOrNull()

    private fun View.toBackgroundLayout(location: IntArray): WeTypeBackgroundLayout {
        getLocationInWindow(location)
        return WeTypeBackgroundLayout(
            windowTop = location[1],
            height = height,
            isShown = isShown,
            isLaidOut = isAttachedToWindow && isLaidOut,
            isLayoutRequested = isLayoutRequested
        )
    }

    private fun applyBackgroundCarrier(
        window: Window,
        decorView: View,
        context: Context,
        state: WeTypeWindowState,
        bounds: WeTypeBackgroundBounds
    ) {
        val decorGroup = decorView as? ViewGroup ?: return
        val backgroundHeight = bounds.height
        val settings = WeTypeSettings.readSnapshotXposed(context.resources)
        val cornerRadii = resolveCornerRadii(decorView, context, state, settings.cornerRadius)
        if (backgroundHeight < cornerRadii.maxRadius()) {
            hideBackgroundCarrier(state)
            return
        }
        if (!state.originalWindowStateCaptured) {
            state.originalWindowBackground = decorView.background
            state.originalWindowBlurRadius = runCatching {
                Window::class.java.getMethod("getBackgroundBlurRadius").invoke(window) as? Int
            }.getOrNull()
            state.originalWindowStateCaptured = true
        }
        if (state.backgroundStyleDirty) {
            val transparent = state.transparentWindowBackground
                ?: Color.TRANSPARENT.toDrawable().also { state.transparentWindowBackground = it }
            if (decorView.background !== transparent) {
                window.setBackgroundBlurRadius(0)
                window.setBackgroundDrawable(transparent)
            }
        }

        applyBackgroundSurface(decorGroup, context, state, cornerRadii, bounds.top, backgroundHeight)
    }

    private fun applyFloatingBackground(root: ViewGroup, bounds: Rect, radius: Float) {
        val state = floatingBackgroundStates.getOrPut(root) { WeTypeWindowState() }
        val radii = WeTypeCornerRadii.uniform(radius)
        // The native drawable's optical outline excludes its transparent shadow
        // margins. Keep those margins out of the replacement material as well.
        applyBackgroundSurface(root, root.context, state, radii,
            bounds.top, bounds.height(), bounds.left, bounds.width())
        if (state.stopMaterialObserver == null) {
            val reference = WeakReference(root)
            state.stopMaterialObserver = WeTypeHyperMaterial.observeAvailability(root.context) {
                state.backgroundStyleDirty = true
                reference.get()?.invalidate()
            }
        }
    }

    private fun removeFloatingBackground(root: ViewGroup) {
        floatingBackgroundStates.remove(root)?.let(::removeBackgroundCarrier)
    }

    private fun applyBackgroundSurface(
        parent: ViewGroup,
        context: Context,
        state: WeTypeWindowState,
        cornerRadii: WeTypeCornerRadii,
        top: Int,
        height: Int,
        left: Int = 0,
        width: Int = parent.width
    ) {
        val settings = WeTypeSettings.readSnapshotXposed(context.resources)
        val overrides = if (settings.hyperMaterialEnabled && WeTypeHyperMaterial.areGlassOverridesAvailable()) {
            settings.glassOverrides
        } else GlassMaterialOverrides()
        val carrier = ensureBackgroundCarrier(context, parent, state, overrides)
        carrier.visibility = View.VISIBLE
        val style = BackgroundStyle(
            color = settings.backgroundColor,
            blurRadius = settings.blurRadius,
            edgeHighlightEnabled = settings.edgeHighlightEnabled,
            edgeHighlightIntensity = settings.edgeHighlightIntensity,
            cornerRadii = cornerRadii,
            nightMode = if (WeTypeSettings.isHostDarkMode(context.resources)) {
                Configuration.UI_MODE_NIGHT_YES
            } else Configuration.UI_MODE_NIGHT_NO,
            density = context.resources.displayMetrics.density,
            hyperMaterialEnabled = settings.hyperMaterialEnabled,
            hyperMaterialAvailable = if (state.backgroundStyleDirty || state.backgroundStyle == null) {
                WeTypeHyperMaterial.isAvailable(context)
            } else checkNotNull(state.backgroundStyle).hyperMaterialAvailable
        )
        val viewRoot = if (state.backgroundStyleDirty || carrier.background == null) {
            runCatching { carrier.invokeMethodAs<Any>("getViewRootImpl") }.getOrNull()
        } else {
            state.backgroundViewRoot
        }
        if (style.hyperMaterialEnabled && state.windowVisible && state.backgroundViewRoot !== viewRoot) {
            cancelBackgroundTextureWait(state)
            state.awaitingBackgroundTexture = true
        }
        if (state.awaitingBackgroundTexture && style.hyperMaterialEnabled && viewRoot != null) {
            awaitBackgroundTexture(state, viewRoot)
        }
        if (carrier.background == null || state.backgroundStyle != style || state.backgroundViewRoot !== viewRoot) {
            applyContinuousCornerOutline(carrier, cornerRadii)
            val material = checkNotNull(state.hyperMaterial)
            if (style.hyperMaterialEnabled) {
                // Native blur admission belongs to the window attachment. Ordinary
                // hide/show reuses it; a new ViewRoot must configure it again.
                if (state.backgroundViewRoot !== viewRoot) material.clear()
                // Remove the old blur/bloom drawable before enabling the system material.
                carrier.background = Color.TRANSPARENT.toDrawable()
                if (!material.apply(style.nightMode == Configuration.UI_MODE_NIGHT_YES, style.color)) {
                    cancelBackgroundTextureWait(state)
                    // An invocation failure keeps the keyboard legible without custom effects.
                    carrier.background = createTintDrawable(WeTypeHyperMaterial.fallbackColor(style.nightMode == Configuration.UI_MODE_NIGHT_YES), cornerRadii)
                }
            } else {
                cancelBackgroundTextureWait(state)
                material.clear()
                carrier.background = createBackgroundDrawable(carrier, context, style)
            }
            state.backgroundStyle = style
            state.backgroundViewRoot = viewRoot
        }
        if (style.hyperMaterialEnabled) applyStartupFill(state, style)
        state.backgroundStyleDirty = false
        // This decorative child keeps a zero-height layout spec. Its rendered bounds must
        // not feed back into the IME's measurement or the app-facing inset calculation.
        state.backgroundBounds.set(left, top, left + width, top + height)
        layoutBackgroundCarrier(carrier, state.backgroundBounds)
        if (style.hyperMaterialEnabled) state.hyperMaterial?.updateGeometry(cornerRadii)
    }

    private fun restoreInactiveBackgroundGeometry(state: WeTypeWindowState) {
        val carrier = state.backgroundCarrier ?: return
        val style = state.backgroundStyle ?: return
        if (!carrier.isAttachedToWindow || carrier.visibility != View.VISIBLE ||
            carrier.parent !== state.backgroundDecorView?.get() || state.backgroundBounds.isEmpty
        ) return
        layoutBackgroundCarrier(carrier, state.backgroundBounds)
        // The glass child and shadow sibling have zero-sized layout specs too.
        // updateGeometry restores their bounds while retaining its shader/style caches.
        if (style.hyperMaterialEnabled) state.hyperMaterial?.updateGeometry(style.cornerRadii)
    }

    private fun layoutBackgroundCarrier(carrier: View, bounds: Rect) {
        val width = bounds.width()
        val height = bounds.height()
        val left = bounds.left
        val top = bounds.top
        if (carrier.width != width || carrier.height != height || carrier.left != left || carrier.top != top) {
            carrier.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            carrier.layout(left, top, left + width, top + height)
            carrier.invalidateOutline()
        }
    }

    private fun ensureBackgroundCarrier(
        context: Context,
        decorGroup: ViewGroup,
        state: WeTypeWindowState,
        overrides: GlassMaterialOverrides
    ): View {
        val existing = state.backgroundCarrier?.takeIf { it.parent === decorGroup && state.carrierOverrides == overrides }
        if (existing != null) return existing

        state.backgroundCarrier?.let { oldCarrier ->
            clearStartupFill(state)
            state.hyperMaterial?.clear()
            (oldCarrier.parent as? ViewGroup)?.removeView(oldCarrier)
            state.backgroundStyle = null
            state.backgroundViewRoot = null
        }
        val carrier = FrameLayout(context).apply {
            visibility = View.INVISIBLE
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // Changing a foreground drawable requests layout; changing its color does not.
            // ColorDrawable skips drawing entirely while its color is transparent.
            foreground = ColorDrawable(Color.TRANSPARENT)
        }

        decorGroup.addView(
            carrier,
            0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
        )
        state.backgroundCarrier = carrier
        state.startupFill = carrier.foreground as ColorDrawable
        state.backgroundBounds.setEmpty()
        // A fresh RenderNode restores actual ROM defaults when an override is cleared.
        state.carrierOverrides = overrides
        state.hyperMaterial = WeTypeHyperMaterial(carrier, overrides)
        return carrier
    }

    private fun shouldHideBackground(
        decorView: View,
        state: WeTypeWindowState
    ): Boolean {
        val collapsedByInsets = state.computedVisibleImeHeightPx
            ?.let { it <= WETYPE_COLLAPSED_IME_HEIGHT_THRESHOLD_PX } == true
        if (collapsedByInsets && !isMiuiClipboardActive()) return true

        // The input view is already a descendant of decor; do not scan it twice.
        // Floating keys live in their own PopupWindow; the service only keeps a
        // small proxy view and must not receive a second, full-width material.
        return containsWeTypeDetachedKeyboardView(decorView, state)
    }

    private fun containsWeTypeDetachedKeyboardView(view: View, state: WeTypeWindowState): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val className = view.javaClass.name
        if (className.startsWith(WETYPE_HARDWARE_VIEW_CLASS_PREFIX) ||
            className.startsWith(WETYPE_FLOATING_VIEW_CLASS_PREFIX)) return true

        val hardwareViewIds = state.hardwareViewIds ?: resolveHardwareViewIds(view.context)
            .also { state.hardwareViewIds = it }
        if (view.id != View.NO_ID && hardwareViewIds.contains(view.id)) return true

        val group = view as? ViewGroup ?: return false
        for (index in 0 until group.childCount) {
            if (containsWeTypeDetachedKeyboardView(group.getChildAt(index), state)) return true
        }
        return false
    }

    private fun resolveHardwareViewIds(context: Context): IntArray =
        WETYPE_HARDWARE_VIEW_ID_NAMES.mapNotNull { name ->
            context.resources.getIdentifier(name, "id", context.packageName)
                .takeIf { it != 0 }
        }.toIntArray()

    private fun hideBackgroundCarrier(state: WeTypeWindowState) {
        cancelBackgroundTextureWait(state)
        val carrier = state.backgroundCarrier ?: return
        carrier.visibility = View.INVISIBLE
        if (state.backgroundStyle?.hyperMaterialEnabled == true) {
            state.hyperMaterial?.clear()
            state.backgroundStyle = null
        }
        // Retain the ordinary blur/bloom paths across hide/show. Recheck the ViewRoot
        // before reuse because its blur drawable belongs to that window attachment.
        state.backgroundStyleDirty = true
    }

    private fun removeBackgroundCarrier(state: WeTypeWindowState) {
        cancelBackgroundTextureWait(state)
        state.stopMaterialObserver?.invoke()
        state.stopMaterialObserver = null
        state.hyperMaterial?.clear()
        state.hyperMaterial = null
        val carrier = state.backgroundCarrier ?: return
        (carrier.parent as? ViewGroup)?.removeView(carrier)
        state.backgroundCarrier = null
        state.startupFill = null
        state.backgroundBounds.setEmpty()
        state.backgroundStyle = null
        state.backgroundViewRoot = null
        state.window = null
    }

    private fun restoreWindowState(state: WeTypeWindowState) {
        if (!state.originalWindowStateCaptured) return
        val window = state.window?.get() ?: return
        runCatching {
            window.setBackgroundBlurRadius(state.originalWindowBlurRadius ?: 0)
            window.setBackgroundDrawable(state.originalWindowBackground)
        }
        state.originalWindowStateCaptured = false
        state.originalWindowBackground = null
        state.originalWindowBlurRadius = null
    }

    private fun createBackgroundDrawable(targetView: View, context: Context, style: BackgroundStyle): Drawable {
        val (color, blurRadius, edgeHighlightEnabled, edgeHighlightIntensity, cornerRadii) = style
        val tintDrawable = createTintDrawable(color, cornerRadii)
        val blurDrawable = createInternalBackgroundBlurDrawable(targetView, blurRadius, cornerRadii)
        val layers = buildList {
            blurDrawable?.also(::add)
            add(tintDrawable)
            if (edgeHighlightEnabled) {
                add(
                    WeTypeBloomStrokeDrawable(
                        context = context,
                        cornerRadii = cornerRadii,
                        surfaceColor = color,
                        intensityScale = edgeHighlightIntensity / 100f,
                        darkMode = style.nightMode == Configuration.UI_MODE_NIGHT_YES
                    )
                )
            }
        }
        return if (layers.size == 1) layers.first() else android.graphics.drawable.LayerDrawable(layers.toTypedArray())
    }

    private fun createInternalBackgroundBlurDrawable(targetView: View, blurRadius: Int, cornerRadii: WeTypeCornerRadii): Drawable? {
        val viewRootImpl = runCatching { targetView.invokeMethodAs<Any>("getViewRootImpl") }.getOrNull() ?: return null
        val blurDrawable = runCatching { viewRootImpl.invokeMethodAs<Drawable>("createBackgroundBlurDrawable") }.getOrNull() ?: return null
        runCatching { blurDrawable.javaClass.getMethod("setBlurRadius", Int::class.javaPrimitiveType).invoke(blurDrawable, blurRadius) }
        runCatching { blurDrawable.javaClass.getMethod("setColor", Int::class.javaPrimitiveType).invoke(blurDrawable, Color.TRANSPARENT) }
        runCatching {
            blurDrawable.javaClass.getMethod(
                "setCornerRadius",
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            ).invoke(
                blurDrawable,
                cornerRadii.topLeft,
                cornerRadii.topRight,
                cornerRadii.bottomRight,
                cornerRadii.bottomLeft
            )
        }.recoverCatching {
            blurDrawable.javaClass.getMethod("setCornerRadius", Float::class.javaPrimitiveType)
                .invoke(blurDrawable, cornerRadii.maxRadius())
        }
        return blurDrawable
    }

    private fun createTintDrawable(color: Int, cornerRadii: WeTypeCornerRadii): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        this.cornerRadii = cornerRadii.toArray()
        setColor(color)
    }

    private fun applyContinuousCornerOutline(
        view: View,
        cornerRadii: WeTypeCornerRadii
    ) {
        val current = view.outlineProvider as? ContinuousCornerOutline
        if (current?.cornerRadii == cornerRadii && view.clipToOutline) return
        view.clipToOutline = true
        view.outlineProvider = ContinuousCornerOutline(cornerRadii)
        view.invalidateOutline()
    }

    private fun runOnMainThreadBlocking(block: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(block).onFailure {
                Log.i("Failed: Cleanup WeType window state for hot reload")
                Log.i(it)
            }.isSuccess
        }

        val completed = CountDownLatch(1)
        var failure: Throwable? = null
        if (!Handler(Looper.getMainLooper()).post {
                try {
                    block()
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    completed.countDown()
                }
            }
        ) {
            return false
        }
        val finished = runCatching { completed.await(2, TimeUnit.SECONDS) }.getOrDefault(false)
        failure?.let {
            Log.i("Failed: Cleanup WeType window state for hot reload")
            Log.i(it)
        }
        return finished && failure == null
    }
}

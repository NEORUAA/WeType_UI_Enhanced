package com.xposed.wetypehook.wetype.hook

import android.graphics.Canvas
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import com.xposed.wetypehook.xposed.HookEnvironment
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.loadClassOrNull
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

private const val SETTINGS_KEYBOARD = "com.tencent.wetype.plugin.hld.keyboard.S10SettingsKeyboard"
private const val CANDIDATE_VIEW = "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView"
private const val SETTINGS_RECYCLER = "com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingRecyclerView"
private val OVERLAY_KEYBOARDS = setOf(
    SETTINGS_KEYBOARD,
    "com.tencent.wetype.plugin.hld.keyboard.selfdraw.S11EmojiKeyboard",
    "com.tencent.wetype.plugin.hld.keyboard.S15CustomPhraseAndClipboardKeyboard",
    "com.tencent.wetype.plugin.hld.keyboard.S29ClipboardCleanRecordKeyboard",
    "com.tencent.wetype.plugin.hld.keyboard.S34ClipboardBombKeyboard",
    "com.tencent.wetype.plugin.hld.keyboard.S35RequestAIKeyboard"
)
private val SETTINGS_PAGES = setOf(
    "com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingKeyboardTypeView",
    "com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingCustomToolbarView",
    "com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingPlusModeOpenView",
    "com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingOfflineVoiceGuideView"
)

/**
 * Transparent skins remove the host's opaque occlusion. Restore that occlusion at
 * composition time, without taking ownership of visibility, alpha or animations.
 */
internal object WeTypeOverlayCompositor {
    private class WindowFrame {
        var observer: WeakReference<ViewTreeObserver>? = null
        var listener: ViewTreeObserver.OnPreDrawListener? = null
        var maskedViews: List<Pair<WeakReference<View>, Int>> = emptyList()
    }

    private val windows = WeakHashMap<View, WindowFrame>()
    private val drawMasks = Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val keyboardClasses = WeakHashMap<Class<*>, Boolean>()
    private val settingsContentGetters = WeakHashMap<Class<*>, Method?>()
    private var settingsManager: Any? = null
    private var toolsRequestedGetter: Method? = null
    private var settingsChromeIds: Set<Int> = emptySet()

    fun install() {
        resolveSettingsStateReader()
        val resourceClass = loadClassOrNull("com.tencent.wetype.plugin.hld.s")
        // Host-owned chrome has two sibling branches: back/title and the lazily
        // added settings toolbar (settings / file transfer). Neither is candidates.
        settingsChromeIds = listOf("setting_container_rl", "setting_toolbar_recyclerview")
            .mapNotNull { name ->
                runCatching { resourceClass?.getDeclaredField(name)?.getInt(null) }.getOrNull()
            }.toSet()
        runCatching {
            val drawChild = ViewGroup::class.java.getDeclaredMethod(
                "drawChild", Canvas::class.java, View::class.java, Long::class.javaPrimitiveType
            ).apply { isAccessible = true }
            HookEnvironment.registerHook(drawChild, "overlay-composition") { chain ->
                val child = chain.args[1] as? View
                val canvas = chain.args[0] as? Canvas
                val drawingAlpha = child?.let(drawMasks::get)
                if (child == null || canvas == null || drawingAlpha == null) {
                    chain.proceed()
                } else {
                    // An empty clip/early return can skip descendant legacy animations.
                    // A transparent layer retains the entire native draw traversal.
                    withWeTypeTransparentDrawing(
                        saveTransparentLayer = { canvas.saveLayerAlpha(null, drawingAlpha) },
                        restore = canvas::restoreToCount,
                        draw = { chain.proceed() }
                    )
                }
            }
            val serviceClass = InputMethodService::class.java
            serviceClass.getMethod("onWindowShown").hookAfter { registerServiceWindow(it.thisObject) }
            serviceClass.getMethod("onStartInputView", EditorInfo::class.java,
                Boolean::class.javaPrimitiveType).hookAfter { registerServiceWindow(it.thisObject) }
            listOf("onWindowHidden", "hideWindow", "onDestroy").forEach { name ->
                serviceClass.getMethod(name).hookAfter { param ->
                    serviceDecor(param.thisObject)?.let(::removeWindow)
                }
            }
            Log.i("Success: Hook WeType transparent layer composition")
        }.onFailure {
            Log.i("Failed: Hook WeType transparent layer composition")
            Log.i(it)
        }
    }

    private fun serviceDecor(service: Any): View? =
        (service as? InputMethodService)?.takeIf { it.packageName == "com.tencent.wetype" }
            ?.window?.window?.decorView

    private fun registerServiceWindow(service: Any) {
        serviceDecor(service)?.let(::registerWindow)
    }

    fun reconcile(rootViews: List<View>) {
        rootViews.map { it.rootView }.distinct().filter(::containsHostLayer).forEach(::registerWindow)
    }

    private fun containsHostLayer(view: View): Boolean {
        if (view.javaClass.name == CANDIDATE_VIEW || isKeyboardRoot(view)) return true
        return view is ViewGroup && (0 until view.childCount).any {
            containsHostLayer(view.getChildAt(it))
        }
    }

    private fun registerWindow(decor: View) {
        val frame = windows.getOrPut(decor) { WindowFrame() }
        val observer = decor.viewTreeObserver
        if (!observer.isAlive) return
        if (frame.observer?.get() !== observer) {
            removeListener(frame, observer)
            val rootReference = WeakReference(decor)
            val listener = ViewTreeObserver.OnPreDrawListener {
                rootReference.get()?.let { root ->
                    runCatching { updateFrame(root, frame) }.onFailure {
                        clearMasks(frame)
                        Log.i("Failed: Compose WeType layers")
                        Log.i(it)
                    }
                }
                true
            }
            frame.observer = WeakReference(observer)
            frame.listener = listener
            observer.addOnPreDrawListener(listener)
        }
        updateFrame(decor, frame)
    }

    private fun updateFrame(decor: View, frame: WindowFrame) {
        val layers = mutableListOf<WeTypeLayerSnapshot>()
        val views = mutableMapOf<Int, View>()
        var nextId = 0
        fun visit(view: View, ancestors: List<Int>, parentRendered: Boolean, settingsOwner: Int?,
                  candidateOwner: Int?, parentOpacity: Float) {
            val id = nextId++
            val rendered = parentRendered && view.isAttachedToWindow &&
                (view.visibility == View.VISIBLE || view.animation?.hasEnded() == false) &&
                view.alpha > 0f && view.width > 0 && view.height > 0
            if (!rendered) return
            val name = view.javaClass.name
            val owner = if (name == SETTINGS_KEYBOARD) id else settingsOwner
            val candidate = if (name == CANDIDATE_VIEW) id else candidateOwner
            val effectiveOpacity = parentOpacity * view.alpha
            val role = when {
                name in OVERLAY_KEYBOARDS -> WeTypeLayerRole.OverlayKeyboard
                name == CANDIDATE_VIEW -> WeTypeLayerRole.Candidate
                candidateOwner != null && view.id in settingsChromeIds ->
                    WeTypeLayerRole.SettingsChrome
                name in SETTINGS_PAGES -> WeTypeLayerRole.SettingsPage
                owner != null && view is LinearLayout && containsSettingsRecycler(view) ->
                    WeTypeLayerRole.SettingsTools
                isKeyboardRoot(view) -> WeTypeLayerRole.Keyboard
                candidateOwner != null -> WeTypeLayerRole.CandidateContent
                else -> null
            }
            if (role != null) {
                val opacity = if (role == WeTypeLayerRole.SettingsPage) {
                    // The host animates the page's content and below-background alpha,
                    // not the root FrameLayout. Read the same native content branch.
                    val getter = settingsContentGetters.getOrPut(view.javaClass) {
                        runCatching { view.javaClass.getMethod("getSettingBaseGridViewContainer") }.getOrNull()
                    }
                    val content = runCatching { getter?.invoke(view) as? View }.getOrNull()
                    view.alpha * (content?.alpha ?: 1f)
                } else effectiveOpacity
                layers += WeTypeLayerSnapshot(id, role, ancestors, rendered, owner, opacity)
                views[id] = view
            }
            if (view is ViewGroup) {
                // The host's keyboard containers use standard child order. Z is applied
                // before index, matching the framework's stable preordered child list.
                (0 until view.childCount).map(view::getChildAt).sortedBy { it.z }.forEach {
                    visit(it, ancestors + id, rendered, owner, candidate, effectiveOpacity)
                }
            }
        }
        visit(decor, emptyList(), decor.isShown, null, null, 1f)
        val toolsRequested = runCatching {
            toolsRequestedGetter?.invoke(settingsManager) as? Boolean
        }.getOrNull()
        val drawingAlphas = resolveWeTypeDrawAlphas(layers, toolsRequested)
        val masked = drawingAlphas.mapNotNull { (id, alpha) ->
            views[id]?.let { it to alpha }
        }.toMap()
        val previous = frame.maskedViews.mapNotNull { (reference, alpha) ->
            reference.get()?.let { it to alpha }
        }.toMap()
        (previous.keys - masked.keys).forEach { drawMasks.remove(it) }
        masked.forEach { (view, alpha) -> drawMasks[view] = alpha }
        // Hardware display lists can reuse the old masking command even when only
        // the host's alpha changed. Re-record affected parents when the plan changes.
        (previous.keys + masked.keys).filter { previous[it] != masked[it] }.forEach(::invalidateDrawing)
        frame.maskedViews = masked.map { (view, alpha) -> WeakReference(view) to alpha }
    }

    private fun isKeyboardRoot(view: View): Boolean {
        if (!view.javaClass.name.startsWith("com.tencent.wetype.plugin.hld.keyboard.")) return false
        return keyboardClasses.getOrPut(view.javaClass) {
            view.javaClass.methods.any { it.name == "getKeyboardType" && it.parameterCount == 0 }
        }
    }

    private fun containsSettingsRecycler(view: View): Boolean {
        if (view.javaClass.name == SETTINGS_RECYCLER) return true
        return view is ViewGroup && (0 until view.childCount).any {
            containsSettingsRecycler(view.getChildAt(it))
        }
    }

    private fun resolveSettingsStateReader() {
        // Verified against the actual 3.5.4 Tinker SettingsMgr: d() reads the same
        // showSettingToolbar StateFlow that o(boolean) updates during page rendering.
        runCatching {
            val clazz = loadClassOrNull("com.tencent.wetype.plugin.hld.settings.d")
                ?: error("No SettingsMgr")
            val singleton = clazz.declaredFields.single {
                Modifier.isStatic(it.modifiers) && it.type == clazz
            }.apply { isAccessible = true }.get(null)
            val getter = clazz.getDeclaredMethod("d").apply { isAccessible = true }
            check(getter.returnType == Boolean::class.javaPrimitiveType)
            check(getter.invoke(singleton) is Boolean)
            settingsManager = singleton
            toolsRequestedGetter = getter
        }.onFailure {
            // Unknown hosts still get visual occlusion from actual page views. Do
            // not guess a pending page from a timer or change their visibility.
            Log.i("Unavailable: WeType settings intent reader; using rendered layers")
        }
    }

    private fun invalidateDrawing(view: View) {
        view.invalidate()
        (view.parent as? View)?.invalidate()
    }

    private fun clearMasks(frame: WindowFrame) {
        frame.maskedViews.mapNotNull { it.first.get() }.forEach {
            drawMasks.remove(it)
            invalidateDrawing(it)
        }
        frame.maskedViews = emptyList()
    }

    private fun removeListener(frame: WindowFrame, currentObserver: ViewTreeObserver? = null) {
        val listener = frame.listener
        // A floating observer transfers its listeners when the decor attaches. The
        // stored observer may now be dead while the listener lives on the new one.
        if (listener != null) {
            listOfNotNull(frame.observer?.get(), currentObserver).distinct().forEach { observer ->
                if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            }
        }
        frame.observer = null
        frame.listener = null
    }

    private fun removeWindow(decor: View) {
        windows.remove(decor)?.let {
            removeListener(it, decor.viewTreeObserver)
            clearMasks(it)
        }
    }

    fun clearAll() {
        windows.entries.toList().forEach { (decor, frame) ->
            removeListener(frame, decor.viewTreeObserver)
            clearMasks(frame)
        }
        windows.clear()
        drawMasks.clear()
        keyboardClasses.clear()
        settingsContentGetters.clear()
        settingsManager = null
        toolsRequestedGetter = null
        settingsChromeIds = emptySet()
    }
}

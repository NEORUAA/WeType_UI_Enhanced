package com.xposed.wetypehook.wetype.hook

import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.PopupWindow
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import com.xposed.wetypehook.xposed.loadClassOrNull
import com.xposed.wetypehook.wetype.graphics.WeTypeFloatingResizeHandlesDrawable
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

private const val FLOATING_HOST_PREFIX = "com.tencent.wetype.plugin.hld.float."
private const val FLOATING_BACKGROUND_ROOT = "com.tencent.wetype.plugin.hld.view.ImeRadiusConstraintLayout"
private const val FLOATING_CONTENT = "com.tencent.wetype.plugin.hld.view.ImeRadiusLinearLayout"
private const val FLOATING_CANDIDATE = "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView"
private const val KEYBOARD_CLASS_PREFIX = "com.tencent.wetype.plugin.hld.keyboard."

/** Observes the floating popup's native background without changing its layout or gestures. */
internal object WeTypeFloatingBackgrounds {
    private data class FloatingGeometry(val bounds: Rect, val radius: Float)

    private class SuppressedBackground(view: View, var original: Drawable) {
        val viewReference = WeakReference(view)
    }

    private class FloatingFrame(
        normalBackground: ImageView,
        editBackground: ImageView,
        content: ViewGroup,
        val radiusGetter: Method
    ) {
        var active = true
        val normalBackgroundReference = WeakReference(normalBackground)
        val editBackgroundReference = WeakReference(editBackground)
        var originalImage: Drawable? = normalBackground.drawable
        var imageSuppressed = false
        var originalEditImage: Drawable? = editBackground.drawable
        var resizeHandles: WeTypeFloatingResizeHandlesDrawable? = null
        val contentReference = WeakReference(content)
        val suppressedBackgrounds = mutableListOf<SuppressedBackground>()
        var observer: WeakReference<ViewTreeObserver>? = null
        var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
        var attachListener: View.OnAttachStateChangeListener? = null
    }

    // The View's listeners own the frame; neither weak map keys nor values keep a
    // dismissed popup alive through a saved drawable's callback.
    private val frames = WeakHashMap<ViewGroup, WeakReference<FloatingFrame>>()
    private val radiusGetters = WeakHashMap<Class<*>, Method?>()
    private val keyboardClasses = WeakHashMap<Class<*>, Boolean>()
    private var backgroundViewIds: Set<Int> = emptySet()
    private var keyboardContentId: Int? = null
    private var updateBackground: ((ViewGroup, Rect, Float) -> Unit)? = null
    private var removeBackground: ((ViewGroup) -> Unit)? = null
    private var installed = false

    fun install(update: (ViewGroup, Rect, Float) -> Unit, remove: (ViewGroup) -> Unit) {
        resolveBackgroundViewIds()
        updateBackground = update
        removeBackground = remove
        if (installed) return
        val methods = PopupWindow::class.java.declaredMethods
            .filter { it.name == "showAtLocation" }
            .sortedBy { it.toGenericString() }
        methods.forEach { method ->
            runCatching {
                method.hookAfter { param ->
                    val popup = param.thisObject as? PopupWindow ?: return@hookAfter
                    if (!popup.isShowing || updateBackground == null) return@hookAfter
                    runCatching { registerHost(popup.contentView) }.onFailure {
                        Log.i("Failed: Observe WeType floating popup")
                        Log.i(it)
                    }
                }
                installed = true
            }.onFailure {
                Log.i("Failed: Hook WeType floating popup ${method.name}")
                Log.i(it)
            }
        }
        installSnapshotProducerHook()
        if (installed) Log.i("Success: Hook WeType floating backgrounds")
    }

    private fun installSnapshotProducerHook() {
        runCatching {
            val producer = loadClassOrNull("com.tencent.wetype.plugin.hld.float.f\$g")
                ?: error("No floating keyboard snapshot producer")
            val methods = producer.declaredMethods.filter {
                !it.isBridge && it.parameterCount == 0 && it.returnType == Bitmap::class.java
            }.sortedBy { it.toGenericString() }
            check(methods.isNotEmpty()) { "No floating keyboard snapshot method" }
            methods.forEach { method ->
                method.hookBefore {
                    if (updateBackground == null) return@hookBefore
                    runCatching {
                        // Resize snapshots use an attached but INVISIBLE live popup.
                        // Clear host theme rewrites just before drawToBitmap, without
                        // requiring a visible frame or changing its native lifecycle.
                        frames.values.toList().mapNotNull { it.get() }.forEach { frame ->
                            val content = frame.contentReference.get()
                            if (frame.active && content?.isAttachedToWindow == true) {
                                suppressContentBackgrounds(content, frame)
                            }
                        }
                    }.onFailure {
                        Log.i("Failed: Prepare WeType floating snapshot backgrounds")
                        Log.i(it)
                    }
                }
                installed = true
            }
        }.onFailure {
            Log.i("Failed: Hook WeType floating snapshot producer")
            Log.i(it)
        }
    }

    fun reconcile(rootViews: List<View>) {
        if (updateBackground == null) return
        // Use the actual WindowManager roots supplied by the caller. Floating host
        // views override getRootView() to return only their background container.
        fun visit(view: View) {
            if (view.javaClass.name.startsWith(FLOATING_HOST_PREFIX)) registerHost(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        rootViews.forEach { root ->
            runCatching { visit(root) }.onFailure {
                Log.i("Failed: Reconcile WeType floating background")
                Log.i(it)
            }
        }
    }

    private fun registerHost(view: View?) {
        val host = view as? ViewGroup ?: return
        if (!host.javaClass.name.startsWith(FLOATING_HOST_PREFIX)) return
        val children = (0 until host.childCount).map(host::getChildAt)
        val content = children.singleOrNull { it.javaClass.name == FLOATING_CONTENT } as? ViewGroup ?: return
        val candidates = children.filterIsInstance<ViewGroup>().mapNotNull { root ->
            if (root.javaClass.name != FLOATING_BACKGROUND_ROOT) return@mapNotNull null
            val images = (0 until root.childCount).map(root::getChildAt).filterIsInstance<ImageView>()
            if (images.size == 2) root to images else null
        }
        val (root, images) = candidates.singleOrNull() ?: return
        val frame = frames[root]?.get() ?: run {
            val getter = radiusGetters.getOrPut(content.javaClass) {
                runCatching { content.javaClass.getMethod("getRadius") }.getOrNull()
            } ?: return
            FloatingFrame(images[0], images[1], content, getter).also { created ->
                frames[root] = WeakReference(created)
                val listener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) = Unit

                    override fun onViewDetachedFromWindow(view: View) {
                        removeFrame(view as ViewGroup, created)
                    }
                }
                created.attachListener = listener
                root.addOnAttachStateChangeListener(listener)
            }
        }
        val observer = root.viewTreeObserver
        if (observer.isAlive && frame.observer?.get() !== observer) {
            removePreDrawListener(frame, observer)
            val reference = WeakReference(root)
            val listener = ViewTreeObserver.OnPreDrawListener {
                reference.get()?.let { current -> updateFrameSafely(current, frame) }
                true
            }
            frame.observer = WeakReference(observer)
            frame.preDrawListener = listener
            observer.addOnPreDrawListener(listener)
        }
        updateFrameSafely(root, frame)
    }

    private fun updateFrameSafely(root: ViewGroup, frame: FloatingFrame) {
        runCatching { updateFrame(root, frame) }.onFailure {
            removeFrame(root, frame)
            Log.i("Failed: Apply WeType floating background")
            Log.i(it)
        }
    }

    private fun updateFrame(root: ViewGroup, frame: FloatingFrame) {
        if (!frame.active || !root.isAttachedToWindow || !root.isShown || root.width <= 0 || root.height <= 0) return
        val update = updateBackground ?: return
        val content = frame.contentReference.get() ?: return
        if (!content.isAttachedToWindow || !content.isShown || content.width <= 0 || content.height <= 0) return
        val normalBackground = frame.normalBackgroundReference.get() ?: return
        val editBackground = frame.editBackgroundReference.get()
        val currentEditImage = editBackground?.drawable
        if (currentEditImage != null && currentEditImage !== frame.resizeHandles) {
            frame.originalEditImage = currentEditImage
        }
        val normalDrawable = normalBackground.drawable ?: frame.originalImage
        // Theme resources arrive asynchronously when a popup first attaches. Wait
        // for them rather than briefly painting a differently sized fallback.
        if (normalDrawable == null && frame.originalEditImage == null) return
        val editGeometry = editBackground?.let { drawableGeometry(it, frame.originalEditImage) }
        val geometry = drawableGeometry(normalBackground, normalDrawable)
            ?: editGeometry
            ?: contentGeometry(root, content, frame.radiusGetter) ?: return
        update(root, geometry.bounds, geometry.radius)
        // Preserve the native edit ImageView, including its alpha animation and
        // resize hit regions; only replace its opaque image with corner strokes.
        if (editBackground != null && editGeometry != null) {
            val handles = frame.resizeHandles ?: WeTypeFloatingResizeHandlesDrawable().also {
                frame.resizeHandles = it
            }
            val localBounds = Rect(editGeometry.bounds).apply {
                offset(-editBackground.left - editBackground.paddingLeft,
                    -editBackground.top - editBackground.paddingTop)
            }
            val strokeWidth = 2.5f * editBackground.resources.displayMetrics.density
            handles.updateGeometry(localBounds, editGeometry.radius, strokeWidth)
            if (editBackground.drawable !== handles) editBackground.setImageDrawable(handles)
        }
        normalBackground.drawable?.let { image ->
            frame.originalImage = image
            normalBackground.setImageDrawable(null)
            frame.imageSuppressed = true
        }
        suppressContentBackgrounds(content, frame)
        restoreSnapshotAlpha(content)
    }

    private fun resolveBackgroundViewIds() {
        // Resource entry names are obfuscated in production. The host's R.id
        // fields retain the semantic names used by its floating-mode renderer.
        val resourceClass = loadClassOrNull("com.tencent.wetype.plugin.hld.s")
        fun id(name: String): Int? = runCatching {
            resourceClass?.getDeclaredField(name)?.getInt(null)?.takeIf { it > 0 }
        }.getOrNull()
        backgroundViewIds = listOf("input_center_view", "below_view", "candidate_container")
            .mapNotNull(::id).toSet()
        keyboardContentId = id("root")
    }

    private fun isKeyboardRoot(view: View): Boolean {
        return keyboardClasses.getOrPut(view.javaClass) {
            // Symbol keyboards live in a different package but inherit the same
            // keyboard base and receive the same floating-mode background.
            generateSequence<Class<*>>(view.javaClass) { it.superclass }
                .any { it.name.startsWith(KEYBOARD_CLASS_PREFIX) } &&
                view.javaClass.methods.any { it.name == "getKeyboardType" && it.parameterCount == 0 }
        }
    }

    private fun suppressContentBackgrounds(content: ViewGroup, frame: FloatingFrame) {
        val targets = linkedSetOf<View>(content)
        fun visit(view: View) {
            if (view.id in backgroundViewIds || view.javaClass.name == FLOATING_CANDIDATE) targets += view
            if (isKeyboardRoot(view)) {
                keyboardContentId?.let { id -> view.findViewById<View>(id)?.let(targets::add) }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(content)
        val iterator = frame.suppressedBackgrounds.iterator()
        while (iterator.hasNext()) {
            val saved = iterator.next()
            val view = saved.viewReference.get()
            if (view == null || view !in targets) {
                restoreBackground(saved)
                iterator.remove()
            }
        }
        targets.forEach { view ->
            val background = view.background ?: return@forEach
            val saved = frame.suppressedBackgrounds.firstOrNull { it.viewReference.get() === view }
            if (saved == null) frame.suppressedBackgrounds += SuppressedBackground(view, background)
            else saved.original = background
            setBackgroundPreservingPadding(view, null)
        }
    }

    private fun restoreBackground(saved: SuppressedBackground) {
        saved.viewReference.get()?.let { view ->
            if (view.background == null) setBackgroundPreservingPadding(view, saved.original)
        }
    }

    private fun restoreSnapshotAlpha(content: ViewGroup) {
        // Only adjust popups replace the live keyboard with a direct FIT_START
        // ImageView after the drag bar. Native code disables alpha on an otherwise
        // transparent drawToBitmap result; restore metadata without changing pixels.
        if (content.childCount != 2 || content.getChildAt(0).javaClass.name != FLOATING_BACKGROUND_ROOT) return
        val snapshot = content.getChildAt(1) as? ImageView ?: return
        if (snapshot.scaleType != ImageView.ScaleType.FIT_START) return
        val bitmap = (snapshot.drawable as? BitmapDrawable)?.bitmap ?: return
        if (bitmap.isRecycled || bitmap.config != Bitmap.Config.ARGB_8888 || bitmap.hasAlpha()) return
        bitmap.setHasAlpha(true)
        snapshot.invalidate()
    }

    private fun drawableGeometry(image: ImageView, drawable: Drawable?): FloatingGeometry? {
        // Native nine-patches carry the visible body's optical outline, excluding
        // their transparent shadow margins. A transparent dark-mode ColorDrawable
        // must not be interpreted as a full-root body; the edit image is the fallback.
        if (drawable !is NinePatchDrawable || drawable.alpha <= 0 ||
            image.scaleType != ImageView.ScaleType.FIT_XY
        ) return null
        val width = image.width - image.paddingLeft - image.paddingRight
        val height = image.height - image.paddingTop - image.paddingBottom
        if (width <= 0 || height <= 0) return null
        return runCatching {
            val previousBounds = Rect(drawable.bounds)
            val outline = Outline()
            try {
                drawable.setBounds(0, 0, width, height)
                drawable.getOutline(outline)
            } finally {
                drawable.bounds = previousBounds
            }
            val bounds = Rect()
            val radius = outline.radius
            if (!outline.alpha.isFinite() || outline.alpha <= 0f || !outline.getRect(bounds) ||
                bounds.isEmpty || !radius.isFinite() || radius < 0f
            ) return@runCatching null
            bounds.offset(image.left + image.paddingLeft, image.top + image.paddingTop)
            FloatingGeometry(bounds, radius)
        }.getOrNull()
    }

    private fun contentGeometry(root: ViewGroup, content: ViewGroup, radiusGetter: Method): FloatingGeometry? {
        val radius = (radiusGetter.invoke(content) as? Number)?.toFloat() ?: return null
        if (!radius.isFinite() || radius < 0f) return null
        val bounds = Rect(content.left, content.top, content.right, content.bottom)
        bounds.offset(-root.left, -root.top)
        return bounds.takeUnless { it.isEmpty }?.let { FloatingGeometry(it, radius) }
    }

    private fun setBackgroundPreservingPadding(view: View, background: Drawable?) {
        val relative = view.isPaddingRelative
        val start = if (relative) view.paddingStart else view.paddingLeft
        val top = view.paddingTop
        val end = if (relative) view.paddingEnd else view.paddingRight
        val bottom = view.paddingBottom
        view.background = background
        val currentStart = if (relative) view.paddingStart else view.paddingLeft
        val currentEnd = if (relative) view.paddingEnd else view.paddingRight
        if (relative != view.isPaddingRelative || start != currentStart || top != view.paddingTop ||
            end != currentEnd || bottom != view.paddingBottom
        ) {
            if (relative) view.setPaddingRelative(start, top, end, bottom)
            else view.setPadding(start, top, end, bottom)
        }
    }

    private fun removePreDrawListener(frame: FloatingFrame, currentObserver: ViewTreeObserver?) {
        frame.preDrawListener?.let { listener ->
            listOfNotNull(frame.observer?.get(), currentObserver).distinct().forEach { observer ->
                if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            }
        }
        frame.observer = null
        frame.preDrawListener = null
    }

    private fun removeFrame(root: ViewGroup, frame: FloatingFrame) {
        if (!frame.active) return
        frame.active = false
        frames.remove(root)
        runCatching {
            removePreDrawListener(frame, root.viewTreeObserver)
            frame.attachListener?.let(root::removeOnAttachStateChangeListener)
            frame.attachListener = null
            frame.normalBackgroundReference.get()?.let { normalBackground ->
                if (frame.imageSuppressed && normalBackground.drawable == null) {
                    normalBackground.setImageDrawable(frame.originalImage)
                }
            }
            frame.editBackgroundReference.get()?.let { editBackground ->
                if (editBackground.drawable === frame.resizeHandles && frame.resizeHandles != null) {
                    editBackground.setImageDrawable(frame.originalEditImage)
                }
            }
        }.onFailure {
            Log.i("Failed: Restore WeType floating background")
            Log.i(it)
        }
        frame.suppressedBackgrounds.forEach { saved ->
            runCatching { restoreBackground(saved) }.onFailure {
                Log.i("Failed: Restore WeType floating content background")
                Log.i(it)
            }
        }
        frame.suppressedBackgrounds.clear()
        runCatching { removeBackground?.invoke(root) }.onFailure {
            Log.i("Failed: Remove WeType floating material")
            Log.i(it)
        }
        frame.originalImage = null
        frame.originalEditImage = null
        frame.resizeHandles = null
    }

    fun clearAll() {
        frames.entries.toList().forEach { (root, reference) ->
            reference.get()?.let { removeFrame(root, it) }
        }
        frames.clear()
        radiusGetters.clear()
        keyboardClasses.clear()
        backgroundViewIds = emptySet()
        keyboardContentId = null
        updateBackground = null
        removeBackground = null
    }
}

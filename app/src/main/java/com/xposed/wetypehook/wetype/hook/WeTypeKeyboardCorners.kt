package com.xposed.wetypehook.wetype.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.xposed.wetypehook.wetype.graphics.WeTypeKeyCornerCanvas
import com.xposed.wetypehook.xposed.HookEnvironment
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookBefore
import com.xposed.wetypehook.xposed.loadClassOrNull
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

/** Changes background geometry while retaining the host's drawing state and key behavior. */
internal object WeTypeKeyboardCorners {
    private const val KEYBOARD = "com.tencent.wetype.plugin.hld.keyboard.selfdraw.S3EnglishQwertyKeyboard"
    private const val HOOK_SCOPE = "wetype.keyboard-corners"
    private val inspectedTypes = mutableSetOf<Class<*>>()
    private val hookedBackgrounds = mutableSetOf<Method>()
    private val inspectedDrawers = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    // Keep module instances off host ThreadLocalMaps so old generations can be collected.
    private val renderStates = WeakHashMap<Thread, RenderState>()

    private fun renderState(): RenderState = synchronized(renderStates) {
        renderStates.getOrPut(Thread.currentThread()) { RenderState() }
    }

    fun hook() {
        runCatching {
            val keyboard = loadClassOrNull(KEYBOARD)?.getMethod("getActionButton")?.declaringClass
                ?: error("Self-draw keyboard accessor unavailable")
            val buttonType = keyboard.getMethod("getActionButton").returnType
            val drawerType = keyboard.getMethod("getButtonDrawer").returnType
            val register = drawerType.declaredMethods.single { method ->
                method.returnType == Void.TYPE && method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].name.contains(".selfdraw.drawmethod.")
            }
            val abstractDrawer = register.parameterTypes.single()
            val drawerName = abstractDrawer.declaredMethods.single { method ->
                Modifier.isAbstract(method.modifiers) && method.parameterTypes.isEmpty() &&
                    method.returnType == String::class.java
            }
            val drawerMap = drawerType.declaredFields.single { Map::class.java.isAssignableFrom(it.type) }
                .apply { isAccessible = true }
            HookEnvironment.withHookScope(HOOK_SCOPE) {
                register.hookBefore { param ->
                    param[0]?.let { install(it, keyboard, buttonType, abstractDrawer, drawerName) }
                }
                // A hot reload can encounter existing drawers. Inspect once when their canvas is set.
                drawerType.declaredMethods.single { method ->
                    method.returnType == Void.TYPE &&
                        method.parameterTypes.contentEquals(arrayOf(Canvas::class.java))
                }.hookBefore { param ->
                    synchronized(inspectedDrawers) {
                        if (inspectedDrawers.add(param.thisObject)) {
                            (drawerMap.get(param.thisObject) as? Map<*, *>)?.values?.forEach { drawer ->
                                drawer?.let { install(it, keyboard, buttonType, abstractDrawer, drawerName) }
                            }
                        }
                    }
                }
            }
            Log.i("Success: Hook WeType continuous key corners")
        }.onFailure {
            Log.e("Failed: Hook WeType continuous key corners")
            Log.e(it)
        }
    }

    private fun install(
        drawer: Any,
        keyboard: Class<*>,
        buttonType: Class<*>,
        abstractDrawer: Class<*>,
        drawerName: Method
    ) {
        synchronized(inspectedTypes) {
            val type = drawer.javaClass
            if (!inspectedTypes.add(type)) return
            runCatching {
                val signature = arrayOf(Canvas::class.java, buttonType)
                val base = generateSequence(type.superclass as Class<*>?) { it.superclass }
                    .takeWhile { it != abstractDrawer && it != Any::class.java }
                    .singleOrNull { it.superclass == abstractDrawer && Modifier.isAbstract(it.modifiers) }
                    ?: return
                val backgrounds = base.declaredMethods.filter { method ->
                    Modifier.isProtected(method.modifiers) && !Modifier.isStatic(method.modifiers) &&
                        method.returnType == Void.TYPE && method.parameterTypes.contentEquals(signature)
                }
                if (backgrounds.size != 2) return
                val baseProperties = properties(base)
                val names = baseProperties.keys
                if (!names.containsAll(listOf("bgPaint", "bgHighLightPaint", "pressMaskPaint", "borderPaint", "shadowPaint", "bgRect"))) return
                val backgroundPaints = BackgroundPaintAccess(base, baseProperties)
                HookEnvironment.withHookScope(HOOK_SCOPE) {
                    backgrounds.forEach { background ->
                        if (hookedBackgrounds.add(background)) installBackground(background, backgroundPaints)
                    }
                    if (drawerName.invoke(drawer) == "normalAlphaType") {
                        installAlphaBitmap(type, base, abstractDrawer, keyboard, buttonType)
                    }
                }
            }.onFailure {
                Log.e("Failed: Hook WeType key background ${type.name}")
                Log.e(it)
            }
        }
    }

    private fun installBackground(method: Method, backgroundPaints: BackgroundPaintAccess) {
        HookEnvironment.registerHook(method, "background") { chain ->
            val canvas = chain.args.getOrNull(0) as? Canvas ?: return@registerHook chain.proceed()
            val button = chain.args.getOrNull(1) ?: return@registerHook chain.proceed()
            val state = renderState()
            // Unknown reentrant drawing retains the original canvas instead of sharing mutable state.
            if (state.busy) return@registerHook chain.proceed()
            val paints = backgroundPaints.get(chain.thisObject!!)
            state.busy = true
            state.corners.target = canvas
            state.corners.shadowPaint = paints.first
            state.corners.borderPaint = paints.second
            state.arguments[0] = state.corners
            state.arguments[1] = button
            try {
                chain.proceed(state.arguments)
            } finally {
                state.arguments[0] = null
                state.arguments[1] = null
                state.corners.target = null
                state.corners.shadowPaint = null
                state.corners.borderPaint = null
                state.busy = false
            }
        }
    }

    private fun installAlphaBitmap(
        type: Class<*>,
        base: Class<*>,
        abstractDrawer: Class<*>,
        keyboard: Class<*>,
        buttonType: Class<*>
    ) {
        // This drawer composites its own bitmap with changeAlpha; keep that compositing pass intact.
        if (type.superclass != base || !Modifier.isFinal(type.modifiers)) return
        val instanceFields = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        if (instanceFields.count { it.type == Paint::class.java } != 3 ||
            instanceFields.count { it.type == Int::class.javaPrimitiveType } != 1) return
        val factory = type.declaredMethods.singleOrNull { method ->
            Modifier.isPrivate(method.modifiers) && !Modifier.isStatic(method.modifiers) &&
                method.returnType == Bitmap::class.java &&
                method.parameterTypes.contentEquals(arrayOf(Canvas::class.java, buttonType))
        } ?: return
        val prepareText = type.declaredMethods.singleOrNull { method ->
            !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                method.parameterTypes.contentEquals(arrayOf(buttonType, Boolean::class.javaPrimitiveType!!))
        }?.apply { isAccessible = true } ?: return
        val alpha = AlphaBitmapAccess(type, abstractDrawer, keyboard, buttonType, prepareText)
        HookEnvironment.registerHook(factory, "alpha-bitmap") { chain ->
            val button = chain.args.getOrNull(1) ?: return@registerHook chain.proceed()
            val state = renderState()
            if (state.busy) return@registerHook chain.proceed()
            state.busy = true
            val bitmap = try {
                alpha.draw(chain.thisObject!!, button, state)
            } finally {
                state.bitmapCanvas.setBitmap(null)
                state.corners.target = null
                state.busy = false
            }
            bitmap ?: chain.proceed()
        }
    }

    private class RenderState {
        val corners = WeTypeKeyCornerCanvas()
        val bitmapCanvas = Canvas()
        val metrics = Paint.FontMetrics()
        val arguments = arrayOfNulls<Any>(2)
        var busy = false
    }

    private class BackgroundPaintAccess(
        type: Class<*>,
        properties: Map<String, WeTypeKeyboardSymbols.PropertySignature>
    ) {
        private val shadow = field(type, properties, "shadowPaint", Paint::class.java)
        private val border = field(type, properties, "borderPaint", Paint::class.java)
        private val paints = WeakHashMap<Any, Pair<Paint, Paint>>()

        fun get(drawer: Any): Pair<Paint, Paint> = synchronized(paints) {
            paints.getOrPut(drawer) { (shadow.get(drawer) as Paint) to (border.get(drawer) as Paint) }
        }
    }

    private class AlphaBitmapAccess(
        type: Class<*>,
        abstractDrawer: Class<*>,
        keyboard: Class<*>,
        buttonType: Class<*>,
        private val prepareText: Method
    ) {
        private val paintFields = properties(type)
        private val buttonFields = properties(buttonType)
        private val keyboardFields = properties(keyboard)
        private val backgroundPaint = field(type, paintFields, "bgPaint", Paint::class.java)
        private val textPaint = field(type, paintFields, "textPaint", Paint::class.java)
        private val parent = abstractDrawer.declaredFields.single { it.type == keyboard }
            .apply { isAccessible = true }
        private val t9 = field(keyboard, keyboardFields, "isT9", Boolean::class.javaPrimitiveType!!)
        private val upperMode = field(keyboard, keyboardFields, "mUpperMode", Int::class.javaPrimitiveType!!)
        private val drawRect = field(buttonType, buttonFields, "drawRect", Rect::class.java)
        private val corner = field(buttonType, buttonFields, "bgCorner", Int::class.javaPrimitiveType!!)
        private val shadowHeight = field(buttonType, buttonFields, "shadowHeight", Float::class.javaPrimitiveType!!)
        private val backgroundColor = field(buttonType, buttonFields, "bgColor", Int::class.javaPrimitiveType!!)
        private val pressColor = field(buttonType, buttonFields, "pressMaskColor", Int::class.javaPrimitiveType!!)
        private val status = field(buttonType, buttonFields, "status", String::class.java)
        private val contentVisible = field(buttonType, buttonFields, "isContentVisible", Boolean::class.javaPrimitiveType!!)
        private val mainText = field(buttonType, buttonFields, "mainText", String::class.java)

        init {
            field(type, paintFields, "alphaPaint", Paint::class.java)
            field(type, paintFields, "changeAlpha", Int::class.javaPrimitiveType!!)
        }

        fun draw(drawer: Any, button: Any, state: RenderState): Bitmap? {
            val rect = drawRect.get(button) as Rect
            val keyboard = parent.get(drawer)
            val isT9 = t9.getBoolean(keyboard)
            val shadow = shadowHeight.getFloat(button)
            val radius = corner.getInt(button).toFloat()
            val width = abs(rect.width())
            val height = abs(rect.bottom + (if (isT9) shadow / 2f else shadow) - rect.top).toInt()
            if (width <= 0 || height <= 0) return null
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                state.bitmapCanvas.setBitmap(bitmap)
                state.corners.target = state.bitmapCanvas
                val canvas = state.corners
                val paint = backgroundPaint.get(drawer) as Paint
                val contentHeight = abs(rect.height()).toFloat()
                // Keep the host bitmap and fill bounds, but omit its shadow and border passes.
                val backgroundTint = backgroundColor.getInt(button)
                if (backgroundTint != 0) {
                    paint.style = Paint.Style.FILL
                    paint.color = backgroundTint
                    canvas.drawRoundRect(0f, 0f, width.toFloat(), contentHeight, radius, radius, paint)
                }
                val pressedTint = pressColor.getInt(button)
                if (pressedTint != 0 && status.get(button) == "press") {
                    paint.style = Paint.Style.FILL
                    paint.color = pressedTint
                    canvas.drawRoundRect(0f, 0f, width.toFloat(), contentHeight, radius, radius, paint)
                }
                if (contentVisible.getBoolean(button)) {
                    val mode = upperMode.getInt(keyboard)
                    // The host treats modes 1 and 5 as lowercase; all other modes use uppercase sizing.
                    prepareText.invoke(drawer, button, mode != 1 && mode != 5)
                    val text = textPaint.get(drawer) as Paint
                    text.getFontMetrics(state.metrics)
                    val metrics = state.metrics
                    val baseline = contentHeight / 2f - (metrics.descent - metrics.ascent) / 2f +
                        metrics.leading - metrics.ascent
                    state.bitmapCanvas.drawText(mainText.get(button) as String, width / 2f, baseline, text)
                }
                return bitmap
            } catch (failure: Throwable) {
                state.bitmapCanvas.setBitmap(null)
                bitmap.recycle()
                throw failure
            }
        }
    }

    private fun properties(type: Class<*>): Map<String, WeTypeKeyboardSymbols.PropertySignature> {
        val metadata = type.declaredAnnotations.firstOrNull { it.annotationClass.java.name == "kotlin.Metadata" }
            ?: error("Keyboard metadata missing")
        @Suppress("UNCHECKED_CAST")
        val data = metadata.annotationClass.java.getMethod("d1").invoke(metadata) as Array<String>
        @Suppress("UNCHECKED_CAST")
        val strings = metadata.annotationClass.java.getMethod("d2").invoke(metadata) as Array<String>
        return WeTypeKeyboardSymbols.decodePropertySignatures(data, strings)
    }

    private fun field(
        type: Class<*>,
        properties: Map<String, WeTypeKeyboardSymbols.PropertySignature>,
        name: String,
        fieldType: Class<*>
    ): Field {
        val signature = properties[name] ?: error("Keyboard property missing: $name")
        return type.declaredFields.single { it.name == signature.field && it.type == fieldType }
            .apply { isAccessible = true }
    }
}

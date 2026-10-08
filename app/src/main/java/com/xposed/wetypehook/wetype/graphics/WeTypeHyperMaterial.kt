package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Point
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.xposed.wetypehook.wetype.settings.GlassMaterialOverrides
import com.xposed.wetypehook.PropertyUtils
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.HookEnvironment
import com.xposed.wetypehook.xposed.hookBefore
import java.lang.reflect.Method

/** System View material used by XiaoAI IME 0.2.910's HyperMaterialHelper. */
internal class WeTypeHyperMaterial(
    private val view: View,
    overrides: GlassMaterialOverrides = GlassMaterialOverrides(),
    private val sampleBehindWindow: Boolean = true
) {
    private val overrides = overrides.forLiquidGlass()
    private data class MaterialStyle(val isDark: Boolean, val density: Float, val tintColor: Int?)

    // One native optical layer avoids independently sampled/tinted regions.
    // Its radius follows the custom top corners; the parent retains all four G2 clips.
    private val usesGlassSurfaces = this.overrides.glass != null && this.overrides.materialType == 1
    private val glassSurface: View? = if (usesGlassSurfaces) {
        check(view is ViewGroup)
        View(view.context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            view.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        }
    } else null
    private val materialView = glassSurface ?: view
    private var glassGeometry: Triple<Int, Int, WeTypeCornerRadii>? = null
    private var appliedStyle: MaterialStyle? = null
    private var pendingWindowType: Runnable? = null
    private var pendingTintClear: Runnable? = null
    private var shadowView: View? = null
    private var shader: RuntimeShader? = null
    private var shadowGeometry: Triple<Int, Int, WeTypeCornerRadii>? = null
    private var shadowStyle: MaterialStyle? = null

    fun apply(isDark: Boolean, tintColor: Int? = null): Boolean {
        if (!isAvailable(view.context)) {
            clear()
            return false
        }
        val density = view.resources.displayMetrics.density
        val style = MaterialStyle(isDark, density, tintColor.takeIf { usesGlassSurfaces })
        if (appliedStyle == style) return true
        clear()
        return runCatching {
            val api = checkNotNull(api)
            run {
                materialView.setBackgroundColor(if (supportsOffScreenFill) Color.TRANSPARENT else fallbackColor(isDark))
                api.setPassWindowBlurEnabled(materialView, sampleBehindWindow)
                if (supportsOffScreenFill) api.call(materialView, "setMiBlurWinType", 65536)
                // keyboard/frosted, token version 30: background mode 1, view mode 1,
                // blur type 0, radius 40dp. Its shadow and bloom sections are absent.
                api.call(materialView, "setMixEffectEnabled", false)
                api.call(materialView, "setMiBackgroundBlurMode", 1)
                api.call(materialView, "setMiBackgroundBlurRadius", (40f * density + 0.5f).toInt().coerceIn(0, 400))
                api.call(materialView, "setMiViewBlurMode", 1)
                val colors = if (usesGlassSurfaces) {
                    intArrayOf()
                } else if (isDark) {
                    intArrayOf(-428838800, -1726737388, 262385602)
                } else {
                    intArrayOf(-2130706433, 1728053247, 1722132652)
                }
                val modes = if (isDark) intArrayOf(15, 3, 3) else intArrayOf(121, 3, 3)
                api.call(materialView, "setMiBackgroundBlendColors", ArrayList(colors.indices.map { Point(colors[it], modes[it]) }))
                api.call(materialView, "setMiBackgroundBlurType", 0)
                if (areGlassOverridesAvailable()) {
                    // Raw array values retain the framework units. No density conversion.
                    overrides.glassWithTint(style.tintColor)?.let { glassApi!!.getValue("setMiGlass").invoke(materialView, it.toFloatArray()) }
                    overrides.blurRadii?.let { glassApi!!.getValue("setMiGlassBlurRadius").invoke(materialView, it[0], it[1]) }
                    overrides.bloom?.let { glassApi!!.getValue("setGlassBloom").invoke(materialView, it.toFloatArray()) }
                    overrides.materialType?.let { glassApi!!.getValue("setMiViewMaterialType").invoke(materialView, it) }
                }
            }
            appliedStyle = style
            if (!supportsOffScreenFill) {
                pendingTintClear = Runnable {
                    pendingTintClear = null
                    if (appliedStyle != null) materialView.setBackgroundColor(Color.TRANSPARENT)
                }.also { view.postDelayed(it, 20L) }
            }
            if (supportsOffScreenFill) {
                pendingWindowType = Runnable {
                    pendingWindowType = null
                    if (appliedStyle != null && view.isAttachedToWindow) {
                        runCatching { api.call(materialView, "setMiBlurWinType", 1) }
                            .onFailure {
                                clear()
                                view.setBackgroundColor(fallbackColor(isDark))
                                Log.e(it)
                            }
                    }
                }.also { view.postDelayed(it, 500L) }
            }
            true
        }.getOrElse {
            // Never leave a partially configured compositor effect on the carrier.
            clear(force = true)
            Log.e(it)
            false
        }
    }

    fun updateGeometry(cornerRadii: WeTypeCornerRadii) {
        if (Build.VERSION.SDK_INT < 33) return
        val style = appliedStyle ?: return
        val parent = view.parent as? ViewGroup ?: return
        if (view.width <= 0 || view.height <= 0) return
        runCatching {
            updateGlassGeometry(cornerRadii)
            val effectView = shadowView ?: View(view.context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                // A sibling outside the clipped frosted panel, as in HyperMaterialHelper.
                parent.addView(this, parent.indexOfChild(view) + 1, FrameLayout.LayoutParams(0, 0))
            }.also { shadowView = it }
            val density = style.density
            val outset = (60f * density + 0.5f).toInt()
            val width = view.width + outset * 2
            val height = view.height + outset * 2
            if (effectView.isLayoutRequested || effectView.width != width || effectView.height != height ||
                effectView.left != view.left - outset || effectView.top != view.top - outset) {
                effectView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                effectView.layout(view.left - outset, view.top - outset, view.right + outset, view.bottom + outset)
            }
            val geometry = Triple(view.width, view.height, cornerRadii)
            if (shadowGeometry != geometry || shadowStyle != style) {
                val shader = shader ?: RuntimeShader(HYPER_MATERIAL_SHADOW_SHADER).also { shader = it }
                val path = createWeTypeContinuousRoundedPath(view.width.toFloat(), view.height.toFloat(), cornerRadii)
                // Start at quarter-pixel precision; bound the uniform array for unusually complex paths.
                var error = 0.25f
                var points = path.approximate(error)
                while (points.size / 3 > 255) {
                    error *= 2f
                    points = path.approximate(error)
                }
                val count = points.size / 3
                val contour = FloatArray(256 * 2)
                for (index in 0 until count) {
                    contour[index * 2] = points[index * 3 + 1]
                    contour[index * 2 + 1] = points[index * 3 + 2]
                }
                contour[count * 2] = contour[0]
                contour[count * 2 + 1] = contour[1]
                shader.setFloatUniform("uContour", contour)
                shader.setIntUniform("uPointCount", count + 1)
                shader.setFloatUniform("uCornerExtent", cornerRadii.maxRadius() * 2f)
                shader.setFloatUniform("uResolution", width.toFloat(), height.toFloat())
                shader.setFloatUniform("uStrokeWidth", density)
                shader.setFloatUniform("uOutset", outset.toFloat())
                val shadowColor = if (style.isDark) 340281416 else 134217728
                shader.setFloatUniform("uShadowColor", Color.red(shadowColor) / 255f,
                    Color.green(shadowColor) / 255f, Color.blue(shadowColor) / 255f)
                shader.setFloatUniform("uShadowAlpha", Color.alpha(shadowColor) / 255f)
                shader.setFloatUniform("uShadowRadius", minOf((if (style.isDark) 60f else 40f) * density, outset.toFloat()))
                shader.setFloatUniform("uStrokeAlphaTop", if (style.isDark) 0.12156863f else 0.59607846f)
                shader.setFloatUniform("uStrokeAlphaBottom", 0.050980393f)
                effectView.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "uInputContent"))
                shadowGeometry = geometry
                shadowStyle = style
            }
            effectView.visibility = view.visibility
        }.onFailure {
            clear()
            view.setBackgroundColor(fallbackColor(style.isDark))
            Log.e(it)
        }
    }

    private fun updateGlassGeometry(corners: WeTypeCornerRadii) {
        val surface = glassSurface ?: return
        val geometry = Triple(view.width, view.height, corners)
        val optical = resolveGlassSurfaceGeometry(view.width, view.height, corners, sampleBehindWindow)
        if (glassGeometry == geometry && surface.left == -optical.outset && surface.top == 0 &&
            surface.width == optical.width && surface.height == optical.height) return
        // No region wrappers or internal clipBounds: the compositor samples one
        // continuous surface, including when top and hardware bottom radii differ.
        surface.measure(View.MeasureSpec.makeMeasureSpec(optical.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(optical.height, View.MeasureSpec.EXACTLY))
        surface.layout(-optical.outset, 0, view.width + optical.outset, optical.height)
        surface.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: Outline) {
                outline.setRoundRect(0, 0, target.width, target.height, optical.radius)
            }
        }
        surface.clipToOutline = false
        surface.invalidateOutline()
        val params = checkNotNull(overrides.glassWithTint(checkNotNull(appliedStyle).tintColor)).toMutableList()
        params[19] = params[19].coerceIn(0.1f, optical.maxEdgeDepth)
        glassApi!!.getValue("setMiGlass").invoke(surface, params.toFloatArray())
        surface.invalidate()
        glassGeometry = geometry
    }

    fun clear(force: Boolean = false) {
        pendingTintClear?.let(view::removeCallbacks)
        pendingTintClear = null
        shadowView?.let {
            it.setRenderEffect(null)
            (it.parent as? ViewGroup)?.removeView(it)
        }
        shadowView = null
        glassGeometry = null
        shadowGeometry = null
        shadowStyle = null
        pendingWindowType?.let(view::removeCallbacks)
        pendingWindowType = null
        if (appliedStyle == null && !force) return
        appliedStyle = null
        val api = api ?: return
        run {
            listOf(
                "setMiBackgroundBlurMode" to 0,
                "setMiViewBlurMode" to 0,
                "setMiBackgroundBlurRadius" to 0,
                "setMiBackgroundBlurType" to 0,
                "setMixEffectEnabled" to false
            ).forEach { (name, value) -> runCatching { api.call(materialView, name, value) } }
            runCatching { api.setPassWindowBlurEnabled(materialView, false) }
            runCatching { api.call(materialView, "clearMiBackgroundBlendColor") }
            if (supportsOffScreenFill) runCatching { api.call(materialView, "setMiBlurWinType", 0) }
            materialView.invalidate()
        }
    }

    companion object {
        private const val BLUR_SETTING = "background_blur_enable"
        private val osVersion by lazy { PropertyUtils["ro.mi.os.version.code", "0"]?.toIntOrNull() ?: 0 }
        private val visualVersion by lazy {
            val property = if (osVersion > 1) "persist.sys.advanced_visual_release" else "persist.sys.background_blur_version"
            PropertyUtils[property, "-1"]?.toIntOrNull() ?: -1
        }
        private val supportsOffScreenFill get() = visualVersion >= 6
        private val api: MaterialApi? by lazy { runCatching { MaterialApi(supportsOffScreenFill) }.getOrNull() }
        private val shaderSupported by lazy {
            Build.VERSION.SDK_INT >= 33 && runCatching {
                RuntimeShader(HYPER_MATERIAL_SHADOW_SHADER)
                true
            }.getOrDefault(false)
        }
        private val blurSupported by lazy {
            PropertyUtils["persist.sys.background_blur_supported", "false"].toBoolean()
        }

        private val glassApi: Map<String, Method>? by lazy {
            runCatching {
                mapOf(
                    "setMiGlass" to View::class.java.getMethod("setMiGlass", FloatArray::class.java),
                    "setMiGlassBlurRadius" to View::class.java.getMethod("setMiGlassBlurRadius", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
                    "setGlassBloom" to View::class.java.getMethod("setGlassBloom", FloatArray::class.java),
                    "setMiViewMaterialType" to View::class.java.getMethod("setMiViewMaterialType", Int::class.javaPrimitiveType)
                )
            }.getOrNull()
        }
        private val bionicSupported by lazy {
            PropertyUtils["persist.sys.bionic_material_supported", "false"].toBoolean()
        }

        fun areGlassOverridesAvailable(): Boolean = bionicSupported && visualVersion >= 4 && glassApi != null

        fun fallbackColor(isDark: Boolean): Int = if (isDark) 0xFF18191B.toInt() else 0xFFE5E6E7.toInt()

        fun isAvailable(context: Context): Boolean = runCatching {
            // The source selects this token's blend colors starting at OS 3. Bionic
            // glass and gradient blur defaults do not gate the frosted keyboard token.
            Build.VERSION.SDK_INT >= 33 && osVersion >= 3 && blurSupported &&
                Settings.Secure.getInt(context.contentResolver, BLUR_SETTING, 0) == 1 &&
                api != null && shaderSupported
        }.getOrDefault(false)

        fun observeAvailability(context: Context, onChanged: () -> Unit): () -> Unit {
            val resolver = context.contentResolver
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = onChanged()
            }
            resolver.registerContentObserver(Settings.Secure.getUriFor(BLUR_SETTING), false, observer)
            return { resolver.unregisterContentObserver(observer) }
        }
    }

    private class MaterialApi(offScreenFill: Boolean) {
        private val passWindowBlurScope = PassWindowBlurScope()
        private val modernAdmission by lazy {
            val stubClass = Class.forName("android.view.ViewRootImplStub", true, View::class.java.classLoader)
            val stub = checkNotNull(stubClass.getMethod("getInstance").invoke(null))
            stub.javaClass.getMethod("isPassWindowBlurAllowed", String::class.java).hookBefore { param ->
                if (passWindowBlurScope.allows(param.argumentOrNull(0) as? String)) param.result = true
            }
            true
        }

        private val methods = buildMap<String, Method> {
            listOf("setMiBackgroundBlurMode", "setMiBackgroundBlurRadius", "setMiViewBlurMode", "setMiBackgroundBlurType")
                .forEach { put(it, View::class.java.getMethod(it, Int::class.javaPrimitiveType)) }
            listOf("setPassWindowBlurEnabled", "setMixEffectEnabled")
                .forEach { put(it, View::class.java.getMethod(it, Boolean::class.javaPrimitiveType)) }
            put("setMiBackgroundBlendColors", View::class.java.getMethod("setMiBackgroundBlendColors", ArrayList::class.java))
            put("clearMiBackgroundBlendColor", View::class.java.getMethod("clearMiBackgroundBlendColor"))
            if (offScreenFill) put("setMiBlurWinType", View::class.java.getMethod("setMiBlurWinType", Int::class.javaPrimitiveType))
        }

        fun setPassWindowBlurEnabled(view: View, enabled: Boolean) {
            val root = View::class.java.getMethod("getViewRootImpl").invoke(view)
            checkNotNull(root) { "Material carrier must be attached before enabling pass-window blur" }
            val filterField = runCatching {
                root.javaClass.getDeclaredField("mPassWindowBlurFilterData").apply { isAccessible = true }
            }.getOrNull()
            val originalFilter = filterField?.get(root) as? String
            // A module-themed Context can belong to a different package than its host window.
            // Match View.setPassWindowBlurEnabled's actual identity check on this ROM.
            val rootContext = root.javaClass.getDeclaredField("mContext").apply { isAccessible = true }
                .get(root) as Context
            val packageName = Context::class.java.getMethod("getBasePackageName").invoke(rootContext) as String
            // Older ROMs keep admission on the ViewRoot. Newer ROMs delegate to the
            // framework stub; override only the synchronous call from this carrier,
            // for this host package and thread. Keep ROM/cloud admission data intact.
            val needsAdmission = !originalFilter.isNullOrEmpty() && !originalFilter.contains(packageName)
            try {
                if (needsAdmission) filterField?.set(root, "$originalFilter $packageName")
                if (filterField == null && packageName == "com.tencent.wetype" &&
                    HookEnvironment.moduleOrNull() != null) {
                    check(modernAdmission)
                    passWindowBlurScope.withPackage(packageName) {
                        call(view, "setPassWindowBlurEnabled", enabled)
                    }
                } else {
                    call(view, "setPassWindowBlurEnabled", enabled)
                }
                // The boolean return also means "already set", so inspect actual state.
                val state = View::class.java.getDeclaredField("mNeedPassWindowBlur").apply { isAccessible = true }
                check(state.getBoolean(view) == enabled) { "System rejected pass-window blur state" }
            } finally {
                if (needsAdmission) filterField?.set(root, originalFilter)
            }
        }

        fun call(view: View, name: String, vararg args: Any) {
            methods.getValue(name).invoke(view, *args)
        }
    }
}

/** Admission must never leak to another package, call, or rendering thread. */
internal class PassWindowBlurScope {
    private val packageName = ThreadLocal<String>()

    fun allows(candidate: String?): Boolean = candidate != null && candidate == packageName.get()

    fun <T> withPackage(candidate: String, block: () -> T): T {
        val previous = packageName.get()
        packageName.set(candidate)
        return try {
            block()
        } finally {
            if (previous == null) packageName.remove() else packageName.set(previous)
        }
    }
}

package com.xposed.wetypehook.wetype.graphics

import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import com.xposed.wetypehook.wetype.settings.DARK_KEY_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.LIGHT_KEY_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.WeTypeAppearanceColorGroups
import java.util.WeakHashMap

internal object WeTypeColorOsMaterial {
    data class Effects(val blur: Boolean = false, val stroke: Boolean = false)

    private const val BLUR_SETTING = "system_material_blur_enable"
    private const val STROKE_SETTING = "system_material_stroke_enable"
    private const val COLOR_MIX = 1
    private const val FAST_KAWASE = 2
    private const val SMOOTH_CORNERS = 1
    const val BACKGROUND_BLUR_RADIUS = 150

    private val blurApi by lazy { runCatching { BlurApi() }.getOrNull() }

    val isSupported: Boolean
        get() = blurApi != null

    fun readEffects(context: Context): Effects {
        if (!isSupported) return Effects()
        val resolver = context.contentResolver
        return Effects(
            blur = runCatching { Settings.System.getInt(resolver, BLUR_SETTING, 0) == 1 }.getOrDefault(false),
            stroke = runCatching { Settings.System.getInt(resolver, STROKE_SETTING, 0) == 1 }.getOrDefault(false)
        )
    }

    fun observeEffects(context: Context, onChanged: () -> Unit): (() -> Unit)? {
        if (!isSupported) return null
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChanged()
        }
        fun stop() {
            runCatching { resolver.unregisterContentObserver(observer) }
        }
        return runCatching<() -> Unit> {
            resolver.registerContentObserver(Settings.System.getUriFor(BLUR_SETTING), false, observer)
            resolver.registerContentObserver(Settings.System.getUriFor(STROKE_SETTING), false, observer)
            ::stop
        }.onFailure { stop() }.getOrNull()
    }

    fun backgroundTint(isDark: Boolean): Int = if (isDark) 0x990D0D0D.toInt() else 0xCCE1E1E1.toInt()

    private val lightKeyDefault by lazy { defaultKeyColor(LIGHT_KEY_COLOR_GROUP_ID) }
    private val darkKeyDefault by lazy { defaultKeyColor(DARK_KEY_COLOR_GROUP_ID) }

    private fun defaultKeyColor(id: String): Int = WeTypeAppearanceColorGroups.groups.first { it.id == id }.defaultColor

    fun keyFill(isDark: Boolean, configuredColor: Int): Int = when {
        isDark && configuredColor == darkKeyDefault -> 0x26FFFFFF
        !isDark && configuredColor == lightKeyDefault -> 0x8AFFFFFF.toInt()
        else -> configuredColor
    }

    fun createBackground(view: View, isDark: Boolean, cornerRadii: WeTypeCornerRadii): Drawable? {
        if (!view.isAttachedToWindow) return null
        return blurApi?.createBackground(view, isDark, cornerRadii)
    }

    /** Draws the directional rim inside the caller's original contour on either kind of Canvas. */
    class KeyContour {
        private val gradients = WeakHashMap<Path, LinearGradient>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        fun draw(
            canvas: Canvas,
            path: Path,
            left: Float,
            top: Float,
            width: Float,
            height: Float,
            isDark: Boolean
        ) {
            if (width <= 0f || height <= 0f) return
            paint.shader = gradients.getOrPut(path) {
                val center = left + width / 2f
                val tilt = minOf(width, height) * 0.035f
                LinearGradient(
                    center - tilt, top, center + tilt, top + height,
                    RIM_COLORS, RIM_STOPS, Shader.TileMode.CLAMP
                )
            }
            val checkpoint = canvas.save()
            try {
                canvas.clipPath(path)
                // The wider passes supply the soft falloff. The thinnest pass carries the rim.
                val opacity = if (isDark) 0.40f else 1f
                for (index in RIM_WIDTHS.indices) {
                    paint.strokeWidth = RIM_WIDTHS[index]
                    paint.alpha = (RIM_ALPHAS[index] * opacity).toInt()
                    canvas.drawPath(path, paint)
                }
            } finally {
                canvas.restoreToCount(checkpoint)
            }
        }

        private companion object {
            val RIM_STOPS = floatArrayOf(0f, 0.20f, 0.49f, 0.79f, 1f)
            val RIM_COLORS = intArrayOf(
                0x9AFFFFFF.toInt(), 0x5CFFFFFF, 0x39FFFFFF, 0x6BFFFFFF, 0xB8FFFFFF.toInt()
            )
            val RIM_WIDTHS = floatArrayOf(10f, 5f, 2.4f)
            val RIM_ALPHAS = intArrayOf(20, 46, 150)
        }
    }

    private class BlurApi {
        private val managerClass = frameworkClass("com.oplus.view.ViewRootManager")
        private val paramsClass = frameworkClass("com.oplus.graphics.OplusBlurParam")
        private val managerConstructor = managerClass.getConstructor(View::class.java)
        private val paramsConstructor = paramsClass.getConstructor()
        private val getDrawable = managerClass.getMethod("getBackgroundBlurDrawable")
        private val setBlurRadius = managerClass.getMethod("setBlurRadius", Int::class.javaPrimitiveType)
        private val setCornerRadius = managerClass.getMethod(
            "setCornerRadius",
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType
        )
        private val setBlurParams = managerClass.getMethod("setBlurParams", paramsClass)
        private val setMaterialParams = paramsClass.getMethod(
            "setMaterialParams",
            Int::class.javaPrimitiveType,
            FloatArray::class.java,
            FloatArray::class.java
        )
        private val setBlurType = paramsClass.getMethod("setBlurType", Int::class.javaPrimitiveType)
        private val setSmoothCornerType = paramsClass.getMethod("setSmoothCornerType", Int::class.javaPrimitiveType)
        private val setSmoothCornerWeight = paramsClass.getMethod("setSmoothCornerWeight", Float::class.javaPrimitiveType)

        fun createBackground(view: View, isDark: Boolean, corners: WeTypeCornerRadii): Drawable? = runCatching {
            val manager = managerConstructor.newInstance(view)
            val drawable = getDrawable.invoke(manager) as? Drawable ?: return null
            val params = paramsConstructor.newInstance()
            val tint = backgroundTint(isDark)
            val value = Color.red(tint) / 255f
            val alpha = Color.alpha(tint) / 255f
            setMaterialParams.invoke(params, COLOR_MIX, floatArrayOf(1f, 1f, 1f, 1f), floatArrayOf(value, value, value, alpha))
            setBlurType.invoke(params, FAST_KAWASE)
            setSmoothCornerType.invoke(params, SMOOTH_CORNERS)
            setSmoothCornerWeight.invoke(params, 3f)
            setBlurRadius.invoke(manager, BACKGROUND_BLUR_RADIUS)
            setCornerRadius.invoke(manager, corners.topLeft, corners.topRight, corners.bottomLeft, corners.bottomRight)
            setBlurParams.invoke(manager, params)
            drawable
        }.getOrNull()
    }

    private fun frameworkClass(name: String): Class<*> = Class.forName(name, false, View::class.java.classLoader)
}

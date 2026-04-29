package com.xposed.wetypehook.wetype.hook

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.annotation.FloatRange
import androidx.core.graphics.PathParser
import com.xposed.wetypehook.wetype.settings.WeTypeSettings

internal class WeTypeIconDrawable(
    @FloatRange(from = 0.0, to = 1.0)
    private val backgroundAlphaFraction: Float
) : Drawable() {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return

        val userOpacity = WeTypeSettings.getToolbarIconBgOpacityXposed()
        val bgAlpha = (userOpacity * backgroundAlphaFraction).toInt().coerceIn(0, 255)

        accentPaint.color = WeTypeSettings.getAppearanceColorXposed("theme_color")
        backgroundPaint.alpha = resolveAlpha(bgAlpha)
        accentPaint.alpha = resolveAlpha(Color.alpha(accentPaint.color))

        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / VIEWPORT_SIZE, bounds.height() / VIEWPORT_SIZE)
        canvas.drawPath(BACKGROUND_PATH, backgroundPaint)
        canvas.drawPath(ACCENT_PATH, accentPaint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        backgroundPaint.colorFilter = colorFilter
        accentPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = VIEWPORT_SIZE.toInt()

    override fun getIntrinsicHeight(): Int = VIEWPORT_SIZE.toInt()

    private fun resolveAlpha(sourceAlpha: Int): Int = (sourceAlpha * drawableAlpha) / 255

    private var drawableAlpha: Int = 255

    companion object {
        private const val VIEWPORT_SIZE = 96f
        private const val BACKGROUND_PATH_DATA =
            "M48,48m-48,0a48,48 0,1 1,96 0a48,48 0,1 1,-96 0"
        private const val ACCENT_PATH_DATA =
            "M61.772,26.55C60.567,26.55 59.509,27.352 59.183,28.513L58.002,32.722C57.745,33.642 58.436,34.553 59.39,34.553V34.553C65.453,34.553 68.414,36.495 66.874,41.988C66.864,42.022 66.855,42.055 66.845,42.089C65.693,46.084 61.195,49.212 57.015,49.212H48.094L50.618,40.206L52.204,34.553L53.491,29.965C53.972,28.25 52.682,26.55 50.901,26.55H45.901C44.695,26.55 43.637,27.352 43.311,28.513L37.507,49.212H23.897C22.691,49.212 21.633,50.014 21.307,51.175L20.308,54.737C19.827,56.452 21.116,58.153 22.897,58.153H35L31.268,71.457C30.787,73.173 32.076,74.873 33.857,74.873H38.858C40.063,74.873 41.121,74.071 41.447,72.91L45.586,58.153H55.05H55.328C66.079,57.67 75.078,50.863 76.542,42.042C77.944,33.593 74.674,27.024 64.438,26.55H61.772Z"

        private val BACKGROUND_PATH: Path =
            requireNotNull(PathParser.createPathFromPathData(BACKGROUND_PATH_DATA))
        private val ACCENT_PATH: Path =
            requireNotNull(PathParser.createPathFromPathData(ACCENT_PATH_DATA)).apply {
                fillType = Path.FillType.EVEN_ODD
            }
    }
}

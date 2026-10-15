package com.xposed.wetypehook.wetype.graphics

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Transparent resize feedback; the host ImageView still owns visibility and animation. */
internal class WeTypeFloatingResizeHandlesDrawable : Drawable() {
    private val bodyBounds = Rect()
    private val handles = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFF07C160.toInt() // Native WeType Brand green.
    }
    private var bodyRadius = -1f

    fun updateGeometry(bounds: Rect, radius: Float, strokeWidth: Float) {
        if (bodyBounds == bounds && bodyRadius == radius && paint.strokeWidth == strokeWidth) return
        bodyBounds.set(bounds)
        bodyRadius = radius
        paint.strokeWidth = strokeWidth
        rebuildHandles()
        invalidateSelf()
    }

    private fun rebuildHandles() {
        handles.reset()
        val body = RectF(bodyBounds).apply { inset(paint.strokeWidth / 2f, paint.strokeWidth / 2f) }
        if (body.isEmpty) return
        val radius = (bodyRadius - paint.strokeWidth / 2f).coerceIn(
            0f, minOf(body.width(), body.height()) / 2f
        )
        if (radius <= 0f) return
        val diameter = radius * 2f
        handles.addArc(RectF(body.left, body.top, body.left + diameter, body.top + diameter), 180f, 90f)
        handles.addArc(RectF(body.right - diameter, body.top, body.right, body.top + diameter), 270f, 90f)
        handles.addArc(RectF(body.right - diameter, body.bottom - diameter, body.right, body.bottom), 0f, 90f)
        handles.addArc(RectF(body.left, body.bottom - diameter, body.left + diameter, body.bottom), 90f, 90f)
    }

    override fun draw(canvas: Canvas) {
        if (handles.isEmpty || paint.alpha == 0) return
        val saveCount = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.drawPath(handles, paint)
        canvas.restoreToCount(saveCount)
    }

    override fun setAlpha(alpha: Int) {
        val value = alpha.coerceIn(0, 255)
        if (paint.alpha == value) return
        paint.alpha = value
        invalidateSelf()
    }

    override fun getAlpha(): Int = paint.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

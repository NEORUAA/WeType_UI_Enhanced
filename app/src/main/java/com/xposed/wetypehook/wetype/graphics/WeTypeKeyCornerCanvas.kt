package com.xposed.wetypehook.wetype.graphics

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

/** Used only for the host's background passes, never as a View's general drawing canvas. */
internal class WeTypeKeyCornerCanvas : Canvas() {
    var target: Canvas? = null
    var shadowPaint: Paint? = null
    var borderPaint: Paint? = null
    private val paths = PositionedPathCache()

    override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
        drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, rx, ry, paint)
    }

    override fun drawRoundRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rx: Float,
        ry: Float,
        paint: Paint
    ) {
        val canvas = checkNotNull(target)
        if (paint === shadowPaint || paint === borderPaint) return
        val width = right - left
        val height = bottom - top
        if (paint.shader != null || rx != ry || rx <= 0f || width <= 0f || height <= 0f ||
            !left.isFinite() || !top.isFinite() || !width.isFinite() ||
            !height.isFinite() || !rx.isFinite()) {
            canvas.drawRoundRect(left, top, right, bottom, rx, ry, paint)
            return
        }
        val positionedPath = paths.getOrNull(left, top, width, height, rx)
        if (positionedPath != null) {
            canvas.drawPath(positionedPath, paint)
            return
        }
        val path = paths.getGeometry(width, height, rx)
        val checkpoint = canvas.save()
        try {
            canvas.translate(left, top)
            canvas.drawPath(path, paint)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }

    // Canvas queries this virtual method from its constructor, before a target can be attached.
    override fun isHardwareAccelerated(): Boolean = target?.isHardwareAccelerated ?: false
    override fun getWidth(): Int = checkNotNull(target).width
    override fun getHeight(): Int = checkNotNull(target).height
    override fun getDensity(): Int = checkNotNull(target).density
    override fun getSaveCount(): Int = checkNotNull(target).saveCount
    override fun save(): Int = checkNotNull(target).save()
    override fun restore() = checkNotNull(target).restore()
    override fun restoreToCount(saveCount: Int) = checkNotNull(target).restoreToCount(saveCount)
    override fun translate(dx: Float, dy: Float) = checkNotNull(target).translate(dx, dy)
    override fun scale(sx: Float, sy: Float) = checkNotNull(target).scale(sx, sy)
    override fun rotate(degrees: Float) = checkNotNull(target).rotate(degrees)
    override fun concat(matrix: Matrix?) = checkNotNull(target).concat(matrix)
    override fun clipRect(rect: Rect): Boolean = checkNotNull(target).clipRect(rect)
    override fun clipRect(rect: RectF): Boolean = checkNotNull(target).clipRect(rect)
    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean =
        checkNotNull(target).clipRect(left, top, right, bottom)
    override fun clipPath(path: Path): Boolean = checkNotNull(target).clipPath(path)
    override fun drawRect(rect: Rect, paint: Paint) = checkNotNull(target).drawRect(rect, paint)
    override fun drawRect(rect: RectF, paint: Paint) = checkNotNull(target).drawRect(rect, paint)
    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) =
        checkNotNull(target).drawRect(left, top, right, bottom, paint)
    override fun drawPath(path: Path, paint: Paint) = checkNotNull(target).drawPath(path, paint)

    /** Stable key positions avoid repeating Canvas state changes for every background pass. */
    private class PositionedPathCache {
        private val geometry = KeyPathCache()
        private val lefts = FloatArray(CAPACITY)
        private val tops = FloatArray(CAPACITY)
        private val widths = FloatArray(CAPACITY)
        private val heights = FloatArray(CAPACITY)
        private val radii = FloatArray(CAPACITY)
        private val paths = arrayOfNulls<Path>(CAPACITY)
        private var count = 0

        fun getOrNull(left: Float, top: Float, width: Float, height: Float, radius: Float): Path? {
            for (index in 0 until count) {
                if (lefts[index] == left && tops[index] == top && widths[index] == width &&
                    heights[index] == height && radii[index] == radius) return paths[index]!!
            }
            // Keep admitted positions stable; overflow reuses geometry with a canvas translation.
            if (count == CAPACITY) return null
            // Copy cached geometry once; never mutate a path already recorded by the host canvas.
            val path = Path(geometry.get(width, height, radius)).apply { offset(left, top) }
            lefts[count] = left
            tops[count] = top
            widths[count] = width
            heights[count] = height
            radii[count] = radius
            paths[count] = path
            count++
            return path
        }

        fun getGeometry(width: Float, height: Float, radius: Float): Path =
            geometry.get(width, height, radius)

        private companion object {
            const val CAPACITY = 128
        }
    }

    /** Key widths interleave, so retaining only the previous path would rebuild every frame. */
    private class KeyPathCache {
        private val widths = FloatArray(CAPACITY)
        private val heights = FloatArray(CAPACITY)
        private val radii = FloatArray(CAPACITY)
        private val paths = arrayOfNulls<Path>(CAPACITY)
        private var count = 0
        private var next = 0

        fun get(width: Float, height: Float, radius: Float): Path {
            for (index in 0 until count) {
                if (widths[index] == width && heights[index] == height && radii[index] == radius) {
                    return paths[index]!!
                }
            }
            val path = createWeTypeContinuousRoundedPath(width, height, radius)
            widths[next] = width
            heights[next] = height
            radii[next] = radius
            paths[next] = path
            next = (next + 1) % CAPACITY
            if (count < CAPACITY) count++
            return path
        }

        private companion object {
            const val CAPACITY = 64
        }
    }
}

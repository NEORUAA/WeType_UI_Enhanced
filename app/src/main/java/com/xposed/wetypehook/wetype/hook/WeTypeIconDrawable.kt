package com.xposed.wetypehook.wetype.hook

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.annotation.FloatRange
import androidx.core.graphics.PathParser
import com.xposed.wetypehook.wetype.graphics.SvgPathImporter
import com.xposed.wetypehook.wetype.graphics.SvgPathNormalizer
import com.xposed.wetypehook.wetype.settings.ICON_COLOR_GROUP_ID
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

        accentPaint.color = WeTypeSettings.getAppearanceColorXposed(ICON_COLOR_GROUP_ID)
        backgroundPaint.alpha = resolveAlpha(bgAlpha)
        accentPaint.alpha = resolveAlpha(Color.alpha(accentPaint.color))

        canvas.save()
        // 只在绘制阶段缩放并居中，不改 intrinsicWidth/Height：图标区尺寸由布局决定，
        // 若两处同时缩放会与固定尺寸 ImageView 叠乘成二次缩放。
        val iconScale = WeTypeSettings.getIconScaleXposed()
        canvas.translate(
            bounds.left + bounds.width() * (1f - iconScale) / 2f,
            bounds.top + bounds.height() * (1f - iconScale) / 2f
        )
        canvas.scale(
            bounds.width() * iconScale / VIEWPORT_SIZE,
            bounds.height() * iconScale / VIEWPORT_SIZE
        )
        canvas.drawPath(BACKGROUND_PATH, backgroundPaint)
        accentPaths().forEach { canvas.drawPath(it, accentPaint) }
        canvas.restore()
    }

    /**
     * 返回当前生效的前景 path 列表。按原始字符串做缓存键，只在配置变化时重新解析，
     * 这样每帧 draw 只是一次 volatile 读；解析结果为空时回退内置徽标，避免图标消失。
     */
    private fun accentPaths(): List<Path> {
        val source = WeTypeSettings.getCustomIconPathXposed()
        if (source == accentCache.source) return accentCache.paths
        val parsed = buildAccentPaths(source)
        accentCache = ParsedIconPaths(source, parsed)
        return parsed
    }

    private fun buildAccentPaths(source: String): List<Path> {
        if (source.isBlank()) return listOf(DEFAULT_ACCENT_PATH)
        val raw = pathDataEntries(source).mapNotNull { data ->
            runCatching { PathParser.createPathFromPathData(SvgPathNormalizer.normalize(data)) }
                .getOrNull()
        }
        if (raw.isEmpty()) {
            // 静默回退会让「导入了却没生效」无从排查，这里留一条错误日志
            Log.e(TAG, "Failed to parse custom icon path, falling back to the built-in badge")
            return listOf(DEFAULT_ACCENT_PATH)
        }

        val union = RectF()
        raw.forEach { candidate ->
            val measured = RectF()
            candidate.computeBounds(measured, true)
            if (!measured.isEmpty) union.union(measured)
        }
        val matrix = fitMatrix(union)
        return raw.map { candidate ->
            Path(candidate).apply {
                matrix?.let { transform(it) }
                fillType = Path.FillType.EVEN_ODD
            }
        }
    }

    /** 输入既可能是每行一条 path，也可能是完整的 `<svg>` 文档。 */
    private fun pathDataEntries(source: String): List<String> =
        if (source.trimStart().startsWith("<")) {
            SvgPathImporter.extractPathData(source)
        } else {
            source.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }

    /**
     * 把任意坐标系的 path 集合等比缩放到 96x96 视口并居中。
     * 缩放基于全部 path 的联合包围盒，才能保留多 path 图标的相对布局。
     * 导入的 SVG 常见 24x24 / 512x512 viewBox，而内置徽标本就在 96 空间，
     * 因此只对自定义 path 归一化，内置徽标保持像素级原样。
     *
     * @return null 表示包围盒为空，调用方应保持原坐标不做变换
     */
    private fun fitMatrix(union: RectF): Matrix? {
        if (union.isEmpty) return null
        val scale = minOf(VIEWPORT_SIZE / union.width(), VIEWPORT_SIZE / union.height())
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                (VIEWPORT_SIZE - union.width() * scale) / 2f - union.left * scale,
                (VIEWPORT_SIZE - union.height() * scale) / 2f - union.top * scale
            )
        }
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
        private const val TAG = "WeTypeIconDrawable"
        private const val VIEWPORT_SIZE = 96f
        private const val BACKGROUND_PATH_DATA =
            "M48,48m-48,0a48,48 0,1 1,96 0a48,48 0,1 1,-96 0"
        private const val ACCENT_PATH_DATA =
            "M61.772,26.55C60.567,26.55 59.509,27.352 59.183,28.513L58.002,32.722C57.745,33.642 58.436,34.553 59.39,34.553V34.553C65.453,34.553 68.414,36.495 66.874,41.988C66.864,42.022 66.855,42.055 66.845,42.089C65.693,46.084 61.195,49.212 57.015,49.212H48.094L50.618,40.206L52.204,34.553L53.491,29.965C53.972,28.25 52.682,26.55 50.901,26.55H45.901C44.695,26.55 43.637,27.352 43.311,28.513L37.507,49.212H23.897C22.691,49.212 21.633,50.014 21.307,51.175L20.308,54.737C19.827,56.452 21.116,58.153 22.897,58.153H35L31.268,71.457C30.787,73.173 32.076,74.873 33.857,74.873H38.858C40.063,74.873 41.121,74.071 41.447,72.91L45.586,58.153H55.05H55.328C66.079,57.67 75.078,50.863 76.542,42.042C77.944,33.593 74.674,27.024 64.438,26.55H61.772Z"

        private val BACKGROUND_PATH: Path =
            requireNotNull(PathParser.createPathFromPathData(BACKGROUND_PATH_DATA))
        private val DEFAULT_ACCENT_PATH: Path =
            requireNotNull(PathParser.createPathFromPathData(ACCENT_PATH_DATA)).apply {
                fillType = Path.FillType.EVEN_ODD
            }

        /** 进程内共享：source 与 paths 一并替换，避免多实例并发写时读到半更新状态。 */
        private class ParsedIconPaths(val source: String, val paths: List<Path>)

        @Volatile
        private var accentCache = ParsedIconPaths("", listOf(DEFAULT_ACCENT_PATH))
    }
}

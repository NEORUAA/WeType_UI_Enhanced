package com.xposed.wetypehook.wetype.graphics

import android.content.res.AssetManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.util.LruCache
import java.util.IdentityHashMap
import java.util.WeakHashMap

/** SF Pro Text and SF Symbols rendering using the module's font asset. */
internal object SfSymbols {
    private const val FONT_ASSET = "fonts/sf_pro.ttf"
    private const val HOST_FONT_ASSET = "fonts/WE-Regular.ttf"
    private const val TEXT_OPTICAL_SIZE = 17
    private const val SYMBOL_OPTICAL_SIZE = 28
    private const val LETTER_WIDTH = 90
    private const val STANDARD_WIDTH = 100
    private const val SYMBOL_FIT = 0.88f
    private const val MAX_CACHED_TEXTS = 64
    private const val MAX_CACHED_SIZES = 4

    private class FontCache {
        val families = mutableMapOf<Int, FontFamily?>()
        val typefaces = WeakHashMap<AssetManager?, MutableMap<Int, Typeface>>()
    }

    private class GlyphRun(
        val sizePx: Float,
        val paint: Paint?,
        val left: Float = 0f,
        val top: Float = 0f,
        val right: Float = 0f,
        val bottom: Float = 0f,
    )

    private class GlyphCache {
        val symbols = LruCache<String, MutableList<GlyphRun>>(MAX_CACHED_TEXTS)
        val text = LruCache<String, MutableList<GlyphRun>>(MAX_CACHED_TEXTS)
    }

    private val fontCaches = WeakHashMap<AssetManager, FontCache>()
    private val hostFamilies = WeakHashMap<AssetManager, FontFamily?>()
    private val glyphCaches = IdentityHashMap<Typeface, GlyphCache>()

    /** SF Pro Text from module assets, followed by the optional host font and system fonts. */
    fun getTypeface(
        assets: AssetManager,
        weight: Int = 400,
        hostAssets: AssetManager? = null,
    ): Typeface? = getTypeface(assets, weight, TEXT_OPTICAL_SIZE, LETTER_WIDTH, hostAssets)

    @Synchronized
    private fun getTypeface(
        assets: AssetManager,
        weight: Int,
        opticalSize: Int,
        width: Int,
        hostAssets: AssetManager?,
    ): Typeface? {
        val resolvedWeight = weight.coerceIn(1, 1000)
        val variant = opticalSize * 1_000_000 + width * 1000 + resolvedWeight
        val cache = fontCaches.getOrPut(assets) { FontCache() }
        val typefaces = cache.typefaces.getOrPut(hostAssets) { mutableMapOf() }
        typefaces[variant]?.let { return it }
        val primary = if (cache.families.containsKey(variant)) {
            cache.families[variant]
        } else {
            // Share each SF font family across host fallback combinations.
            runCatching {
                val font = Font.Builder(assets, FONT_ASSET)
                    .setFontVariationSettings("'wght' $resolvedWeight, 'opsz' $opticalSize, 'wdth' $width")
                    .setWeight(resolvedWeight)
                    .setSlant(FontStyle.FONT_SLANT_UPRIGHT)
                    .build()
                FontFamily.Builder(font).build()
            }.getOrNull().also { cache.families[variant] = it }
        } ?: return null
        return runCatching {
            val builder = Typeface.CustomFallbackBuilder(primary)
                .setStyle(FontStyle(resolvedWeight, FontStyle.FONT_SLANT_UPRIGHT))
                .setSystemFallback("sans-serif")
            hostAssets?.let(::getHostFamily)?.let(builder::addCustomFallback)
            builder.build()
        }.getOrNull()?.also { typefaces[variant] = it }
    }

    private fun getHostFamily(assets: AssetManager): FontFamily? {
        if (hostFamilies.containsKey(assets)) return hostFamilies[assets]
        // Cache missing host fonts too, so previews never retry asset IO during every draw.
        return runCatching {
            FontFamily.Builder(Font.Builder(assets, HOST_FONT_ASSET).build()).build()
        }.getOrNull().also { hostFamilies[assets] = it }
    }

    fun codePoint(systemName: String): Int? = SfSymbolCatalog.codePoint(systemName)

    fun drawSymbol(
        canvas: Canvas,
        assets: AssetManager,
        systemName: String,
        bounds: RectF,
        sizePx: Float,
        color: Int,
        weight: Int = 400,
        fitFraction: Float = SYMBOL_FIT,
        hostAssets: AssetManager? = null,
    ): Boolean {
        if (bounds.isEmpty || !fitFraction.isFinite() || fitFraction <= 0f) return false
        return draw(
            canvas, assets, SfSymbolCatalog.glyph(systemName) ?: return false,
            bounds.centerX(), bounds.centerY(), sizePx, color, weight, symbol = true,
            maxWidth = bounds.width() * fitFraction, maxHeight = bounds.height() * fitFraction,
            hostAssets = hostAssets,
        )
    }

    fun drawSymbol(
        canvas: Canvas,
        assets: AssetManager,
        systemName: String,
        centerX: Float,
        centerY: Float,
        sizePx: Float,
        color: Int,
        weight: Int = 400,
        hostAssets: AssetManager? = null,
    ): Boolean {
        return draw(
            canvas, assets, SfSymbolCatalog.glyph(systemName) ?: return false,
            centerX, centerY, sizePx, color, weight, symbol = true, hostAssets = hostAssets,
        )
    }

    fun drawText(
        canvas: Canvas,
        assets: AssetManager,
        text: String,
        bounds: RectF,
        sizePx: Float,
        color: Int,
        weight: Int = 400,
        hostAssets: AssetManager? = null,
    ): Boolean {
        if (bounds.isEmpty) return false
        return draw(
            canvas, assets, text, bounds.centerX(), bounds.centerY(), sizePx, color, weight,
            symbol = false, maxWidth = bounds.width(), maxHeight = bounds.height(),
            hostAssets = hostAssets,
        )
    }

    fun drawText(
        canvas: Canvas,
        assets: AssetManager,
        text: String,
        centerX: Float,
        centerY: Float,
        sizePx: Float,
        color: Int,
        weight: Int = 400,
        hostAssets: AssetManager? = null,
    ): Boolean = draw(
        canvas, assets, text, centerX, centerY, sizePx, color, weight, symbol = false,
        hostAssets = hostAssets,
    )

    @Synchronized
    private fun draw(
        canvas: Canvas,
        assets: AssetManager,
        text: String,
        centerX: Float,
        centerY: Float,
        sizePx: Float,
        color: Int,
        weight: Int,
        symbol: Boolean,
        maxWidth: Float = Float.POSITIVE_INFINITY,
        maxHeight: Float = Float.POSITIVE_INFINITY,
        hostAssets: AssetManager? = null,
    ): Boolean {
        if (text.isEmpty() || sizePx <= 0f || !sizePx.isFinite() ||
            !centerX.isFinite() || !centerY.isFinite() || maxWidth <= 0f || maxHeight <= 0f
        ) return false
        val opticalSize = if (symbol) SYMBOL_OPTICAL_SIZE else TEXT_OPTICAL_SIZE
        val typeface = getTypeface(assets, weight, opticalSize, STANDARD_WIDTH, hostAssets) ?: return false
        val cache = glyphCaches.getOrPut(typeface) { GlyphCache() }
        val runs = (if (symbol) cache.symbols else cache.text).let { entries ->
            entries.get(text) ?: mutableListOf<GlyphRun>().also { entries.put(text, it) }
        }
        var run: GlyphRun? = null
        for (index in runs.indices) {
            if (runs[index].sizePx == sizePx) {
                run = runs[index]
                break
            }
        }
        val glyph = run ?: createGlyph(typeface, text, sizePx).also {
            if (runs.size == MAX_CACHED_SIZES) runs.removeAt(0)
            runs.add(it)
        }
        val paint = glyph.paint ?: return false
        val scale = minOf(1f, maxWidth / (glyph.right - glyph.left), maxHeight / (glyph.bottom - glyph.top))
        if (!scale.isFinite() || scale <= 0f) return false
        paint.color = color
        val saveCount = canvas.save()
        try {
            // Ink bounds keep offset and wide SF Symbols visually centered.
            canvas.translate(
                centerX - (glyph.left + glyph.right) * scale / 2f,
                centerY - (glyph.top + glyph.bottom) * scale / 2f,
            )
            if (scale != 1f) canvas.scale(scale, scale)
            canvas.drawText(text, 0f, 0f, paint)
        } finally {
            canvas.restoreToCount(saveCount)
        }
        return true
    }

    private fun createGlyph(typeface: Typeface, text: String, sizePx: Float): GlyphRun {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = typeface
            textSize = sizePx
            textAlign = Paint.Align.LEFT
        }
        // hasGlyph(text) checks ligatures for multi-character strings, so validate each code point.
        var offset = 0
        while (offset < text.length) {
            val end = offset + Character.charCount(text.codePointAt(offset))
            if (!paint.hasGlyph(text.substring(offset, end))) return GlyphRun(sizePx, null)
            offset = end
        }
        val ink = Rect()
        paint.getTextBounds(text, 0, text.length, ink)
        if (ink.isEmpty) return GlyphRun(sizePx, null)
        return GlyphRun(sizePx, paint, ink.left.toFloat(), ink.top.toFloat(), ink.right.toFloat(), ink.bottom.toFloat())
    }
}

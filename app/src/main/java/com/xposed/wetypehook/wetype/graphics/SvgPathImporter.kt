package com.xposed.wetypehook.wetype.graphics

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

object SvgPathImporter {
    private val identity = listOf(1f, 0f, 0f, 1f, 0f, 0f)
    private val transformPattern = Regex("([a-zA-Z]+)\\s*\\(([^)]*)\\)")
    private val numberPattern = Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")
    private val definitionElements = setOf(
        "defs", "mask", "clipPath", "symbol", "pattern", "marker",
        "linearGradient", "radialGradient", "filter"
    )

    data class PathEntry(
        val pathData: String,
        // SVG's six affine coefficients: a, b, c, d, e, f.
        val transform: List<Float> = identity,
        val evenOdd: Boolean = false,
        val fillOpacity: Float = 1f
    )

    private data class ElementState(
        val transform: List<Float> = identity,
        val evenOdd: Boolean = false,
        val fillOpacity: Float = 1f,
        val fillEnabled: Boolean = true,
        val rendered: Boolean = true
    )

    /** Preserve inherited geometry and paint properties, excluding definition-only paths. */
    fun extractPaths(svgText: String): List<PathEntry> = runCatching {
        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }.newPullParser()
        parser.setInput(StringReader(svgText))
        val results = mutableListOf<PathEntry>()
        val stack = ArrayDeque<ElementState>()
        stack.addLast(ElementState())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val parent = stack.last()
                    val style = parser.getAttributeValue(null, "style")
                        ?.split(';')
                        ?.map { it.split(':', limit = 2) }
                        ?.filter { it.size == 2 }
                        ?.associate { it[0].trim() to it[1].trim() }.orEmpty()
                    fun property(name: String) = style[name]
                        ?: parser.getAttributeValue(null, name)?.trim()
                    val transform = multiply(
                        parent.transform,
                        parseTransform(parser.getAttributeValue(null, "transform").orEmpty())
                    )
                    val evenOdd = when (property("fill-rule")) {
                        "evenodd" -> true
                        "nonzero" -> false
                        else -> parent.evenOdd
                    }
                    val fillOpacity = property("fill-opacity")
                        ?.takeUnless { it == "inherit" }
                        ?.let(::parseOpacity) ?: parent.fillOpacity
                    val fillEnabled = when (property("fill")) {
                        null, "inherit" -> parent.fillEnabled
                        "none" -> false
                        else -> true
                    }
                    val rendered = parent.rendered && parser.name !in definitionElements &&
                        property("display") != "none"
                    stack.addLast(ElementState(transform, evenOdd, fillOpacity, fillEnabled, rendered))
                    if (parser.name == "path" && rendered && fillEnabled) {
                        parser.getAttributeValue(null, "d")?.takeIf { it.isNotBlank() }?.let {
                            results.add(PathEntry(it, transform, evenOdd, fillOpacity))
                        }
                    }
                }
                XmlPullParser.END_TAG -> stack.removeLast()
            }
            event = parser.next()
        }
        require(stack.size == 1) { "Incomplete SVG document" }
        results
    }.getOrElse { emptyList() }

    private fun parseOpacity(source: String): Float {
        val value = source.removeSuffix("%").toFloat()
        require(value.isFinite())
        return (if (source.endsWith('%')) value / 100f else value).coerceIn(0f, 1f)
    }

    private fun parseTransform(source: String): List<Float> {
        var matrix = identity
        var end = 0
        transformPattern.findAll(source).forEach { match ->
            require(source.substring(end, match.range.first).all { it.isWhitespace() || it == ',' })
            val arguments = match.groupValues[2]
            var argumentEnd = 0
            val values = numberPattern.findAll(arguments).map { number ->
                require(arguments.substring(argumentEnd, number.range.first)
                    .all { it.isWhitespace() || it == ',' })
                argumentEnd = number.range.last + 1
                number.value.toFloat().also { require(it.isFinite()) }
            }.toList()
            require(arguments.substring(argumentEnd).all { it.isWhitespace() || it == ',' })
            val next = when (match.groupValues[1]) {
                "matrix" -> values.also { require(it.size == 6) }
                "translate" -> {
                    require(values.size in 1..2)
                    translation(values[0], values.getOrElse(1) { 0f })
                }
                "scale" -> {
                    require(values.size in 1..2)
                    listOf(values[0], 0f, 0f, values.getOrElse(1) { values[0] }, 0f, 0f)
                }
                "rotate" -> {
                    require(values.size == 1 || values.size == 3)
                    val radians = Math.toRadians(values[0].toDouble())
                    val c = cos(radians).toFloat()
                    val s = sin(radians).toFloat()
                    val rotation = listOf(c, s, -s, c, 0f, 0f)
                    if (values.size == 1) rotation else multiply(
                        multiply(translation(values[1], values[2]), rotation),
                        translation(-values[1], -values[2])
                    )
                }
                "skewX", "skewY" -> {
                    require(values.size == 1)
                    val skew = tan(Math.toRadians(values[0].toDouble())).toFloat()
                    if (match.groupValues[1] == "skewX") {
                        listOf(1f, 0f, skew, 1f, 0f, 0f)
                    } else {
                        listOf(1f, skew, 0f, 1f, 0f, 0f)
                    }
                }
                else -> error("Unsupported SVG transform")
            }
            matrix = multiply(matrix, next)
            end = match.range.last + 1
        }
        require(source.substring(end).all { it.isWhitespace() || it == ',' })
        return matrix
    }

    private fun translation(x: Float, y: Float) = listOf(1f, 0f, 0f, 1f, x, y)

    // Column vectors: the child/local transform is applied before its parent.
    private fun multiply(left: List<Float>, right: List<Float>): List<Float> = listOf(
        left[0] * right[0] + left[2] * right[1],
        left[1] * right[0] + left[3] * right[1],
        left[0] * right[2] + left[2] * right[3],
        left[1] * right[2] + left[3] * right[3],
        left[0] * right[4] + left[2] * right[5] + left[4],
        left[1] * right[4] + left[3] * right[5] + left[5]
    ).also { require(it.all(Float::isFinite)) }
}

package com.xposed.wetypehook.wetype.graphics

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

object SvgPathImporter {

    /**
     * 从 SVG 文本中提取所有 `<path>` 的 `d` 属性，按文档顺序返回。
     *
     * 缩放不在此处理：`WeTypeIconDrawable` 会依据联合包围盒把 path 等比归一到 96x96 视口，
     * 因此这里既不关心 viewBox 也不关心宽高。
     *
     * @return 空列表表示文件不可解析，或其中没有可用的 path（例如仅含 rect/circle）。
     */
    fun extractPathData(svgText: String): List<String> = runCatching {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(svgText.toByteArray()), null)
        val results = mutableListOf<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("path", ignoreCase = true)) {
                repeat(parser.attributeCount) { index ->
                    if (parser.getAttributeName(index).equals("d", ignoreCase = true)) {
                        parser.getAttributeValue(index)
                            ?.takeIf { it.isNotBlank() }
                            ?.let(results::add)
                    }
                }
            }
            event = parser.next()
        }
        results
    }.getOrElse { emptyList() }
}

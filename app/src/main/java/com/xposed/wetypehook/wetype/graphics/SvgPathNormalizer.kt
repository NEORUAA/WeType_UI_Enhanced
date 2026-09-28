package com.xposed.wetypehook.wetype.graphics

/**
 * 规范化 SVG path 数据，保证 [androidx.core.graphics.PathParser] 能完整解析。
 *
 * 压缩工具生成的 path 常把 `A`/`a` 的 large-arc-flag 与 sweep-flag 紧贴相邻坐标，
 * 例如 `A13.443 13.443 0 017.947 8.897`（本意是 flag `0`、flag `1`、x `7.947`）。
 * 而 `PathParser.getFloats()` 只按空格、逗号、第二个小数点和负号切分数值，会把
 * `017.947` 整体读成 `17.947`，于是这条弧线只剩 5 个参数（规范要求 7 个）。
 * `PathDataNode.addCommand()` 按固定步长遍历参数且不校验边界，随即数组越界，
 * 整条 path 解析失败——调用方通常会回退到默认徽标，表现为「自定义图标不生效」。
 *
 * [normalize] 按命令的参数个数重新切分并补齐 token 之间的分隔符：对 `A`/`a`
 * 单独把第 4、5 个参数按单字符读取，其余命令按数值读取。输出是幂等的。
 */
object SvgPathNormalizer {

    /** 每种命令的参数个数（大小写一致，`z` 无参数，未收录的命令按 1 处理）。 */
    private val PARAM_COUNTS = mapOf(
        'm' to 2,
        'l' to 2,
        'h' to 1,
        'v' to 1,
        'c' to 6,
        's' to 4,
        'q' to 4,
        't' to 2,
        'a' to 7,
        'z' to 0,
    )

    /**
     * 重排一条 path 的 token，补上解析器所需的分隔符。
     * 空白输入原样返回；已规范化的输入再次处理结果不变。
     */
    fun normalize(pathData: String): String {
        if (pathData.isBlank()) return pathData
        val result = StringBuilder(pathData.length + 16)
        var command = ' '
        var paramIndex = 0
        var index = 0
        while (index < pathData.length) {
            val char = pathData[index]
            when {
                // e/E 不是 path 命令，只会作为指数出现，不能在这里当成新命令
                char.isLetter() && char != 'e' && char != 'E' -> {
                    command = char.lowercaseChar()
                    paramIndex = 0
                    result.append(char)
                    index++
                }
                char.isLetter() -> index++
                char.isWhitespace() || char == ',' -> index++
                else -> {
                    val end = readToken(pathData, index, command, paramIndex)
                    appendToken(result, pathData.substring(index, end))
                    index = end
                    val count = PARAM_COUNTS[command]
                    paramIndex = if (count == null || count == 0) 0 else (paramIndex + 1) % count
                }
            }
        }
        return result.toString()
    }

    /**
     * 返回 [start] 处 token 的结束下标。
     * `A`/`a` 的第 4、5 个参数是 `0`/`1` flag，必须按单字符读取，
     * 否则会与相邻坐标粘连成一个数。
     */
    private fun readToken(text: String, start: Int, command: Char, paramIndex: Int): Int {
        val isArcFlag = command == 'a' && (paramIndex == 3 || paramIndex == 4)
        if (isArcFlag && (text[start] == '0' || text[start] == '1')) return start + 1
        return readNumber(text, start)
    }

    /**
     * 返回 [start] 处数值的结束下标，规则与 `PathParser` 一致：
     * 可选符号 + 最多一个小数点，指数部分仅在 `e/E` 后确有数字时才吸收。
     * 保证至少前进一位，避免畸形输入导致死循环。
     */
    private fun readNumber(text: String, start: Int): Int {
        var index = start
        if (text[index] == '+' || text[index] == '-') index++
        var hasDot = false
        while (index < text.length) {
            val char = text[index]
            if (char.isDigit()) {
                index++
            } else if (char == '.' && !hasDot) {
                hasDot = true
                index++
            } else {
                break
            }
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            var exponent = index + 1
            if (exponent < text.length && (text[exponent] == '+' || text[exponent] == '-')) exponent++
            if (exponent < text.length && text[exponent].isDigit()) {
                index = exponent
                while (index < text.length && text[index].isDigit()) index++
            }
        }
        return if (index == start) start + 1 else index
    }

    /** 命令字母紧跟数字不需要分隔（`M0`），数字之间必须补空格。 */
    private fun appendToken(result: StringBuilder, token: String) {
        if (result.isNotEmpty() && !result.last().isLetter()) result.append(' ')
        result.append(token)
    }
}

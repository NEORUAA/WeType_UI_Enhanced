package com.xposed.wetypehook.wetype.hook

import java.util.Locale

private val SUPPORTED_DRAWER_TYPES = setOf("normalType", "imageType", "floatType", "dotComma")
private val PAGE_SWITCH_IDS = setOf("num", "return", "back", "symbol")
private val PAGE_SWITCH_LABELS = setOf("123", "ABC", "abc", "#+=", "=\\<")

internal sealed interface WeTypeKeyAppearance {
    data class Symbol(val name: String) : WeTypeKeyAppearance
    data class Text(val value: String) : WeTypeKeyAppearance
    data object Empty : WeTypeKeyAppearance
    data object FontOnly : WeTypeKeyAppearance
}

/** A drawing policy only: host labels also drive editor and voice-input actions. */
internal fun resolveWeTypeKeyAppearance(
    id: String,
    drawerType: String,
    text: String,
    imageName: String
): WeTypeKeyAppearance? {
    if (drawerType !in SUPPORTED_DRAWER_TYPES) return null
    val keyId = id.removeSuffix("_copy")
    val image = imageName.removeSuffix("_dark").removeSuffix("_pressed")
    if (keyId == "enter") {
        val action = text.trim().lowercase(Locale.ROOT)
        val symbol = when (action) {
            "搜索", "搜尋", "search" -> "magnifyingglass"
            "发送", "發送", "传送", "傳送", "send" -> "arrow.up"
            "完成", "done", "确定", "確定", "确认", "確認", "confirm" -> "checkmark"
            "下一项", "下一項", "下一个", "下一個", "下一步", "继续", "繼續", "next", "continue",
            "前往", "开始", "開始", "go" -> "arrow.right"
            "上一项", "上一項", "上一个", "上一個", "返回", "previous" -> "arrow.left"
            "换行", "換行", "回车", "回車", "return", "enter", "newline" -> "return"
            "" -> if (image == "icon_keys_return") "return" else null
            else -> null
        }
        // App-defined action labels retain their wording instead of borrowing an editor action.
        return symbol?.let(WeTypeKeyAppearance::Symbol)
            ?: WeTypeKeyAppearance.FontOnly.takeIf { drawerType != "imageType" }
    }
    if (drawerType == "imageType") {
        when (image) {
            "icon_keys_outlinedcapslock" -> return WeTypeKeyAppearance.Symbol("shift")
            "icon_keys_capslock" -> return WeTypeKeyAppearance.Symbol("shift.fill")
            "icon_keys_capslocked" -> return WeTypeKeyAppearance.Symbol("capslock.fill")
            "icon_keys_delete" -> return WeTypeKeyAppearance.Symbol("delete.left")
            "icon_keys_123" -> return WeTypeKeyAppearance.Text("123")
            "icon_keys_back" -> if (keyId == "return") return WeTypeKeyAppearance.Symbol("arrow.left")
            "icon_keys_voice" -> if (keyId == "space" && text.isBlank()) return WeTypeKeyAppearance.Empty
        }
        return null
    }
    if (keyId == "symbol" && (text == "符号" || text == "符號")) {
        return WeTypeKeyAppearance.Text("#+=")
    }
    if (keyId in PAGE_SWITCH_IDS && text in PAGE_SWITCH_LABELS) {
        return WeTypeKeyAppearance.Text(text)
    }
    // Keep the host's wording and layout while using the same font for every text key.
    return WeTypeKeyAppearance.FontOnly.takeIf { text.isNotBlank() }
}

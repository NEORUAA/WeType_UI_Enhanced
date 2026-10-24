package com.xposed.wetypehook.wetype.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class WeTypeKeyboardSymbolsTest {
    @Test
    fun editorActionsKeepTheirSpecificMeaning() {
        mapOf(
            "搜索" to "magnifyingglass", " Search " to "magnifyingglass",
            "发送" to "arrow.up", "send" to "arrow.up",
            "完成" to "checkmark", "done" to "checkmark",
            "确定" to "checkmark", "确认" to "checkmark", "Confirm" to "checkmark",
            "下一项" to "arrow.right", "next" to "arrow.right",
            "继续" to "arrow.right", "continue" to "arrow.right",
            "前往" to "arrow.right", "go" to "arrow.right",
            "上一项" to "arrow.left", "previous" to "arrow.left",
            "返回" to "arrow.left",
            "换行" to "return", "return" to "return"
        ).forEach { (label, symbol) ->
            assertEquals(label, WeTypeKeyAppearance.Symbol(symbol), appearance("enter", label))
        }
        assertEquals(WeTypeKeyAppearance.Symbol("return"),
            appearance("enter", "", "imageType", "icon_keys_return"))
    }

    @Test
    fun customActionLabelsRetainTheirWordingInsteadOfInheritingAnEditorAction() {
        listOf("确认发送", "Join", "支付", "Web Search").forEach { label ->
            assertEquals(label, WeTypeKeyAppearance.FontOnly, appearance("enter", label))
        }
        assertNull(appearance("enter", "", "imageType", "custom_action"))
        assertNull(appearance("enter", "搜索", "emptyTextType"))
        assertNull(appearance("enter", "发送", "blankType"))
        assertEquals(WeTypeKeyAppearance.FontOnly, appearance("S3_key_s", "Search"))
    }

    @Test
    fun shiftModesAndPressedDeleteUseTheCorrespondingSymbols() {
        mapOf(
            "icon_keys_outlinedcapslock" to "shift",
            "icon_keys_capslock_dark" to "shift.fill",
            "icon_keys_capslocked" to "capslock.fill",
            "icon_keys_delete_pressed" to "delete.left"
        ).forEach { (image, symbol) ->
            assertEquals(WeTypeKeyAppearance.Symbol(symbol), appearance("shift", "", "imageType", image))
        }
        assertEquals(WeTypeKeyAppearance.FontOnly, appearance("shift", "'词"))
    }

    @Test
    fun keyboardPageSwitchesDoNotBecomeEditorActions() {
        assertEquals(WeTypeKeyAppearance.Text("123"), appearance("num", "", "imageType", "icon_keys_123"))
        assertEquals(WeTypeKeyAppearance.Text("123"), appearance("num", "123"))
        assertEquals(WeTypeKeyAppearance.Symbol("arrow.left"), appearance("return", "", "imageType", "icon_keys_back"))
        assertEquals(WeTypeKeyAppearance.Text("#+="), appearance("symbol", "符号"))
        assertEquals(WeTypeKeyAppearance.Text("ABC"), appearance("return", "ABC"))
        assertNull(appearance("num", "", "imageType", "icon_keys_num_t9"))
        assertNull(appearance("return", "", "imageType", "custom_back"))
    }

    @Test
    fun allTextUsesSfAndOnlyTheIdleSpaceMicrophoneIsHidden() {
        listOf("A", "z", "é", "中", "ABC", "PQRS", "1", ",", "。", "@", "#").forEach {
            assertEquals(WeTypeKeyAppearance.FontOnly, appearance("key", it))
        }
        assertEquals(WeTypeKeyAppearance.Empty, appearance("space_copy", "", "imageType", "icon_keys_voice_dark"))
        assertEquals(WeTypeKeyAppearance.Empty, appearance("space", "", "imageType", "icon_keys_voice_pressed"))
        assertNull(appearance("space", "长按说话", "imageType", "icon_keys_voice"))
        assertNull(appearance("space", "", "imageType", "custom_voice"))
        assertNull(appearance("voice", "", "imageType", "icon_keys_voice"))
        assertEquals(WeTypeKeyAppearance.FontOnly, appearance("space", "长按说话"))
        assertNull(appearance("emoji", "", "imageType", "icon_keys_emoji"))
    }

    @Test
    fun hostMetadataResolvesOptimizedGettersAndReusedNamesWithoutAdjacency() {
        val (data, strings) = hostPropertyMetadata()
        val properties = WeTypeKeyboardSymbols.decodePropertySignatures(data, strings)
        assertEquals(WeTypeKeyboardSymbols.PropertySignature("s", "getMainTextColor"), properties["mainTextColor"])
        assertEquals(WeTypeKeyboardSymbols.PropertySignature("t", "getMainTextColorPressed"), properties["mainTextColorPressed"])
        assertEquals(WeTypeKeyboardSymbols.PropertySignature("G", "G"), properties["iconColor"])
        // "I" is reused from an earlier descriptor; it is nowhere near this property's d2 entry.
        assertEquals(WeTypeKeyboardSymbols.PropertySignature("H", "I"), properties["iconPressedColor"])
        assertEquals(WeTypeKeyboardSymbols.PropertySignature("m0", "c0"), properties["status"])
    }

    @Test
    fun unsupportedOrTruncatedMetadataFailsClosed() {
        val (data, strings) = hostPropertyMetadata()
        assertThrows(IllegalStateException::class.java) {
            WeTypeKeyboardSymbols.decodePropertySignatures(arrayOf(data.single().dropLast(1)), strings)
        }
        assertThrows(IllegalStateException::class.java) {
            WeTypeKeyboardSymbols.decodePropertySignatures(arrayOf("unsupported"), strings)
        }
    }

    private fun hostPropertyMetadata(): Pair<Array<String>, Array<String>> {
        // A subset of WeType 4.0.0 ImeButton's d1 properties, retaining its original d2 indices.
        val bytes = Base64.getDecoder().decode(
            "lQEKAhgCCgIQAAoCGAIKAAoCGAIKAAoCGAIKAggDCgIQCAoCCAIKAhALCgIIAgoCGAIKAggLCgIYAgoCCAsKAhgCCgIIBQoCGAIKAggDCgIQDgoCCAIKAhgCCgIIAgoCGAIKAgglCgIQBwoCCAsKAhgCCgMIkQEKAhgCCgIIDgoCGAIKAggFCgIYAgoCCAIKAhAJCgIIAlImEI4BGgIwCjgGQAZYhg6iBhUKBQiLARBSGgUIjAEQFCIFCI0BEFVSJRCRARoCMAo4BkAGWIYOogYUCgQIcRBSGgUIjwEQFCIFCJABEFVSJhC4ARoCMAo4BkAGWIYOogYVCgUItgEQUhoFCLYBEBQiBQi3ARBVUiUQugEaAjAKOAZABliGDqIGFAoFCLMBEFIaBAhSEBQiBQi5ARBVUi4QiAIaAjAyMgcQhgIaAjAyOAZAQliGDqIGFAoECCYQTBoFCPABEEEiBQiHAhBQ"
        )
        val encoded = "\u0000" + bytes.map { (it.toInt() and 0xff).toChar() }.joinToString("")
        val strings = Array(279) { "" }
        mapOf(
            38 to "m0", 82 to "I", 113 to "t", 139 to "s", 140 to "getMainTextColor",
            142 to "mainTextColor", 143 to "getMainTextColorPressed", 145 to "mainTextColorPressed",
            179 to "H", 182 to "G", 184 to "iconColor", 186 to "iconPressedColor",
            240 to "c0", 264 to "status"
        ).forEach { (index, value) -> strings[index] = value }
        return arrayOf(encoded) to strings
    }

    private fun appearance(id: String, text: String, drawer: String = "normalType", image: String = "") =
        resolveWeTypeKeyAppearance(id, drawer, text, image)
}

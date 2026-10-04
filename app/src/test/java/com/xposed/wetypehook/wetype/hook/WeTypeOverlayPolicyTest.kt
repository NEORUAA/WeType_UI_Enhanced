package com.xposed.wetypehook.wetype.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class WeTypeOverlayPolicyTest {
    // ImeCandidateView.M1 adds the right-hand settings toolbar at child index 0,
    // outside setting_container_rl. Both are shared with S10SettingsKeyboard.
    private fun settingsWithSiblingChrome() = listOf(
        layer(1, WeTypeLayerRole.Candidate),
        layer(2, WeTypeLayerRole.SettingsChrome, ancestors = listOf(1)),
        layer(3, WeTypeLayerRole.CandidateContent, ancestors = listOf(1, 2)),
        layer(4, WeTypeLayerRole.CandidateContent, ancestors = listOf(1, 2)),
        layer(5, WeTypeLayerRole.SettingsChrome, ancestors = listOf(1)),
        layer(6, WeTypeLayerRole.CandidateContent, ancestors = listOf(1, 5)),
        layer(7, WeTypeLayerRole.CandidateContent, ancestors = listOf(1)),
        layer(8, WeTypeLayerRole.OverlayKeyboard, owner = 8)
    )

    @Test
    fun expandedToolsKeepBothSiblingChromeBranchesAndHideOnlyCandidates() {
        assertEquals(mapOf(7 to 0), resolveWeTypeDrawAlphas(settingsWithSiblingChrome(), true))
    }

    @Test
    fun rightToolbarRemainsVisibleEvenWhenHostHidesBackAndTitle() {
        assertEquals(mapOf(7 to 0), resolveWeTypeDrawAlphas(settingsWithSiblingChrome().map {
            if (it.id == 5 || it.id == 6) it.copy(rendered = false) else it
        }, true))
    }

    @Test
    fun sharedToolbarKeepsNativeSettingsCrossfadeAndPageSwitching() {
        for (toolsRequested in listOf(true, false)) {
            assertEquals(mapOf(7 to 128), resolveWeTypeDrawAlphas(settingsWithSiblingChrome().map {
                if (it.id == 8) it.copy(opacity = 0.5f) else it
            }, toolsRequested))
        }
    }

    @Test
    fun emojiCrossfadeCoversSharedChromeWhileSettingsStillHidesCandidateSiblings() {
        val layers = settingsWithSiblingChrome() +
            layer(9, WeTypeLayerRole.OverlayKeyboard).copy(opacity = 0.5f)
        assertEquals(mapOf(1 to 128, 7 to 0, 8 to 128), resolveWeTypeDrawAlphas(layers, true))
        assertEquals(mapOf(1 to 0, 8 to 0), resolveWeTypeDrawAlphas(layers.map {
            if (it.id == 9) it.copy(opacity = 1f) else it
        }, true))
    }

    @Test
    fun hiddenSharedChromeDoesNotPreserveTheOldCandidateBar() {
        assertEquals(mapOf(1 to 0), resolveWeTypeDrawAlphas(settingsWithSiblingChrome().map {
            if (it.id in 2..6) it.copy(rendered = false) else it
        }, true))
    }

    @Test
    fun incomingAndOutgoingOverlayKeepEveryNativeCrossfadeStage() {
        val keyboard = layer(1, WeTypeLayerRole.Keyboard)
        val overlay = layer(2, WeTypeLayerRole.OverlayKeyboard)
        listOf(0f to 255, 0.25f to 191, 0.5f to 128, 0.75f to 64, 1f to 0,
            0.75f to 64, 0.5f to 128, 0.25f to 191, 0f to 255).forEach { (opacity, expected) ->
            val result = resolveWeTypeDrawAlphas(listOf(keyboard, overlay.copy(opacity = opacity)), true)
            assertEquals(expected, result[1] ?: 255)
        }
    }

    @Test
    fun settingsContentFadeRetainsToolsUntilItsNativeContentBecomesOpaque() {
        val tools = layer(1, WeTypeLayerRole.SettingsTools, owner = 10)
        val page = layer(2, WeTypeLayerRole.SettingsPage, owner = 10)
        assertEquals(emptyMap<Int, Int>(), resolveWeTypeDrawAlphas(
            listOf(tools, page.copy(opacity = 0f)), false))
        assertEquals(mapOf(1 to 128), resolveWeTypeDrawAlphas(
            listOf(tools, page.copy(opacity = 0.5f)), false))
        assertEquals(mapOf(1 to 128), resolveWeTypeDrawAlphas(
            listOf(tools, page.copy(opacity = 0.5f)), true))
    }

    @Test
    fun sharedNavigationStaysVisibleDuringPartialSettingsCrossfade() {
        assertEquals(mapOf(3 to 128, 6 to 128), resolveWeTypeDrawAlphas(
            settingsWithSharedNavigation().map {
                if (it.id == 7) it.copy(opacity = 0.5f) else it
            }, false))
    }

    @Test
    fun stackedTranslucentOverlaysComposeTransmissionRatherThanDiscardingUnderlay() {
        assertEquals(mapOf(1 to 64, 2 to 128), resolveWeTypeDrawAlphas(listOf(
            layer(1, WeTypeLayerRole.Keyboard),
            layer(2, WeTypeLayerRole.OverlayKeyboard).copy(opacity = 0.5f),
            layer(3, WeTypeLayerRole.OverlayKeyboard).copy(opacity = 0.5f)
        ), true))
    }

    private fun settingsWithSharedNavigation() = listOf(
        layer(1, WeTypeLayerRole.Candidate),
        layer(2, WeTypeLayerRole.CandidateContent, ancestors = listOf(1)),
        layer(3, WeTypeLayerRole.CandidateContent, ancestors = listOf(1, 2)),
        layer(4, WeTypeLayerRole.SettingsChrome, ancestors = listOf(1, 2)),
        layer(5, WeTypeLayerRole.CandidateContent, ancestors = listOf(1, 2, 4)),
        layer(6, WeTypeLayerRole.CandidateContent, ancestors = listOf(1)),
        layer(7, WeTypeLayerRole.OverlayKeyboard, owner = 7)
    )

    @Test
    fun settingsKeepsSharedBackButtonTitleAndTheirContainersWhileCoveringOtherContent() {
        assertEquals(setOf(3, 6), resolveWeTypeDrawMasks(settingsWithSharedNavigation(), false))
        // Keep navigation during the settings exit animation as well.
        assertEquals(setOf(3, 6), resolveWeTypeDrawMasks(settingsWithSharedNavigation(), true))
    }

    @Test
    fun emojiAboveSettingsStillCoversTheEntireCandidateBarIncludingSharedNavigation() {
        assertEquals(setOf(1, 7), resolveWeTypeDrawMasks(settingsWithSharedNavigation() +
            layer(8, WeTypeLayerRole.OverlayKeyboard), false))
    }

    @Test
    fun hiddenSettingsChromeDoesNotLeaveOldCandidateContentExposed() {
        assertEquals(setOf(1), resolveWeTypeDrawMasks(settingsWithSharedNavigation().map {
            if (it.id == 4) it.copy(rendered = false) else it
        }, false))
    }

    private fun layer(id: Int, role: WeTypeLayerRole, shown: Boolean = true,
                      ancestors: List<Int> = emptyList(), owner: Int? = null) =
        WeTypeLayerSnapshot(id, role, ancestors, shown, owner)

    @Test
    fun emojiAndSettingsOccludeBothKeysAndCandidateThroughoutExit() {
        val keys = layer(1, WeTypeLayerRole.Keyboard)
        val candidate = layer(2, WeTypeLayerRole.Candidate)
        val overlay = layer(3, WeTypeLayerRole.OverlayKeyboard)
        assertEquals(setOf(1, 2), resolveWeTypeDrawMasks(listOf(keys, candidate, overlay), true))
        // The parent is still rendered while it fades, regardless of logical page state.
        assertEquals(setOf(1, 2), resolveWeTypeDrawMasks(listOf(keys, candidate, overlay), false))
        assertEquals(emptySet<Int>(), resolveWeTypeDrawMasks(
            listOf(keys, candidate, overlay.copy(rendered = false)), true))
    }

    @Test
    fun nestedKeyboardContentAndAncestorsAreNeverMaskedByTheirOwnOverlay() {
        val parent = layer(1, WeTypeLayerRole.Keyboard)
        val overlay = layer(2, WeTypeLayerRole.OverlayKeyboard, ancestors = listOf(1))
        val nested = layer(3, WeTypeLayerRole.Keyboard, ancestors = listOf(1, 2))
        assertEquals(emptySet<Int>(), resolveWeTypeDrawMasks(listOf(parent, overlay, nested), null))
    }

    @Test
    fun onlyTheTopRenderedOverlayWinsAndCachedHiddenPagesDoNotOcclude() {
        val layers = listOf(layer(1, WeTypeLayerRole.Keyboard),
            layer(2, WeTypeLayerRole.OverlayKeyboard), layer(3, WeTypeLayerRole.OverlayKeyboard))
        assertEquals(setOf(1, 2), resolveWeTypeDrawMasks(layers, null))
        assertEquals(setOf(1), resolveWeTypeDrawMasks(layers.map {
            if (it.id == 3) it.copy(rendered = false) else it
        }, null))
        assertEquals(emptySet<Int>(), resolveWeTypeDrawMasks(layers.map {
            if (it.role == WeTypeLayerRole.OverlayKeyboard) it.copy(rendered = false) else it
        }, null))
    }

    @Test
    fun aNewKeyboardPaintedAboveAnOldOverlayKeepsItsNativePriority() {
        assertEquals(emptySet<Int>(), resolveWeTypeDrawMasks(listOf(
            layer(1, WeTypeLayerRole.OverlayKeyboard), layer(2, WeTypeLayerRole.Keyboard)
        ), null))
    }

    @Test
    fun settingsIntentCoversTheAsyncRevealGapAndClosingAnimation() {
        val tools = layer(1, WeTypeLayerRole.SettingsTools, owner = 10)
        val page = layer(2, WeTypeLayerRole.SettingsPage, shown = false, owner = 10)
        // The host StateFlow already requested a page; its collector has not revealed it.
        assertEquals(setOf(1), resolveWeTypeDrawMasks(listOf(tools, page), false))
        assertEquals(setOf(1), resolveWeTypeDrawMasks(listOf(tools, page.copy(rendered = true)), false))
        // Closing requested tools, but the legacy page animation is still on screen.
        assertEquals(setOf(1), resolveWeTypeDrawMasks(listOf(tools, page.copy(rendered = true)), true))
        assertEquals(emptySet<Int>(), resolveWeTypeDrawMasks(listOf(tools, page), true))
    }

    @Test
    fun cancelledOrRepeatedOpeningUsesCurrentHostStateWithoutRememberedVisibility() {
        val layers = listOf(layer(1, WeTypeLayerRole.SettingsTools, owner = 10),
            layer(2, WeTypeLayerRole.SettingsPage, shown = false, owner = 10))
        repeat(3) {
            assertEquals(setOf(1), resolveWeTypeDrawMasks(layers, false))
            assertEquals(emptySet<Int>(), resolveWeTypeDrawMasks(layers, true))
        }
        assertEquals(emptySet<Int>(), resolveWeTypeDrawMasks(layers.map { it.copy(rendered = false) }, false))
    }

    @Test
    fun pageFromAnotherSettingsRootDoesNotHideThisToolsGrid() {
        assertEquals(emptySet<Int>(), resolveWeTypeDrawMasks(listOf(
            layer(1, WeTypeLayerRole.SettingsTools, owner = 10),
            layer(2, WeTypeLayerRole.SettingsPage, owner = 20)
        ), true))
    }

    @Test
    fun missingIntentReaderUsesVisiblePagesAndDoesNotInventAPendingPage() {
        val tools = layer(1, WeTypeLayerRole.SettingsTools, owner = 10)
        val page = layer(2, WeTypeLayerRole.SettingsPage, owner = 10)
        assertEquals(setOf(1), resolveWeTypeDrawMasks(listOf(tools, page), null))
        assertEquals(emptySet<Int>(), resolveWeTypeDrawMasks(listOf(tools, page.copy(rendered = false)), null))
    }

    @Test
    fun incomingTranslatorToolbarIsNotTreatedAsACoveredKeyboardRoot() {
        // Inline translation/AI panels are ordinary host views, not overlay keyboard roots.
        assertEquals(emptySet<Int>(), resolveWeTypeDrawMasks(listOf(
            layer(1, WeTypeLayerRole.Keyboard), layer(2, WeTypeLayerRole.Candidate)
        ), null))
    }

    @Test
    fun maskedDrawingStillCallsNativeDrawingOnceAndRestoresItsCanvas() {
        val events = mutableListOf<String>()
        val nativeResult = Any()
        val result = withWeTypeTransparentDrawing(
            saveTransparentLayer = { events += "save"; 7 },
            restore = { events += "restore:$it" },
            draw = { events += "native-animation-and-draw"; nativeResult }
        )
        assertSame(nativeResult, result)
        assertEquals(listOf("save", "native-animation-and-draw", "restore:7"), events)
    }

    @Test
    fun drawingFailureRestoresTheCanvasAndDoesNotRetryOrSwallowTheHostException() {
        var draws = 0
        var restored = false
        val hostFailure = IllegalStateException("host draw failed")
        val failure = assertThrows(IllegalStateException::class.java) {
            withWeTypeTransparentDrawing(
                saveTransparentLayer = { 3 },
                restore = { assertEquals(3, it); restored = true },
                draw = { draws++; throw hostFailure }
            )
        }
        assertSame(hostFailure, failure)
        assertEquals(1, draws)
        assertTrue(restored)
    }
}

package com.xposed.wetypehook.wetype.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeTypeBackgroundBoundsTest {
    private val decor = layout(0, 2400)

    @Test
    fun relayoutDefersMaterialUpdateButHiddenContentRemovesIt() {
        assertEquals(WeTypeBackgroundUpdate.PendingLayout, resolveWeTypeBackgroundUpdate(
            decor.copy(isLayoutRequested = true), listOf(layout(1400, 1000))
        ))
        assertEquals(WeTypeBackgroundUpdate.PendingLayout, resolveWeTypeBackgroundUpdate(
            decor, listOf(layout(1400, 1000).copy(isLayoutRequested = true))
        ))
        assertEquals(WeTypeBackgroundUpdate.Hidden, resolveWeTypeBackgroundUpdate(
            decor.copy(isShown = false, isLayoutRequested = true), listOf(layout(1400, 1000))
        ))
        assertEquals(WeTypeBackgroundUpdate.Hidden, resolveWeTypeBackgroundUpdate(decor, emptyList()))
        assertEquals(WeTypeBackgroundUpdate.Ready(WeTypeBackgroundBounds(1400, 1000)),
            resolveWeTypeBackgroundUpdate(decor, listOf(layout(1400, 1000))))
    }

    @Test
    fun doesNotUseMeasuredOrPreviousBoundsBeforeLayout() {
        assertNull(resolveWeTypeBackgroundBounds(decor, listOf(
            layout(0, 0).copy(isLaidOut = false)
        )))
        assertNull(resolveWeTypeBackgroundBounds(decor, listOf(
            layout(1000, 1400).copy(isLayoutRequested = true)
        )))
    }

    @Test
    fun waitsForPendingInputEvenWhenCandidatesAreAlreadyLaidOut() {
        assertNull(resolveWeTypeBackgroundBounds(decor, listOf(
            layout(1400, 80), layout(1480, 920).copy(isLayoutRequested = true)
        )))
    }

    @Test
    fun missingOrHiddenContentDoesNotBecomeAFullWindowBackground() {
        assertNull(resolveWeTypeBackgroundBounds(decor, emptyList()))
        assertNull(resolveWeTypeBackgroundBounds(decor, listOf(layout(0, 2400).copy(isShown = false))))
        assertNull(resolveWeTypeBackgroundBounds(decor, listOf(layout(0, 0))))
    }

    @Test
    fun waitsForTheDecorToFinishItsOwnLayout() {
        assertNull(resolveWeTypeBackgroundBounds(
            decor.copy(isLayoutRequested = true), listOf(layout(1400, 1000))
        ))
        assertNull(resolveWeTypeBackgroundBounds(
            decor.copy(isShown = false), listOf(layout(1400, 1000))
        ))
    }

    @Test
    fun rejectsPositionsOutsideTheDecorInsteadOfClampingThemToFullHeight() {
        assertNull(resolveWeTypeBackgroundBounds(decor, listOf(layout(-100, 1000))))
        assertNull(resolveWeTypeBackgroundBounds(decor, listOf(layout(2400, 1000))))
    }

    @Test
    fun usesDecorLocalCoordinatesAndAcceptsARealZeroTop() {
        assertEquals(WeTypeBackgroundBounds(400, 600), resolveWeTypeBackgroundBounds(
            layout(200, 1000), listOf(layout(600, 600))
        ))
        assertEquals(WeTypeBackgroundBounds(0, 1000), resolveWeTypeBackgroundBounds(
            layout(200, 1000), listOf(layout(200, 1000))
        ))
    }

    @Test
    fun tracksPositionOnlyChangesAndKeepsTheBottomBarCovered() {
        assertEquals(WeTypeBackgroundBounds(1400, 1000), resolveWeTypeBackgroundBounds(
            decor, listOf(layout(1400, 80), layout(1480, 820))
        ))
        assertEquals(WeTypeBackgroundBounds(1500, 900), resolveWeTypeBackgroundBounds(
            decor, listOf(layout(1500, 80), layout(1580, 820))
        ))
    }

    private fun layout(top: Int, height: Int) = WeTypeBackgroundLayout(top, height, true, true, false)
}

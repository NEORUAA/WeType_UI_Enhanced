package com.xposed.wetypehook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiuiBottomInsetCompensationTest {
    private val originalSpacer = MiuiFullscreenSpacerLayout(-1, 0f)
    private val flexibleSpacer = MiuiFullscreenSpacerLayout(0, 1f)
    private val noChange = MiuiBottomInsetUpdate()

    @Test
    fun firstFrameAndRepeatedMeasurementsKeepTheOriginalValuesForRelease() {
        val state = MiuiBottomInsetCompensation()
        assertEquals(noChange, state.reconcile(false, null, 48, originalSpacer, true))
        assertEquals(noChange, state.reconcile(true, 48, 24, originalSpacer, true))
        assertEquals(MiuiBottomInsetUpdate(0, flexibleSpacer), state.reconcile(
            true, 48, 48, originalSpacer, true
        ))
        repeat(4) {
            assertEquals(noChange, state.reconcile(true, 48, 0, flexibleSpacer, true))
        }
        assertEquals(MiuiBottomInsetUpdate(48, originalSpacer), state.release(0, flexibleSpacer))
        assertFalse(state.hasAdjustedPadding)
        assertEquals(noChange, state.release(48, originalSpacer))
    }

    @Test
    fun navigationInsetChangesUpdateTheRestoredPaddingWithoutReapplyingIt() {
        val state = MiuiBottomInsetCompensation()
        state.reconcile(true, 48, 48, originalSpacer, true)
        assertEquals(noChange, state.reconcile(true, 72, 0, flexibleSpacer, true))
        assertEquals(MiuiBottomInsetUpdate(72, originalSpacer), state.release(0, flexibleSpacer))
    }

    @Test
    fun hostPaddingRewriteReleasesTheSpacerAndDisablesThisFrame() {
        val state = MiuiBottomInsetCompensation()
        state.reconcile(true, 48, 48, originalSpacer, true)
        assertEquals(MiuiBottomInsetUpdate(spacerLayout = originalSpacer), state.reconcile(
            true, 48, 48, flexibleSpacer, true
        ))
        assertFalse(state.hasAdjustedPadding)
        repeat(3) {
            assertEquals(noChange, state.reconcile(true, 48, 48, originalSpacer, true))
        }
        state.release(48, originalSpacer)
        assertEquals(noChange, state.reconcile(true, 48, 48, originalSpacer, true))
    }

    @Test
    fun hostSpacerHeightRewriteKeepsItsWholeLayoutAndReleasesOurPadding() {
        val state = MiuiBottomInsetCompensation()
        state.reconcile(true, 48, 48, originalSpacer, true)
        val hostSpacer = MiuiFullscreenSpacerLayout(120, 1f)
        assertEquals(MiuiBottomInsetUpdate(48), state.reconcile(
            true, 48, 0, hostSpacer, true
        ))
        assertEquals(noChange, state.reconcile(true, 48, 48, hostSpacer, true))
    }

    @Test
    fun hostSpacerWeightRewriteKeepsItsWholeLayoutAndReleasesOurPadding() {
        val state = MiuiBottomInsetCompensation()
        state.reconcile(true, 48, 48, originalSpacer, true)
        assertEquals(MiuiBottomInsetUpdate(48), state.reconcile(
            true, 48, 0, MiuiFullscreenSpacerLayout(0, 2f), true
        ))
    }

    @Test
    fun fullHeightFrameTransitionRestoresOnlyTheSpacerAndNormalExitCanReenter() {
        val state = MiuiBottomInsetCompensation()
        state.reconcile(true, 48, 48, originalSpacer, true)
        assertEquals(MiuiBottomInsetUpdate(spacerLayout = originalSpacer), state.reconcile(
            true, 48, 0, flexibleSpacer, false
        ))
        assertTrue(state.hasAdjustedPadding)
        assertEquals(noChange, state.reconcile(true, 48, 0, originalSpacer, false))
        assertEquals(MiuiBottomInsetUpdate(48), state.reconcile(
            false, 48, 0, originalSpacer, false
        ))
        assertEquals(MiuiBottomInsetUpdate(0, flexibleSpacer), state.reconcile(
            true, 48, 48, originalSpacer, true
        ))
    }

    @Test
    fun contentReplacementReleasesOldContentAndAcceptsTheNewInset() {
        val state = MiuiBottomInsetCompensation()
        state.reconcile(true, 48, 48, originalSpacer, true)
        assertEquals(MiuiBottomInsetUpdate(48, originalSpacer), state.release(0, flexibleSpacer))
        assertEquals(MiuiBottomInsetUpdate(0, flexibleSpacer), state.reconcile(
            true, 72, 72, originalSpacer, true
        ))
        assertEquals(MiuiBottomInsetUpdate(72, originalSpacer), state.release(0, flexibleSpacer))
    }

    @Test
    fun losingEligibilityDoesNotHideAHostRewriteOrAllowAnotherAttempt() {
        val state = MiuiBottomInsetCompensation()
        state.reconcile(true, 48, 48, originalSpacer, true)
        val hostSpacer = MiuiFullscreenSpacerLayout(120, 1f)
        assertEquals(noChange, state.reconcile(
            false, 48, 24, hostSpacer, true
        ))
        assertEquals(noChange, state.reconcile(true, 48, 48, hostSpacer, true))
    }

    @Test
    fun contentReplacementDetectsARewriteBeforeReleasingTheOldContent() {
        val state = MiuiBottomInsetCompensation()
        state.reconcile(true, 48, 48, originalSpacer, true)
        assertEquals(MiuiBottomInsetUpdate(spacerLayout = originalSpacer), state.release(48, flexibleSpacer))
        assertEquals(noChange, state.reconcile(true, 72, 72, originalSpacer, true))
    }

    @Test
    fun contentReplacementAlsoDetectsAHostSpacerRewrite() {
        val state = MiuiBottomInsetCompensation()
        state.reconcile(true, 48, 48, originalSpacer, true)
        assertEquals(MiuiBottomInsetUpdate(48), state.release(0, originalSpacer))
        assertEquals(noChange, state.reconcile(true, 72, 72, originalSpacer, true))
    }

    @Test
    fun currentInsetIsRestoredWhenASpacerConflictCoincidesWithLayoutExit() {
        val state = MiuiBottomInsetCompensation()
        state.reconcile(true, 48, 48, originalSpacer, true)
        assertEquals(MiuiBottomInsetUpdate(72), state.reconcile(
            false, 72, 0, originalSpacer, true
        ))
        assertEquals(noChange, state.reconcile(true, 72, 72, originalSpacer, true))
    }

    @Test
    fun naturallyFlexibleSpacerIsNeverOwnedOrRestored() {
        val state = MiuiBottomInsetCompensation()
        assertEquals(MiuiBottomInsetUpdate(0), state.reconcile(
            true, 48, 48, flexibleSpacer, true
        ))
        assertEquals(noChange, state.reconcile(true, 48, 0, originalSpacer, false))
        assertTrue(state.hasAdjustedPadding)
        assertEquals(MiuiBottomInsetUpdate(48), state.release(0, originalSpacer))
    }
}

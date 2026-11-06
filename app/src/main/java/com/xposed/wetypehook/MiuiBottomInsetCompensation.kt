package com.xposed.wetypehook

internal data class MiuiFullscreenSpacerLayout(val height: Int, val weight: Float)

internal data class MiuiBottomInsetUpdate(
    val paddingBottom: Int? = null,
    val spacerLayout: MiuiFullscreenSpacerLayout? = null
) {
    companion object {
        val NO_CHANGE = MiuiBottomInsetUpdate()
    }
}

/** Owns only the values this frame changed and yields if the host writes them again. */
internal class MiuiBottomInsetCompensation {
    private var originalPaddingBottom: Int? = null
    private var originalSpacerLayout: MiuiFullscreenSpacerLayout? = null
    private var relinquished = false

    val hasAdjustedPadding: Boolean
        get() = originalPaddingBottom != null

    fun reconcile(
        eligible: Boolean,
        navigationInset: Int?,
        paddingBottom: Int?,
        spacerLayout: MiuiFullscreenSpacerLayout?,
        useFlexibleSpacer: Boolean
    ): MiuiBottomInsetUpdate {
        if (relinquished) return MiuiBottomInsetUpdate.NO_CHANGE
        // Keep the current inset available for release even if this traversal exits.
        if (originalPaddingBottom != null && paddingBottom == 0 &&
            navigationInset != null && navigationInset > 0
        ) originalPaddingBottom = navigationInset
        if (wasRewritten(paddingBottom, spacerLayout)) return release(paddingBottom, spacerLayout)
        if (!eligible || navigationInset == null || navigationInset <= 0 ||
            paddingBottom == null || spacerLayout == null
        ) return release(paddingBottom, spacerLayout)
        if (originalPaddingBottom == null && paddingBottom != navigationInset) {
            return MiuiBottomInsetUpdate.NO_CHANGE
        }

        val paddingUpdate = if (originalPaddingBottom == null) 0 else null
        originalPaddingBottom = navigationInset
        val spacerUpdate = if (useFlexibleSpacer) {
            if (spacerLayout != FLEXIBLE_SPACER) {
                originalSpacerLayout = spacerLayout
                FLEXIBLE_SPACER
            } else null
        } else releaseSpacer(spacerLayout)
        return if (paddingUpdate == null && spacerUpdate == null) MiuiBottomInsetUpdate.NO_CHANGE
        else MiuiBottomInsetUpdate(paddingUpdate, spacerUpdate)
    }

    /** A normal layout exit or content replacement may be compensated again later. */
    fun release(
        paddingBottom: Int?,
        spacerLayout: MiuiFullscreenSpacerLayout?
    ): MiuiBottomInsetUpdate {
        if (originalPaddingBottom == null && originalSpacerLayout == null) {
            return MiuiBottomInsetUpdate.NO_CHANGE
        }
        // Check before clearing ownership, including when content or layout changes.
        if (wasRewritten(paddingBottom, spacerLayout)) relinquished = true
        val paddingUpdate = originalPaddingBottom.takeIf { paddingBottom == 0 }
        originalPaddingBottom = null
        val spacerUpdate = releaseSpacer(spacerLayout)
        return if (paddingUpdate == null && spacerUpdate == null) MiuiBottomInsetUpdate.NO_CHANGE
        else MiuiBottomInsetUpdate(paddingUpdate, spacerUpdate)
    }

    private fun wasRewritten(
        paddingBottom: Int?,
        spacerLayout: MiuiFullscreenSpacerLayout?
    ): Boolean = originalPaddingBottom != null && paddingBottom != null && paddingBottom != 0 ||
        originalSpacerLayout != null && spacerLayout != null && spacerLayout != FLEXIBLE_SPACER

    private fun releaseSpacer(current: MiuiFullscreenSpacerLayout?): MiuiFullscreenSpacerLayout? {
        val original = originalSpacerLayout ?: return null
        originalSpacerLayout = null
        // Height and weight form one layout contract. Preserve the host's whole
        // pair if either value changed instead of combining two owners' layouts.
        return original.takeIf { current == FLEXIBLE_SPACER }
    }

    private companion object {
        val FLEXIBLE_SPACER = MiuiFullscreenSpacerLayout(0, 1f)
    }
}

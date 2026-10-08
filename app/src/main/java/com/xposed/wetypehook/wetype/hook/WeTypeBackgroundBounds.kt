package com.xposed.wetypehook.wetype.hook

internal data class WeTypeBackgroundLayout(
    val windowTop: Int,
    val height: Int,
    val isShown: Boolean,
    val isLaidOut: Boolean,
    val isLayoutRequested: Boolean
)

internal data class WeTypeBackgroundBounds(val top: Int, val height: Int)

internal sealed interface WeTypeBackgroundUpdate {
    data object PendingLayout : WeTypeBackgroundUpdate
    data object Hidden : WeTypeBackgroundUpdate
    data class Ready(val bounds: WeTypeBackgroundBounds) : WeTypeBackgroundUpdate
}

/** Returns decor-local bounds only after all visible content has finished layout. */
internal fun resolveWeTypeBackgroundBounds(
    decor: WeTypeBackgroundLayout,
    contents: List<WeTypeBackgroundLayout>
): WeTypeBackgroundBounds? {
    return (resolveWeTypeBackgroundUpdate(decor, contents) as? WeTypeBackgroundUpdate.Ready)?.bounds
}

/** A pending layout keeps the last valid material; actual hidden content removes it. */
internal fun resolveWeTypeBackgroundUpdate(
    decor: WeTypeBackgroundLayout,
    contents: List<WeTypeBackgroundLayout>
): WeTypeBackgroundUpdate {
    if (!decor.isShown) return WeTypeBackgroundUpdate.Hidden
    if (!decor.isLaidOut || decor.isLayoutRequested) return WeTypeBackgroundUpdate.PendingLayout
    if (decor.height <= 0) return WeTypeBackgroundUpdate.Hidden
    var top = decor.height
    for (content in contents) {
        if (!content.isShown) continue
        if (!content.isLaidOut || content.isLayoutRequested) return WeTypeBackgroundUpdate.PendingLayout
        if (content.height <= 0) continue
        val relativeTop = content.windowTop - decor.windowTop
        if (relativeTop >= 0 && relativeTop < top) top = relativeTop
    }
    if (top == decor.height) return WeTypeBackgroundUpdate.Hidden
    return WeTypeBackgroundUpdate.Ready(WeTypeBackgroundBounds(top, decor.height - top))
}

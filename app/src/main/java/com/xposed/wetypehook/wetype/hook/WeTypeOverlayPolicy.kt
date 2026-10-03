package com.xposed.wetypehook.wetype.hook

import kotlin.math.roundToInt

internal enum class WeTypeLayerRole {
    Keyboard, Candidate, CandidateContent, SettingsNavigation, OverlayKeyboard, SettingsTools, SettingsPage
}

/** One frame's host-owned state. No previous visibility is saved or restored. */
internal data class WeTypeLayerSnapshot(
    val id: Int,
    val role: WeTypeLayerRole,
    val ancestors: List<Int>,
    val rendered: Boolean,
    val settingsOwner: Int? = null,
    val opacity: Float = 1f
)

/** IDs follow the host tree's paint order, including sibling Z order. */
internal fun resolveWeTypeDrawMasks(
    layers: List<WeTypeLayerSnapshot>,
    settingsToolsRequested: Boolean?
): Set<Int> = resolveWeTypeDrawAlphas(layers, settingsToolsRequested).keys

/** Reproduce opaque-background occlusion without erasing the host's crossfade. */
internal fun resolveWeTypeDrawAlphas(
    layers: List<WeTypeLayerSnapshot>,
    settingsToolsRequested: Boolean?
): Map<Int, Int> {
    val alphas = mutableMapOf<Int, Int>()
    val overlays = layers.filter { it.role == WeTypeLayerRole.OverlayKeyboard && it.rendered }
    val navigation = layers.filter { it.role == WeTypeLayerRole.SettingsNavigation && it.rendered }
    for (layer in layers) {
        if (!layer.rendered) continue
        if (layer.role == WeTypeLayerRole.Keyboard || layer.role == WeTypeLayerRole.Candidate ||
            layer.role == WeTypeLayerRole.CandidateContent || layer.role == WeTypeLayerRole.OverlayKeyboard) {
            val coveringOverlays = overlays.filter { overlay ->
                    overlay.id > layer.id && layer.id !in overlay.ancestors &&
                        overlay.id !in layer.ancestors
                }
            val coveringOverlay = coveringOverlays.lastOrNull()
            if (coveringOverlay != null) {
                // Settings reuses the candidate bar's navigation. Preserve the bar's
                // ancestors and navigation descendants; mask only disjoint content.
                val sharedNavigation = coveringOverlay.settingsOwner != null &&
                    (layer.role == WeTypeLayerRole.Candidate ||
                        layer.role == WeTypeLayerRole.CandidateContent) && navigation.any {
                        layer.id in it.ancestors || it.id in layer.ancestors
                    }
                if (!sharedNavigation) {
                    val transmission = coveringOverlays.fold(1f) { alpha, overlay ->
                        alpha * (1f - overlay.opacity.coerceIn(0f, 1f))
                    }
                    val alpha = (255f * transmission).roundToInt()
                    if (alpha < 255) alphas[layer.id] = alpha
                }
            }
        }
        if (layer.role == WeTypeLayerRole.SettingsTools) {
            val pages = layers.filter { page ->
                page.role == WeTypeLayerRole.SettingsPage && page.rendered &&
                    page.settingsOwner == layer.settingsOwner
            }
            val alpha = if (pages.isNotEmpty()) {
                (255f * pages.fold(1f) { value, page ->
                    value * (1f - page.opacity.coerceIn(0f, 1f))
                }).roundToInt()
            } else if (settingsToolsRequested == false) 0 else 255
            if (alpha < 255) alphas[layer.id] = alpha
        }
    }
    // One transparent layer per covered branch is sufficient, even for a deep bar.
    return layers.filter { it.id in alphas && it.ancestors.none(alphas::containsKey) }
        .associate { it.id to alphas.getValue(it.id) }
}

/** Keep the native draw call and animation bookkeeping, even for occluded content. */
internal inline fun <T> withWeTypeTransparentDrawing(
    saveTransparentLayer: () -> Int,
    restore: (Int) -> Unit,
    draw: () -> T
): T {
    val checkpoint = saveTransparentLayer()
    return try {
        draw()
    } finally {
        restore(checkpoint)
    }
}

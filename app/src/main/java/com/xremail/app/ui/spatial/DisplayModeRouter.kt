package com.xremail.app.ui.spatial

import androidx.compose.runtime.Composable

/**
 * Routes between headset (SpatialPanel + Material3) and glasses (Glimmer)
 * based on DisplayBlendMode.
 *
 * Glimmer is exclusively for AI Glasses (additive/transparent displays).
 * On the Galaxy XR headset, use standard spatial panels.
 *
 * Production:
 * ```
 * val displayMode = XrDevice.getCurrentDevice(session).getPreferredDisplayBlendMode()
 * if (displayMode == DisplayBlendMode.ADDITIVE) {
 *     GlimmerEmailApp()
 * } else {
 *     SpatialEmailLayout(...)
 * }
 * ```
 */
enum class DisplayMode {
    HEADSET,
    GLASSES_ADDITIVE,
}

object DisplayModeRouter {
    /**
     * Phase 1: always returns HEADSET since we're targeting the XR emulator.
     * Production reads from XrDevice.getCurrentDevice(session).getPreferredDisplayBlendMode().
     */
    fun detect(): DisplayMode = DisplayMode.HEADSET
}

@Composable
fun GlimmerEmailApp() {
    // Phase 2+ stub: Glimmer-based UI for AI Glasses (additive displays).
    // Will use Glimmer components from androidx.xr.compose.glimmer when
    // targeting glasses hardware.
}

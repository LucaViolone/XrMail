package com.xremail.app.ui.spatial

import androidx.compose.animation.core.Spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.offset

/**
 * Lazy head-follow offset.
 *
 * Returns a [DpOffset] that reacts to head yaw with a softly animated spring so
 * the UI "drifts" with the user's head turn instead of being fully head-locked
 * (which feels rigid) or fully world-locked (which makes the panel fall out of
 * view while walking around).
 *
 * Usage pattern: hoist this at the top of the composable, read
 * `lazyOffset.x` / `lazyOffset.y`, and add them onto a panel's existing static
 * [SubspaceModifier.offset]. Example:
 *
 * ```
 * val lazyOffset by rememberLazyFollowOffset()
 * SpatialPanel(
 *     modifier = SubspaceModifier
 *         .width(300.dp).height(180.dp)
 *         .offset(x = 180.dp + lazyOffset.x, y = (-160).dp + lazyOffset.y, z = 30.dp),
 * ) { ... }
 * ```
 *
 * Notes on defaults:
 * - [peripheralBiasX] defaults to 0.dp. The returned offset is purely the
 *   reactive delta from head yaw; each panel keeps its own static base offset.
 *   Pass a non-zero bias only if you want to shift a specific panel off-center
 *   beyond its base offset.
 * - A small [deadZoneDegrees] prevents jitter from tiny head micro-movements,
 *   reinforcing the "lazy lock" feel (the UI doesn't twitch at rest).
 * - On emulator / when [LocalSession] is null, yaw stays at 0 so the UI still
 *   renders stably.
 */
@Composable
fun rememberLazyFollowOffset(
    peripheralBiasX: Dp = 0.dp,
    peripheralBiasY: Dp = 0.dp,
    stiffness: Float = Spring.StiffnessVeryLow,
    dampingRatio: Float = Spring.DampingRatioLowBouncy,
    deadZoneDegrees: Float = 8f,
): State<DpOffset> {
    // Disabled: the previous implementation drove a spring off ArDevice head
    // yaw at 60 Hz, which thrashed recomposition of every SpatialPanel and made
    // the UI feel frozen. The coefficient (4 dp / °) was also far too small to
    // produce a visible head-follow at typical panel distance, so users saw a
    // "stuck in space" panel even when the animation *was* running.
    //
    // True head-following in Jetpack XR needs Orbiter or an Entity pinned to
    // session.scene.spatialUser.head, not a dp offset inside a world-anchored
    // Subspace. See InteractionTierRouter where panels should move into an
    // Orbiter-style layout.
    //
    // Until that refactor lands, return a constant zero so panels are stable
    // and world-locked. Callers can still combine with their static offset.
    return remember(peripheralBiasX, peripheralBiasY) {
        mutableStateOf(DpOffset(peripheralBiasX, peripheralBiasY))
    }
}

/**
 * Convenience extension: add a lazy-follow offset on top of the current chain.
 * Prefer inlining the add (`base + lazyOffset.x`) when a panel already has a
 * static offset so you don't stack two `.offset(...)` calls with different
 * semantics; use this for panels that have no base offset.
 */
fun SubspaceModifier.lazyHeadFollow(offset: DpOffset): SubspaceModifier =
    this.offset(x = offset.x, y = offset.y)

/**
 * Ergonomic wrapper that hoists the offset state for the caller.
 */
@Composable
fun LazyFollowPanelPosition(content: @Composable (offset: DpOffset) -> Unit) {
    val offset by rememberLazyFollowOffset()
    content(offset)
}

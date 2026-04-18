package com.xremail.app.ui.spatial

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.xr.arcore.ArDevice
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.runtime.math.Pose
import kotlin.math.atan2
import kotlin.math.abs

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
    val xrSession = LocalSession.current

    // Live yaw in degrees. Read via withFrameNanos so we resample every frame
    // while the composable is attached; the ArDevice pose itself is a
    // StateFlow, but we avoid collect() on composables to keep this cheap and
    // independent of recomposition cadence.
    var yawDeg by remember { mutableStateOf(0f) }

    LaunchedEffect(xrSession) {
        if (xrSession == null) {
            yawDeg = 0f
            return@LaunchedEffect
        }
        val arDevice = try {
            ArDevice.getInstance(xrSession)
        } catch (t: Throwable) {
            // Device/session may not support head pose (e.g. emulator); keep
            // the offset stable rather than throwing.
            null
        }
        if (arDevice == null) {
            yawDeg = 0f
            return@LaunchedEffect
        }
        arDevice.state.collect { state ->
            val pose: Pose = state.devicePose
            val q = pose.rotation
            // Yaw (rotation about Y) from a quaternion — standard formula.
            val sinyCosp = 2.0 * (q.w * q.y + q.x * q.z)
            val cosyCosp = 1.0 - 2.0 * (q.y * q.y + q.x * q.z)
            val yawRad = atan2(sinyCosp, cosyCosp)
            val deg = Math.toDegrees(yawRad).toFloat()
            yawDeg = if (abs(deg) < deadZoneDegrees) 0f else deg
        }
    }

    // Target X: peripheral bias minus yaw contribution. Coefficient 4f means
    // "every 1 degree of head yaw shifts the UI by 4 dp in the opposite
    // direction". At ~30 dp per 1 degree would feel head-locked; 4 dp gives a
    // subtle drag-behind. Tune freely — this is the main "feel" knob.
    val targetX: Dp = peripheralBiasX - (yawDeg * 4f).dp
    val targetY: Dp = peripheralBiasY

    val animX = animateDpAsState(
        targetValue = targetX,
        animationSpec = spring(dampingRatio = dampingRatio, stiffness = stiffness),
        label = "lazyFollowX",
    )
    val animY = animateDpAsState(
        targetValue = targetY,
        animationSpec = spring(dampingRatio = dampingRatio, stiffness = stiffness),
        label = "lazyFollowY",
    )

    return remember(animX, animY) {
        derivedStateOf { DpOffset(animX.value, animY.value) }
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

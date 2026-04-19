package com.xremail.app.ui.peripheral

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import com.xremail.app.data.Email
import com.xremail.app.tracking.SecondaryHandGestures
import com.xremail.app.ui.notifications.NotificationBanner
import com.xremail.app.ui.theme.XREmailColors
import com.xremail.app.util.XrLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * Gaze-dwell wrapper around [NotificationBanner].
 *
 * The Galaxy XR OS routes eye-gaze hover into Compose hover events on the
 * panel content the user is looking at — there's no app-side raw-gaze stream
 * (and there shouldn't be, for privacy). We use [Modifier.hoverable] to listen
 * for that hover and, after [DWELL_MS] of continuous hover, fire [onExpand].
 *
 * To prevent the "click immediately collapses what you just opened" footgun
 * that killed the prior gaze implementation, the dwell timer is suppressed
 * for [POST_INTERACTION_LOCKOUT_MS] after any pinch / click reported through
 * [SecondaryHandGestures.lastInteractionMs]. This is the input-lockout the
 * gap-analysis spec'd as the precondition for re-enabling gaze.
 *
 * A subtle progress ring is drawn around the entire banner during dwell so
 * the user has clear feedback that "the system noticed I'm looking, and is
 * about to act" — never a silent surprise expansion.
 */
@Composable
fun GazeDwellNotificationBanner(
    emails: List<Email>,
    lastInteractionMs: StateFlow<Long>,
    onBumpInteraction: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    var dwellProgress by remember { mutableFloatStateOf(0f) }
    var hoverStartMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isHovered) {
        if (isHovered) {
            hoverStartMs = System.currentTimeMillis()
            XrLog.d(TAG, "hover ENTER on NotificationBanner; dwell timer armed")

            while (isHovered) {
                val now = System.currentTimeMillis()
                // Read directly from the StateFlow each tick so a `bumpInteraction`
                // landing AFTER hover-enter still suppresses dwell. (Reviewer
                // fix: the old `collectAsStateLong()` snapshot captured the
                // value at hover-enter time, so a pinch happening 50ms in would
                // have been ignored.)
                val sinceInteraction = now - lastInteractionMs.value
                if (sinceInteraction < POST_INTERACTION_LOCKOUT_MS) {
                    XrLog.v(TAG, "  dwell suppressed (post-interaction lockout " +
                        "${POST_INTERACTION_LOCKOUT_MS - sinceInteraction}ms remaining)")
                    hoverStartMs = now
                    dwellProgress = 0f
                } else {
                    val elapsed = now - hoverStartMs
                    dwellProgress = (elapsed.toFloat() / DWELL_MS).coerceIn(0f, 1f)
                    if (elapsed >= DWELL_MS) {
                        XrLog.i(TAG, "DWELL FIRE after ${elapsed}ms -> onExpand()")
                        onBumpInteraction()
                        onExpand()
                        dwellProgress = 0f
                        break
                    }
                }
                delay(POLL_MS)
            }
        } else {
            if (dwellProgress > 0f) {
                XrLog.v(TAG, "hover EXIT before dwell complete (was at " +
                    "${(dwellProgress * 100).toInt()}%)")
            }
            dwellProgress = 0f
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = dwellProgress,
        animationSpec = tween(durationMillis = 80),
        label = "dwellProgress",
    )

    val ringShape = RoundedCornerShape(BANNER_CORNER_DP.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .hoverable(interactionSource = interactionSource)
            .clip(ringShape)
            .drawWithContent {
                drawContent()
                if (animatedProgress > 0f) {
                    drawDwellRing(animatedProgress)
                }
            },
    ) {
        NotificationBanner(
            emails = emails,
            onExpand = {
                XrLog.d(TAG, "click on NotificationBanner -> onExpand()")
                onBumpInteraction()
                onExpand()
            },
        )
    }
}

/**
 * Renders the dwell-progress ring as an arc that traces the rounded-rectangle
 * silhouette of the banner. The outer `Modifier.clip(RoundedCornerShape(...))`
 * already clips the stroke to the same shape so the ring naturally rounds at
 * the corners — no manual path math required, and clipping guarantees we never
 * see a sharp-cornered overdraw on a pill-shaped banner.
 */
private fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.drawDwellRing(
    progress: Float,
) {
    val strokePx = 3.dp.toPx()
    val inset = strokePx / 2f
    val arcSize = Size(
        width = size.width - inset * 2,
        height = size.height - inset * 2,
    )
    val topLeft = Offset(inset, inset)

    rotate(degrees = -90f, pivot = Offset(size.width / 2, size.height / 2)) {
        drawArc(
            color = XREmailColors.aiAccent.copy(alpha = 0.65f),
            startAngle = 0f,
            sweepAngle = 360f * progress,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx),
        )
    }
}

private const val TAG = "GazeDwell"
private const val DWELL_MS = 400L
private const val POST_INTERACTION_LOCKOUT_MS = 800L
private const val POLL_MS = 32L
// Matches NotificationBanner's outer corner radius. Kept in sync manually —
// if the banner shape ever changes, update this so the dwell ring still
// hugs the silhouette.
private const val BANNER_CORNER_DP = 16

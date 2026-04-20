package com.xremail.app.ui.peripheral

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xremail.app.ui.theme.XREmailColors

/**
 * Visible affordance for the pinch-hold expand gesture. Mirror of
 * [CollapseAffordance] but for forward tier escalation.
 *
 * Why a ring? A pinch-HOLD has ambiguous duration: the user can't tell
 * whether they've held long enough for PINCH_HOLD_EXPAND to fire. The
 * ring fills 0f → 1f over [com.xremail.app.tracking.SecondaryHandGestures.PINCH_HOLD_DURATION_MS]
 * (currently 550 ms) so the user sees real-time recognition — same
 * feedback model the OS uses for its own system-level long-press
 * gestures.
 *
 * A quick PINCH_SELECT tap doesn't trigger this ring (and doesn't
 * need to — taps resolve in a single frame and the tier transition
 * itself is the feedback).
 *
 * Color-coded blue/primary to contrast with the red/high-priority
 * CollapseAffordance so the two gestures are visually unambiguous
 * when they happen to be on screen at the same time.
 */
@Composable
fun ExpandAffordance(
    progress: Float,
    label: String = "Pinch + hold to expand",
    modifier: Modifier = Modifier,
) {
    val animProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 80),
        label = "expandProgress",
    )

    val ringColor = XREmailColors.primary
    val idleColor = XREmailColors.onSurfaceDim

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(XREmailColors.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier.size(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(22.dp)) {
                val stroke = 2.5.dp.toPx()
                val diameter = size.minDimension - stroke
                val topLeft = Offset(stroke / 2f, stroke / 2f)
                val ringSize = Size(diameter, diameter)
                drawArc(
                    color = idleColor.copy(alpha = 0.35f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = ringSize,
                    style = Stroke(width = stroke),
                )
                if (animProgress > 0f) {
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * animProgress,
                        useCenter = false,
                        topLeft = topLeft,
                        size = ringSize,
                        style = Stroke(width = stroke),
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.OpenInFull,
                contentDescription = "Expand",
                tint = if (animProgress > 0f) ringColor else idleColor,
                modifier = Modifier.size(12.dp),
            )
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = if (animProgress > 0.05f) "Expanding..." else label,
            style = MaterialTheme.typography.labelSmall,
            color = if (animProgress > 0.05f) ringColor else XREmailColors.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}

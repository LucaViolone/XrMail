package com.xremail.app.ui.peripheral

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.xremail.app.ui.theme.XREmailColors

/**
 * Visible affordance for the REVERSE-PINCH expand gesture. Mirror of
 * [CollapseAffordance] but for forward tier escalation.
 *
 * Why a ring? Reverse-pinch has an ambiguous threshold: the user can't
 * tell how wide they've spread their fingers yet. The ring fills 0f → 1f
 * as thumb-index distance grows from the compressed pose toward the
 * spread threshold, so the user sees real-time progress — same feedback
 * model the OS uses for its own system-level long-press gestures.
 *
 * Color-coded blue/primary to contrast with the red/high-priority
 * [CollapseAffordance] so the two gestures are visually unambiguous
 * when they happen to be on screen at the same time.
 */
@Composable
fun ExpandAffordance(
    progress: Float,
    @Suppress("UNUSED_PARAMETER") label: String = "",
    modifier: Modifier = Modifier,
) {
    val animProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 80),
        label = "expandProgress",
    )

    val ringColor = XREmailColors.primary
    val idleColor = XREmailColors.onSurfaceDim

    Box(
        modifier = modifier
            .size(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(XREmailColors.surfaceVariant.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
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
            contentDescription = "Expand (reverse-pinch spread)",
            tint = if (animProgress > 0f) ringColor else idleColor,
            modifier = Modifier.size(12.dp),
        )
    }
}

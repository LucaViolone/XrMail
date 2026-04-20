package com.xremail.app.ui.peripheral

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PanTool
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
 * Visible affordance for the closed-fist-hold collapse gesture. Icon-only
 * pill (no text label) — the ring fills [progress] 0f → 1f while the user
 * holds a fist, and the fist icon makes the gesture self-explanatory.
 *
 * Dropped the text label 2026-04-19: on a 440dp peripheral panel, the
 * expand-pill + collapse-pill + voice-prompt header row was overflowing
 * and crowding out the content beneath. Icons alone carry the same
 * meaning at a fraction of the width and let the list of emails
 * actually own the panel.
 */
@Composable
fun CollapseAffordance(
    progress: Float,
    @Suppress("UNUSED_PARAMETER") label: String = "",
    modifier: Modifier = Modifier,
) {
    val animProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 80),
        label = "collapseProgress",
    )

    val ringColor = XREmailColors.priorityHigh
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
            imageVector = Icons.Default.PanTool,
            contentDescription = "Collapse (closed-fist hold)",
            tint = if (animProgress > 0f) ringColor else idleColor,
            modifier = Modifier.size(12.dp),
        )
    }
}

package com.xremail.app.ui.peripheral

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PanTool
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
 * Visible affordance for the open-palm-hold collapse gesture. Lives at the
 * top of every non-AMBIENT peripheral tier so the user always knows:
 *
 *   1. THAT a collapse gesture exists (vs. having to discover it).
 *   2. WHAT the gesture is (open palm, distinct from pinch).
 *   3. HOW MUCH MORE they have to hold (the ring fills as they hold).
 *
 * The ring fills based on [progress] (0f → 1f), driven by
 * [com.xremail.app.tracking.SecondaryHandGestures.openPalmProgress].
 * When it hits 1f the gesture has fired and the panel collapses one tier.
 */
@Composable
fun CollapseAffordance(
    progress: Float,
    label: String = "Hold open palm to collapse",
    modifier: Modifier = Modifier,
) {
    val animProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 80),
        label = "collapseProgress",
    )

    val ringColor = XREmailColors.priorityHigh
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
                imageVector = Icons.Default.PanTool,
                contentDescription = "Collapse",
                tint = if (animProgress > 0f) ringColor else idleColor,
                modifier = Modifier.size(12.dp),
            )
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = if (animProgress > 0.05f) "Collapsing..." else label,
            style = MaterialTheme.typography.labelSmall,
            color = if (animProgress > 0.05f) ringColor else XREmailColors.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}

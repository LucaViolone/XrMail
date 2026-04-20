package com.xremail.app.ui.notifications

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xremail.app.data.Email
import com.xremail.app.data.Priority
import com.xremail.app.ui.theme.XREmailColors

/**
 * Compact notification card for the gaze-expanded notification stack.
 * Designed for peripheral use while walking — high contrast, large touch
 * targets, swipeable for quick archive/snooze without entering Inbox.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCard(
    email: Email,
    isHighlighted: Boolean,
    onSelect: () -> Unit,
    onArchive: () -> Unit,
    onSnooze: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightScale by animateFloatAsState(
        targetValue = if (isHighlighted) 1.03f else 1f,
        animationSpec = tween(200),
        label = "cardHighlight",
    )

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onArchive(); true }
                SwipeToDismissBoxValue.EndToStart -> { onSnooze(); true }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    // CRITICAL: the clickable WRAPS the SwipeToDismissBox here (not the
    // inner content). SwipeToDismissBox's AnchoredDraggable installs a
    // pointer-input modifier that consumes the gaze+pinch DOWN event
    // before it can reach a clickable child — the user reported this
    // as "pinching to click doesn't open or expand anything" on the
    // notification cards. With the clickable on the outside, the OS
    // gaze+pinch click pipeline lands ON the outer Modifier and fires
    // before the dismiss state ever sees the pointer. Drag still works
    // because horizontal motion bypasses the simple-click recognizer.
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .scale(highlightScale)
            .clickable(onClick = onSelect),
    ) {
    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val bgColor by animateColorAsState(
                targetValue = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> XREmailColors.secondary.copy(alpha = 0.25f)
                    SwipeToDismissBoxValue.EndToStart -> XREmailColors.tertiary.copy(alpha = 0.25f)
                    else -> Color.Transparent
                },
                label = "notifSwipeBg",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(bgColor)
                    .padding(horizontal = 16.dp),
            ) {
                if (direction == SwipeToDismissBoxValue.StartToEnd) {
                    Icon(
                        Icons.Default.Archive,
                        contentDescription = "Archive",
                        tint = XREmailColors.secondary,
                        modifier = Modifier.size(20.dp).align(Alignment.CenterStart),
                    )
                }
                if (direction == SwipeToDismissBoxValue.EndToStart) {
                    Icon(
                        Icons.Default.Snooze,
                        contentDescription = "Snooze",
                        tint = XREmailColors.tertiary,
                        modifier = Modifier.size(20.dp).align(Alignment.CenterEnd),
                    )
                }
            }
        },
    ) {
        NotificationCardContent(
            email = email,
            isHighlighted = isHighlighted,
            onClick = onSelect,
        )
    }
    }
}

@Composable
private fun NotificationCardContent(
    email: Email,
    isHighlighted: Boolean,
    @Suppress("UNUSED_PARAMETER") onClick: () -> Unit,
) {
    val priorityColor = when (email.priority) {
        Priority.HIGH -> XREmailColors.priorityHigh
        Priority.MEDIUM -> XREmailColors.priorityMedium
        Priority.LOW -> XREmailColors.priorityLow
        Priority.IGNORE -> XREmailColors.onSurfaceDim
    }

    // PINCH-TO-OPEN: a single deliberate pinch on a card opens that email
    // straight into FOCUS view. The actual click handler now lives on
    // the OUTER NotificationCard wrapper (above the SwipeToDismissBox)
    // so the gaze+pinch event lands BEFORE the dismiss draggable can
    // consume it — see the long comment in NotificationCard for why.
    // The `onClick` parameter is kept in the signature to avoid churning
    // the call sites that currently pass it through.
    val bgColor = when {
        isHighlighted -> XREmailColors.surfaceElevated
        else -> XREmailColors.surfaceVariant.copy(alpha = 0.85f)
    }

    // INNER clickable removed — the parent Box (in NotificationCard
    // above) is now the click target so it lands BEFORE the
    // SwipeToDismissBox's pointer-consuming draggable. Keeping a
    // second clickable here would race the outer one on every touch
    // and recompose, with one of them winning at random.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(priorityColor),
        )

        Spacer(Modifier.width(10.dp))

        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(priorityColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = email.sender.take(1).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = priorityColor,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = email.sender.split(" ").firstOrNull() ?: email.sender,
                    style = MaterialTheme.typography.labelMedium,
                    color = XREmailColors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = email.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = XREmailColors.onSurfaceDim,
                )
            }

            Text(
                text = email.aiSummary,
                style = MaterialTheme.typography.bodySmall,
                color = XREmailColors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Pulsing indicator for HIGH priority
        if (email.priority == Priority.HIGH) {
            Spacer(Modifier.width(8.dp))
            PriorityPulse(color = priorityColor)
        }
    }
}

@Composable
private fun PriorityPulse(color: Color) {
    val transition = rememberInfiniteTransition(label = "priorityPulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color),
    )
}

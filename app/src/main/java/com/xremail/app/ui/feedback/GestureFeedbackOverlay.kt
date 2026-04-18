package com.xremail.app.ui.feedback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.xremail.app.tracking.SecondaryHandGestures
import com.xremail.app.ui.theme.XREmailColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow

/**
 * Foveal-zone confirmation pill that appears briefly when a gesture fires.
 *
 * Solves the "frozen after expansion" UX issue: after any gesture is
 * interpreted the user sees a short visual + haptic confirmation so they
 * know the system registered their input, even when the action itself is a
 * no-op (e.g. pinch in FOCUS) or a tier-only transition.
 */
@Composable
fun GestureFeedbackOverlay(
    gestures: SharedFlow<SecondaryHandGestures.Gesture>,
    modifier: Modifier = Modifier,
) {
    var currentGesture by remember { mutableStateOf<SecondaryHandGestures.Gesture?>(null) }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        gestures.collect { gesture ->
            currentGesture = gesture
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    LaunchedEffect(currentGesture) {
        if (currentGesture != null) {
            delay(400L)
            currentGesture = null
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = currentGesture != null,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            currentGesture?.let { gesture ->
                FeedbackPill(
                    icon = iconFor(gesture),
                    label = labelFor(gesture),
                )
            }
        }
    }
}

@Composable
private fun FeedbackPill(icon: ImageVector, label: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(XREmailColors.surfaceElevated.copy(alpha = 0.92f))
            .border(
                width = 1.dp,
                color = XREmailColors.onSurfaceDim.copy(alpha = 0.35f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = XREmailColors.onSurface,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = XREmailColors.onSurface,
        )
    }
}

private fun iconFor(gesture: SecondaryHandGestures.Gesture): ImageVector = when (gesture) {
    SecondaryHandGestures.Gesture.SWIPE_LEFT_ARCHIVE -> Icons.Default.Archive
    SecondaryHandGestures.Gesture.SWIPE_RIGHT_SNOOZE -> Icons.Default.AccessTime
    SecondaryHandGestures.Gesture.SWIPE_UP_STAR -> Icons.Default.Star
    SecondaryHandGestures.Gesture.SWIPE_DOWN_DISMISS -> Icons.Default.KeyboardArrowDown
    SecondaryHandGestures.Gesture.PINCH_SELECT -> Icons.Default.TouchApp
    SecondaryHandGestures.Gesture.PINCH_HOLD_EXPAND -> Icons.Default.OpenInFull
}

private fun labelFor(gesture: SecondaryHandGestures.Gesture): String = when (gesture) {
    SecondaryHandGestures.Gesture.SWIPE_LEFT_ARCHIVE -> "Archived"
    SecondaryHandGestures.Gesture.SWIPE_RIGHT_SNOOZE -> "Snoozed"
    SecondaryHandGestures.Gesture.SWIPE_UP_STAR -> "Starred"
    SecondaryHandGestures.Gesture.SWIPE_DOWN_DISMISS -> "Dismissed"
    SecondaryHandGestures.Gesture.PINCH_SELECT -> "Select"
    SecondaryHandGestures.Gesture.PINCH_HOLD_EXPAND -> "Expand"
}

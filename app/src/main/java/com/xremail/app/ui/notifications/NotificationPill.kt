package com.xremail.app.ui.notifications

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xremail.app.ui.theme.XREmailColors

@Composable
fun NotificationPill(
    unreadCount: Int,
    hasHighPriority: Boolean = false,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = unreadCount > 0,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = modifier,
    ) {
        val pulseScale = if (hasHighPriority) {
            val transition = rememberInfiniteTransition(label = "pillPulse")
            val scale by transition.animateFloat(
                initialValue = 1f,
                targetValue = 1.08f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "pillScale",
            )
            scale
        } else {
            1f
        }

        Row(
            modifier = Modifier
                .scale(pulseScale)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    if (hasHighPriority) XREmailColors.priorityHigh.copy(alpha = 0.2f)
                    else XREmailColors.primary.copy(alpha = 0.15f)
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Mail,
                contentDescription = null,
                tint = if (hasHighPriority) XREmailColors.priorityHigh else XREmailColors.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "$unreadCount new",
                style = MaterialTheme.typography.labelMedium,
                color = if (hasHighPriority) XREmailColors.priorityHigh else XREmailColors.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

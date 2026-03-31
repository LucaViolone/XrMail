package com.xremail.app.ui.peripheral

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xremail.app.ui.theme.XREmailColors
import com.xremail.app.viewmodel.VoiceDraft
import com.xremail.app.voice.VoiceComposeManager

@Composable
fun VoiceComposeOverlay(
    draft: VoiceDraft?,
    composeState: VoiceComposeManager.ComposeState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = draft != null && composeState != VoiceComposeManager.ComposeState.IDLE,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        if (draft == null) return@AnimatedVisibility

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(XREmailColors.surface.copy(alpha = 0.95f))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "To: ${draft.recipientName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = XREmailColors.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                ComposeStateIndicator(state = composeState)
            }

            Text(
                text = draft.subject,
                style = MaterialTheme.typography.bodySmall,
                color = XREmailColors.onSurfaceDim,
            )

            if (draft.draftText.isNotBlank()) {
                Text(
                    text = draft.draftText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = XREmailColors.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(XREmailColors.surfaceVariant)
                        .padding(12.dp),
                )
            }

            if (draft.isGenerating) {
                Text(
                    text = "Generating draft...",
                    style = MaterialTheme.typography.labelSmall,
                    color = XREmailColors.aiAccent,
                    fontStyle = FontStyle.Italic,
                )
            }

            val promptText = when (composeState) {
                VoiceComposeManager.ComposeState.LISTENING -> "Listening... describe your reply"
                VoiceComposeManager.ComposeState.GENERATING -> "AI is drafting..."
                VoiceComposeManager.ComposeState.READING_BACK -> "Reading draft aloud..."
                VoiceComposeManager.ComposeState.AWAITING_CONFIRM ->
                    "Pinch to send · Say \"edit\" to revise"
                VoiceComposeManager.ComposeState.IDLE -> ""
            }

            if (promptText.isNotBlank()) {
                Text(
                    text = promptText,
                    style = MaterialTheme.typography.labelSmall,
                    color = XREmailColors.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ComposeStateIndicator(
    state: VoiceComposeManager.ComposeState,
) {
    val isActive = state == VoiceComposeManager.ComposeState.LISTENING ||
        state == VoiceComposeManager.ComposeState.GENERATING

    if (!isActive) return

    val pulse = rememberInfiniteTransition(label = "composePulse")
    val alpha by pulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "composeAlpha",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.alpha(alpha),
    ) {
        Icon(
            Icons.Default.Mic,
            contentDescription = null,
            tint = if (state == VoiceComposeManager.ComposeState.LISTENING)
                XREmailColors.secondary else XREmailColors.aiAccent,
            modifier = Modifier.size(14.dp),
        )
    }
}

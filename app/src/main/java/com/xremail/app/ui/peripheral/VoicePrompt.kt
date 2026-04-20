package com.xremail.app.ui.peripheral

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xremail.app.ui.theme.XREmailColors
import com.xremail.app.voice.PushToTalkSession

/**
 * Push-to-talk voice indicator and trigger.
 *
 * Tap to start a round, tap again to stop. The chip mirrors
 * [PushToTalkSession.State] so the user always knows which stage of the
 * voice turn is happening.
 *
 *   IDLE       -> "Tap to talk"               (green pulse, tappable)
 *   LISTENING  -> "Listening... tap to stop"  (red pulse, tappable)
 *   THINKING   -> "Thinking..."               (amber pulse, not tappable)
 *   SPEAKING   -> "Speaking..."               (muted icon, not tappable)
 *   ERROR      -> "Voice error — tap to retry" (red, tappable)
 *
 * Under the old Live API + local always-on recognizer this was a
 * three-state overlay that tried to describe two parallel subsystems.
 * Now there's exactly one voice pipeline and the chip can show its
 * literal state.
 */
@Composable
fun VoicePrompt(
    state: PushToTalkSession.State,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val visual = remember(state) { computeVisual(state) }

    val pulseAlpha by rememberInfiniteTransition(label = "voicePulse").animateFloat(
        initialValue = if (visual.pulse) 0.45f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voicePulseAlpha",
    )
    val effectiveAlpha = if (visual.pulse) pulseAlpha else 1f

    val rowModifier = modifier
        .clip(RoundedCornerShape(if (compact) 14.dp else 18.dp))
        .background(visual.bgColor)
        .let { base ->
            if (visual.tappable) base.clickable(onClick = onToggle) else base
        }
        .padding(
            horizontal = if (compact) 10.dp else 14.dp,
            vertical = if (compact) 6.dp else 10.dp,
        )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = rowModifier,
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 14.dp else 20.dp)
                .alpha(effectiveAlpha),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (visual.muted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = visual.label,
                tint = visual.iconColor,
                modifier = Modifier.size(if (compact) 14.dp else 20.dp),
            )
        }

        Spacer(Modifier.width(if (compact) 6.dp else 10.dp))

        AnimatedContent(
            targetState = visual.label,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
            label = "voiceLabel",
        ) { label ->
            Text(
                text = label,
                style = if (compact) MaterialTheme.typography.labelSmall
                    else MaterialTheme.typography.labelMedium,
                color = visual.textColor,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private data class VoiceVisual(
    val label: String,
    val iconColor: Color,
    val textColor: Color,
    val bgColor: Color,
    val pulse: Boolean,
    val muted: Boolean,
    val tappable: Boolean,
)

private fun computeVisual(state: PushToTalkSession.State): VoiceVisual = when (state) {
    PushToTalkSession.State.IDLE -> VoiceVisual(
        label = "Tap to talk",
        iconColor = XREmailColors.aiAccent,
        textColor = XREmailColors.onSurface,
        bgColor = XREmailColors.aiAccent.copy(alpha = 0.18f),
        pulse = true,
        muted = false,
        tappable = true,
    )

    PushToTalkSession.State.LISTENING -> VoiceVisual(
        label = "Listening... tap to stop",
        iconColor = XREmailColors.priorityHigh,
        textColor = XREmailColors.onSurface,
        bgColor = XREmailColors.priorityHigh.copy(alpha = 0.2f),
        pulse = true,
        muted = false,
        tappable = true,
    )

    PushToTalkSession.State.THINKING -> VoiceVisual(
        label = "Thinking...",
        iconColor = XREmailColors.priorityMedium,
        textColor = XREmailColors.onSurface,
        bgColor = XREmailColors.priorityMedium.copy(alpha = 0.18f),
        pulse = true,
        muted = false,
        tappable = false,
    )

    PushToTalkSession.State.SPEAKING -> VoiceVisual(
        label = "Speaking...",
        iconColor = XREmailColors.primary,
        textColor = XREmailColors.onSurface,
        bgColor = XREmailColors.primary.copy(alpha = 0.15f),
        pulse = false,
        muted = false,
        tappable = false,
    )

    PushToTalkSession.State.ERROR -> VoiceVisual(
        label = "Voice error — tap to retry",
        iconColor = XREmailColors.priorityHigh,
        textColor = XREmailColors.priorityHigh,
        bgColor = XREmailColors.priorityHigh.copy(alpha = 0.14f),
        pulse = false,
        muted = true,
        tappable = true,
    )
}

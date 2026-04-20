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
 * Voice chip indicator and trigger.
 *
 * Pure push-to-talk: each tap opens a single recognizer round. A tap
 * during SPEAKING or THINKING interrupts the in-flight utterance / reply
 * and immediately opens a fresh round — no hands-free listening, so
 * there is no startup-beep-per-turn and no TTS-echo barge-in misfires
 * (both unusable on the Galaxy XR on-device recognizer).
 *
 * Labels by state:
 *
 *   IDLE       -> "Tap to talk"
 *   LISTENING  -> "Listening... tap to stop" (red pulse)
 *   THINKING   -> "Thinking... tap to cancel"
 *   SPEAKING   -> "Speaking... tap to interrupt"
 *   ERROR      -> "Voice error — tap to retry"
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

private fun computeVisual(
    state: PushToTalkSession.State,
): VoiceVisual = when (state) {
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
        label = "Thinking... tap to cancel",
        iconColor = XREmailColors.priorityMedium,
        textColor = XREmailColors.onSurface,
        bgColor = XREmailColors.priorityMedium.copy(alpha = 0.18f),
        pulse = true,
        muted = false,
        // Tap must always be available — a stuck Gemini request
        // would otherwise leave the chip unresponsive.
        tappable = true,
    )

    PushToTalkSession.State.SPEAKING -> VoiceVisual(
        // Tap-to-interrupt is the replacement for the old always-on
        // barge-in mic. User hits the chip to stop the agent and start
        // their own turn — no audible recognizer startup beep like the
        // auto-opened barge-in had.
        label = "Speaking... tap to interrupt",
        iconColor = XREmailColors.primary,
        textColor = XREmailColors.onSurface,
        bgColor = XREmailColors.primary.copy(alpha = 0.15f),
        pulse = false,
        muted = false,
        tappable = true,
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

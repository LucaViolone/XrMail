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
import com.xremail.app.voice.GeminiLiveManager
import com.xremail.app.voice.LocalCommandRecognizer

/**
 * Always-visible voice indicator. Tells the user EXACTLY what the voice
 * subsystem is doing right now and how to invoke it.
 *
 * Visual contract by composite state:
 *
 *   Gemini.LISTENING                  -> animated red mic + "Listening..."
 *                                        (Gemini Live mic is open, model
 *                                        will respond when user speaks)
 *
 *   Gemini.CONNECTING                 -> pulsing amber mic + "Connecting..."
 *
 *   Local.LISTENING + Gemini.CONNECTED -> steady green mic + "Say 'Hey Gemini'"
 *                                         (the normal idle-but-ready state)
 *
 *   Local.PAUSED                      -> dim mic + "Voice paused"
 *                                         (recognizer paused, e.g. while
 *                                         Gemini owns the mic)
 *
 *   Gemini.ERROR or Local.ERROR       -> red MicOff + "Voice error"
 *
 *   else (initial / unknown)          -> gray mic + "Voice off"
 *
 * The user almost always sees "Say 'Hey Gemini'" — the explicit cue that
 * was missing in the previous build, where the only voice signal was a
 * mic icon that disappeared the moment voice wasn't active. The user
 * had no way to know voice was even an option.
 */
@Composable
fun VoicePrompt(
    voiceState: GeminiLiveManager.SessionState,
    localState: LocalCommandRecognizer.State,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onSummonGemini: (() -> Unit)? = null,
) {
    val visual = remember(voiceState, localState) { computeVisual(voiceState, localState) }

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

    // Pinch-to-summon: in any state where Gemini Live is reachable but
    // not already streaming, a tap opens the mic immediately. This is
    // the manual escape hatch when the local wake-word recognizer is
    // broken (Galaxy XR's on-device service silently fails — the
    // VoicePrompt becomes the user's only way in).
    // Tap-to-summon is available whenever the live session is reachable
    // and we're not already streaming — including the IDLE case where
    // ALWAYS_ON_LOCAL_VOICE_ENABLED is off (no wake-word, tap is the
    // ONLY way in). Without IDLE in this list the user is stuck staring
    // at "Voice off" with no way to talk to Gemini.
    val canSummon = onSummonGemini != null && (
        voiceState == GeminiLiveManager.SessionState.CONNECTED ||
            voiceState == GeminiLiveManager.SessionState.ERROR ||
            localState == LocalCommandRecognizer.State.ERROR ||
            localState == LocalCommandRecognizer.State.IDLE
    )

    val rowModifier = modifier
        .clip(RoundedCornerShape(if (compact) 14.dp else 18.dp))
        .background(visual.bgColor)
        .let { base ->
            if (canSummon && onSummonGemini != null) {
                base.clickable(onClick = onSummonGemini)
            } else {
                base
            }
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
)

private fun computeVisual(
    voice: GeminiLiveManager.SessionState,
    local: LocalCommandRecognizer.State,
): VoiceVisual = when {
    voice == GeminiLiveManager.SessionState.LISTENING -> VoiceVisual(
        label = "Listening...",
        iconColor = XREmailColors.priorityHigh,
        textColor = XREmailColors.onSurface,
        bgColor = XREmailColors.priorityHigh.copy(alpha = 0.18f),
        pulse = true,
        muted = false,
    )

    voice == GeminiLiveManager.SessionState.CONNECTING -> VoiceVisual(
        label = "Connecting...",
        iconColor = XREmailColors.priorityMedium,
        textColor = XREmailColors.onSurface,
        bgColor = XREmailColors.priorityMedium.copy(alpha = 0.15f),
        pulse = true,
        muted = false,
    )

    // Local recognizer dead OR intentionally disabled (ALWAYS_ON_LOCAL_VOICE_ENABLED=false)
    // but Gemini Live is up: show "Tap to talk" so the user has a clear
    // manual path. We pulse it so it's actually noticeable in the
    // peripheral HUD — without the pulse the user assumes voice is off.
    voice == GeminiLiveManager.SessionState.CONNECTED && (
        local == LocalCommandRecognizer.State.ERROR ||
            local == LocalCommandRecognizer.State.IDLE
    ) -> VoiceVisual(
        label = "Tap to talk",
        iconColor = XREmailColors.aiAccent,
        textColor = XREmailColors.onSurface,
        bgColor = XREmailColors.aiAccent.copy(alpha = 0.18f),
        pulse = true,
        muted = false,
    )

    voice == GeminiLiveManager.SessionState.ERROR ||
        local == LocalCommandRecognizer.State.ERROR -> VoiceVisual(
        label = "Voice error",
        iconColor = XREmailColors.priorityHigh,
        textColor = XREmailColors.priorityHigh,
        bgColor = XREmailColors.priorityHigh.copy(alpha = 0.12f),
        pulse = false,
        muted = true,
    )

    voice == GeminiLiveManager.SessionState.CONNECTED &&
        local == LocalCommandRecognizer.State.LISTENING -> VoiceVisual(
        label = "Say \"Hey Gemini\"",
        iconColor = XREmailColors.aiAccent,
        textColor = XREmailColors.onSurface,
        bgColor = XREmailColors.aiAccent.copy(alpha = 0.14f),
        pulse = false,
        muted = false,
    )

    voice == GeminiLiveManager.SessionState.CONNECTED &&
        local == LocalCommandRecognizer.State.STARTING -> VoiceVisual(
        label = "Voice starting…",
        iconColor = XREmailColors.priorityMedium,
        textColor = XREmailColors.onSurface,
        bgColor = XREmailColors.priorityMedium.copy(alpha = 0.12f),
        pulse = true,
        muted = false,
    )

    local == LocalCommandRecognizer.State.PAUSED -> VoiceVisual(
        label = "Voice paused",
        iconColor = XREmailColors.onSurfaceDim,
        textColor = XREmailColors.onSurfaceDim,
        bgColor = XREmailColors.surfaceVariant.copy(alpha = 0.6f),
        pulse = false,
        muted = false,
    )

    else -> VoiceVisual(
        label = "Voice off",
        iconColor = XREmailColors.onSurfaceDim,
        textColor = XREmailColors.onSurfaceDim,
        bgColor = XREmailColors.surfaceVariant.copy(alpha = 0.5f),
        pulse = false,
        muted = true,
    )
}

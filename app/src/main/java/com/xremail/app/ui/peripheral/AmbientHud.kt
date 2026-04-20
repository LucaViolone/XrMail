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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xremail.app.data.Email
import com.xremail.app.ui.notifications.NotificationBanner
import com.xremail.app.ui.theme.XREmailColors
import com.xremail.app.viewmodel.ToastMessage
import com.xremail.app.voice.GeminiLiveManager
import com.xremail.app.voice.TTSManager
import kotlinx.coroutines.delay

/**
 * Peripheral HUD for walking/on-the-go use.
 * Shows: notification banner (iPhone-style with sender preview + count),
 * TTS progress, voice status, and toasts.
 *
 * The banner is plain-clickable (pinch in XR, tap on emulator) — gaze-dwell
 * expansion was removed per user feedback. Tier expansion is now exclusively
 * driven by the explicit pinch gesture on the banner OR the global
 * PINCH_HOLD_EXPAND / PINCH_SELECT hand gesture (see GestureToActionMapper).
 */
@Composable
fun AmbientHud(
    unreadCount: Int,
    hasHighPriority: Boolean,
    emails: List<Email>,
    ttsState: TTSManager.PlaybackState,
    ttsProgress: Float,
    voiceState: GeminiLiveManager.SessionState,
    toastMessage: ToastMessage?,
    onExpandToNotifications: () -> Unit,
    onDismissToast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Visual chrome stripped here on 2026-04-19: this Column used to wrap
    // everything in its own Surface (rounded corner + alpha background +
    // 16dp padding) on top of the SpatialPanel's outer Surface AND the
    // NotificationBanner's own Surface — three nested rounded panels with
    // ~70dp of cumulative padding between the panel edge and the actual
    // notification text. The user described it as "a huge outline over
    // notifications that's just a ton of extra space". Now we're a
    // minimal Column that delegates the background/shape/padding to the
    // outer SpatialPanel.
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Voice status (mic icon, only visible when LISTENING/CONNECTING)
        // is now overlaid in the same row as the banner via a small
        // top-aligned indicator — no dedicated row taking 16dp vertical
        // when the indicator is invisible (which is most of the time).
        VoiceStatusIndicator(
            voiceState = voiceState,
            modifier = Modifier.align(Alignment.End),
        )

        NotificationBanner(
            emails = emails,
            onExpand = onExpandToNotifications,
        )

        TtsProgressBar(state = ttsState, progress = ttsProgress)

        ToastOverlay(
            message = toastMessage,
            onDismiss = onDismissToast,
        )
    }
}

@Composable
fun VoiceStatusIndicator(
    voiceState: GeminiLiveManager.SessionState,
    modifier: Modifier = Modifier,
) {
    val isActive = voiceState == GeminiLiveManager.SessionState.LISTENING
    val isProcessing = voiceState == GeminiLiveManager.SessionState.CONNECTING

    if (!isActive && !isProcessing) return

    val pulseTransition = rememberInfiniteTransition(label = "voicePulse")
    val alpha by pulseTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voiceAlpha",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.alpha(alpha),
    ) {
        Icon(
            Icons.Default.Mic,
            contentDescription = null,
            tint = if (isActive) XREmailColors.secondary else XREmailColors.aiAccent,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
fun TtsProgressBar(
    state: TTSManager.PlaybackState,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    if (state == TTSManager.PlaybackState.IDLE) return

    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = if (state == TTSManager.PlaybackState.PAUSED)
                XREmailColors.onSurfaceDim else XREmailColors.primary,
            trackColor = XREmailColors.surfaceVariant,
        )
    }
}

@Composable
fun ToastOverlay(
    message: ToastMessage?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(500)),
        modifier = modifier,
    ) {
        if (message != null) {
            LaunchedEffect(message.id) {
                delay(3000)
                onDismiss()
            }

            Text(
                text = message.text,
                style = MaterialTheme.typography.labelSmall,
                color = XREmailColors.secondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

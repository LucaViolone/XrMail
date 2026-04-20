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
 * Gaze dwell on the banner triggers expansion to NOTIFICATION_CARDS tier.
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
    /** Shown when /emails failed (e.g. 401, backend down) — otherwise invisible. */
    errorMessage: String? = null,
    onExpandToNotifications: () -> Unit,
    onDismissToast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(280.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(XREmailColors.surface.copy(alpha = 0.92f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            VoiceStatusIndicator(voiceState = voiceState)
        }

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.labelSmall,
                color = XREmailColors.priorityHigh,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

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

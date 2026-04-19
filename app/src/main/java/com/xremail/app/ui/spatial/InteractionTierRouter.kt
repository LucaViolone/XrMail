package com.xremail.app.ui.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.ContentEdge
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.width
import com.xremail.app.data.Email
import com.xremail.app.data.EmailCategory
import com.xremail.app.data.Priority
import com.xremail.app.ui.notifications.NotificationBanner
import com.xremail.app.ui.notifications.NotificationCardStack
import com.xremail.app.ui.peripheral.AmbientHud
import com.xremail.app.ui.peripheral.TtsProgressBar
import com.xremail.app.ui.peripheral.TriagePanel
import com.xremail.app.ui.peripheral.VoiceComposeOverlay
import com.xremail.app.ui.peripheral.VoiceStatusIndicator
import com.xremail.app.ui.theme.XREmailColors
import com.xremail.app.viewmodel.EmailUiState
import com.xremail.app.viewmodel.InteractionTier
import com.xremail.app.viewmodel.VoiceDraft
import com.xremail.app.voice.GeminiLiveManager
import com.xremail.app.voice.TTSManager
import com.xremail.app.voice.VoiceComposeManager

/**
 * Depth-aware spatial layout using the three-plane system:
 *
 * - Background (Z=30dp): Ambient HUD — notification banner, always visible
 * - Foreground (Z=15dp): Notification Cards — gaze-expanded, closer to user
 * - Content (Z=0dp): Triage and Focus — primary interaction planes
 *
 * Transitions:
 *   AMBIENT_HUD → (gaze dwell) → NOTIFICATION_CARDS → (pinch) → TRIAGE → (pinch-hold) → FOCUS
 *   Each level auto-collapses when gaze moves away or via gesture.
 */
@Composable
fun InteractionTierRouter(
    uiState: EmailUiState,
    prioritySortedEmails: List<Email>,
    ttsState: TTSManager.PlaybackState,
    ttsProgress: Float,
    tiltScrollDelta: Float,
    voiceSessionState: GeminiLiveManager.SessionState,
    voiceComposeState: VoiceComposeManager.ComposeState,
    voiceDraft: VoiceDraft?,
    onExpandToNotifications: () -> Unit,
    onCollapseFromNotifications: () -> Unit,
    onExpandToTriage: () -> Unit,
    onCollapseToHud: () -> Unit,
    onExpandToFocus: () -> Unit,
    onCollapseToTriage: () -> Unit,
    onEmailSelected: (Email) -> Unit,
    onOpenFromNotification: (Email) -> Unit,
    onCategorySelected: (EmailCategory?) -> Unit,
    onToggleAiSummary: () -> Unit,
    onReply: () -> Unit,
    onArchive: () -> Unit,
    onArchiveEmail: (Email) -> Unit,
    onSnooze: () -> Unit,
    onSnoozeEmail: (Email) -> Unit,
    onForward: () -> Unit,
    onSend: () -> Unit,
    onCancelCompose: () -> Unit,
    onDismissToast: () -> Unit,
    /** Fires a simulated voice reply — shown as "Voice" button in the Focus action bar. */
    onVoiceReply: (() -> Unit)? = null,
    onConfirmVoiceSend: (() -> Unit)? = null,
    onCancelVoice: (() -> Unit)? = null,
) {
    // Single shared lazy head-follow offset drives all peripheral tiers so
    // they drift together when the user turns their head. Defaults to 0
    // peripheral bias — the reactive delta adds onto each panel's own static
    // offset. FOCUS tier opts out below (it has manual drag instead).
    val lazyOffset: DpOffset by rememberLazyFollowOffset()

    Subspace {
        when (uiState.tier) {
            InteractionTier.AMBIENT_HUD -> {
                // Background plane: notification banner HUD in upper-right peripheral zone
                // Positioned at ~25 degrees off-center for peripheral visibility
                SpatialPanel(
                    modifier = SubspaceModifier
                        .width(300.dp)
                        .height(180.dp)
                        .offset(
                            x = 180.dp + lazyOffset.x,
                            y = (-160).dp + lazyOffset.y,
                            z = 30.dp,
                        ),
                ) {
                    AmbientHud(
                        unreadCount = uiState.unreadCount,
                        hasHighPriority = uiState.emails.any {
                            it.priority == Priority.HIGH && !it.isRead
                        },
                        emails = uiState.emails,
                        ttsState = ttsState,
                        ttsProgress = ttsProgress,
                        voiceState = voiceSessionState,
                        toastMessage = uiState.toastMessage,
                        onExpandToNotifications = onExpandToNotifications,
                        onDismissToast = onDismissToast,
                    )
                }

                if (uiState.isVoiceComposing) {
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(340.dp)
                            .height(280.dp)
                            .offset(
                                x = 180.dp + lazyOffset.x,
                                y = (-40).dp + lazyOffset.y,
                                z = 20.dp,
                            ),
                    ) {
                        VoiceComposeOverlay(
                            draft = voiceDraft ?: uiState.voiceDraft,
                            composeState = voiceComposeState,
                        )
                    }
                }
            }

            InteractionTier.NOTIFICATION_CARDS -> {
                // Foreground plane: cards fan out closer to user for easy reading
                // while walking. Positioned slightly right and up to avoid
                // obstructing the path ahead.
                SpatialPanel(
                    modifier = SubspaceModifier
                        .width(340.dp)
                        .height(520.dp)
                        .offset(
                            x = 140.dp + lazyOffset.x,
                            y = (-80).dp + lazyOffset.y,
                            z = 15.dp,
                        ),
                ) {
                    NotificationCardStack(
                        emails = uiState.emails,
                        highlightedId = uiState.highlightedNotificationId,
                        onSelectEmail = onOpenFromNotification,
                        onArchiveEmail = onArchiveEmail,
                        onSnoozeEmail = onSnoozeEmail,
                        onCollapseToHud = onCollapseFromNotifications,
                        onExpandToTriage = onExpandToTriage,
                    )
                }

                // Keep a minimal HUD visible in the corner during card view
                SpatialPanel(
                    modifier = SubspaceModifier
                        .width(200.dp)
                        .height(60.dp)
                        .offset(
                            x = 220.dp + lazyOffset.x,
                            y = (-200).dp + lazyOffset.y,
                            z = 30.dp,
                        ),
                ) {
                    MinimalStatusBar(
                        ttsState = ttsState,
                        ttsProgress = ttsProgress,
                        voiceState = voiceSessionState,
                    )
                }
            }

            InteractionTier.TRIAGE -> {
                // Content plane: centered for focused triage interaction,
                // softly drifts with head yaw so it's easy to glance away
                // and come back without re-centering manually.
                SpatialPanel(
                    modifier = SubspaceModifier
                        .width(420.dp)
                        .height(760.dp)
                        .offset(x = lazyOffset.x, y = lazyOffset.y),
                ) {
                    Surface(color = XREmailColors.surface) {
                        TriagePanel(
                            emails = prioritySortedEmails,
                            selectedEmail = uiState.selectedEmail,
                            ttsState = ttsState,
                            ttsSummary = uiState.selectedEmail?.aiSummary ?: "",
                            tiltScrollDelta = tiltScrollDelta,
                            onEmailSelected = onEmailSelected,
                            onArchive = onArchiveEmail,
                            onSnooze = onSnoozeEmail,
                            onCollapseToHud = onCollapseToHud,
                            onExpandToFocus = onExpandToFocus,
                        )
                    }

                    Orbiter(
                        position = ContentEdge.Top,
                        offset = 48.dp,
                        alignment = Alignment.End,
                    ) {
                        NotificationBanner(
                            emails = uiState.emails,
                            onExpand = {},
                        )
                    }
                }

                if (uiState.isVoiceComposing) {
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(380.dp)
                            .height(300.dp)
                            .offset(
                                x = 240.dp + lazyOffset.x,
                                y = lazyOffset.y,
                                z = 10.dp,
                            ),
                    ) {
                        VoiceComposeOverlay(
                            draft = voiceDraft ?: uiState.voiceDraft,
                            composeState = voiceComposeState,
                        )
                    }
                }
            }

            InteractionTier.FOCUS -> {
                SpatialEmailLayout(
                    emails = uiState.emails,
                    selectedEmail = uiState.selectedEmail,
                    selectedContact = uiState.selectedContact,
                    mode = uiState.mode,
                    activeCategory = uiState.activeCategory,
                    isAiSummaryExpanded = uiState.isAiSummaryExpanded,
                    unreadCount = uiState.unreadCount,
                    onEmailSelected = onEmailSelected,
                    onCategorySelected = onCategorySelected,
                    onToggleAiSummary = onToggleAiSummary,
                    onReply = onReply,
                    onArchive = onArchive,
                    onSnooze = onSnooze,
                    onForward = onForward,
                    onSend = onSend,
                    onCancelCompose = onCancelCompose,
                    onCollapse = onCollapseToTriage,
                    onVoiceReply = onVoiceReply,
                    voiceDraft = voiceDraft ?: uiState.voiceDraft,
                    voiceComposeState = voiceComposeState,
                    onConfirmVoiceSend = onConfirmVoiceSend,
                    onCancelVoice = onCancelVoice,
                )
            }
        }
    }
}

/**
 * Tiny status bar shown during NOTIFICATION_CARDS tier so the user
 * can still see TTS progress and voice status in the corner.
 */
@Composable
private fun MinimalStatusBar(
    ttsState: TTSManager.PlaybackState,
    ttsProgress: Float,
    voiceState: GeminiLiveManager.SessionState,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(XREmailColors.surface.copy(alpha = 0.85f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TtsProgressBar(
            state = ttsState,
            progress = ttsProgress,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        VoiceStatusIndicator(voiceState = voiceState)
    }
}

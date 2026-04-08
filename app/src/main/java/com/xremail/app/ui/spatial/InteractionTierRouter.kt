package com.xremail.app.ui.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import com.xremail.app.ui.peripheral.EmulatorHelpHint
import com.xremail.app.ui.peripheral.EmulatorHelpOverlay
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
 * Single expanding panel system. Each tier gets its own SpatialPanel
 * (required by the XR runtime for proper sizing), but they share the
 * same Subspace and use consistent positioning so the UI appears to
 * grow from the periphery toward center as tiers expand.
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
    onKeyDown: (Int) -> Boolean = { false },
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
) {
    val focusRequester = remember { FocusRequester() }

    // Re-request focus whenever the tier changes so the active panel captures keys
    LaunchedEffect(uiState.tier) {
        // Yield to let the new panel compose and attach the FocusRequester
        kotlinx.coroutines.yield()
        try {
            focusRequester.requestFocus()
        } catch (_: IllegalStateException) {
            // FocusRequester not yet attached — Activity-level fallback handles it
        }
    }

    val keyEventModifier = Modifier
        .focusRequester(focusRequester)
        .onPreviewKeyEvent { event: KeyEvent ->
            if (event.type == KeyEventType.KeyDown) {
                onKeyDown(event.key.nativeKeyCode)
            } else false
        }
        .focusable()

    Subspace {
        when (uiState.tier) {
            InteractionTier.AMBIENT_HUD -> {
                SpatialPanel(
                    modifier = SubspaceModifier
                        .width(300.dp)
                        .height(180.dp)
                        .offset(x = 180.dp, y = (-160).dp, z = 30.dp),
                ) {
                    Surface(
                        color = XREmailColors.surface,
                        modifier = Modifier.fillMaxSize().then(keyEventModifier),
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

                    Orbiter(
                        position = ContentEdge.Top,
                        offset = 48.dp,
                        alignment = Alignment.End,
                    ) {
                        MinimalStatusBar(
                            ttsState = ttsState,
                            ttsProgress = ttsProgress,
                            voiceState = voiceSessionState,
                        )
                    }
                }

                if (uiState.isVoiceComposing) {
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(340.dp)
                            .height(280.dp)
                            .offset(x = 180.dp, y = (-40).dp, z = 20.dp),
                    ) {
                        VoiceComposeOverlay(
                            draft = voiceDraft ?: uiState.voiceDraft,
                            composeState = voiceComposeState,
                        )
                    }
                }
            }

            InteractionTier.NOTIFICATION_CARDS -> {
                SpatialPanel(
                    modifier = SubspaceModifier
                        .width(340.dp)
                        .height(520.dp)
                        .offset(x = 100.dp, y = (-60).dp, z = 15.dp),
                ) {
                    Surface(
                        color = XREmailColors.surface,
                        modifier = Modifier.fillMaxSize().then(keyEventModifier),
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

                    Orbiter(
                        position = ContentEdge.Top,
                        offset = 48.dp,
                        alignment = Alignment.End,
                    ) {
                        MinimalStatusBar(
                            ttsState = ttsState,
                            ttsProgress = ttsProgress,
                            voiceState = voiceSessionState,
                        )
                    }
                }
            }

            InteractionTier.TRIAGE -> {
                SpatialPanel(
                    modifier = SubspaceModifier
                        .width(480.dp)
                        .height(760.dp),
                ) {
                    Surface(
                        color = XREmailColors.surface,
                        modifier = Modifier.fillMaxSize().then(keyEventModifier),
                    ) {
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
                        MinimalStatusBar(
                            ttsState = ttsState,
                            ttsProgress = ttsProgress,
                            voiceState = voiceSessionState,
                        )
                    }

                    Orbiter(
                        position = ContentEdge.Top,
                        offset = 96.dp,
                        alignment = Alignment.Start,
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
                            .offset(x = 280.dp, z = 10.dp),
                    ) {
                        VoiceComposeOverlay(
                            draft = voiceDraft ?: uiState.voiceDraft,
                            composeState = voiceComposeState,
                        )
                    }
                }
            }

            InteractionTier.FOCUS -> {
                SpatialPanel(
                    modifier = SubspaceModifier
                        .width(1200.dp)
                        .height(860.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize().then(keyEventModifier)) {
                        FocusContent(
                            emails = uiState.emails,
                            selectedEmail = uiState.selectedEmail,
                            selectedContact = uiState.selectedContact,
                            mode = uiState.mode,
                            activeCategory = uiState.activeCategory,
                            isAiSummaryExpanded = uiState.isAiSummaryExpanded,
                            onEmailSelected = onEmailSelected,
                            onCategorySelected = onCategorySelected,
                            onToggleAiSummary = onToggleAiSummary,
                            onSend = onSend,
                            onCancelCompose = onCancelCompose,
                        )
                    }

                    Orbiter(
                        position = ContentEdge.Bottom,
                        offset = 96.dp,
                        alignment = Alignment.CenterHorizontally,
                    ) {
                        QuickActionBar(
                            onReply = onReply,
                            onArchive = onArchive,
                            onSnooze = onSnooze,
                            onForward = onForward,
                        )
                    }

                    Orbiter(
                        position = ContentEdge.Top,
                        offset = 48.dp,
                        alignment = Alignment.End,
                    ) {
                        MinimalStatusBar(
                            ttsState = ttsState,
                            ttsProgress = ttsProgress,
                            voiceState = voiceSessionState,
                        )
                    }

                    Orbiter(
                        position = ContentEdge.Top,
                        offset = 96.dp,
                        alignment = Alignment.Start,
                    ) {
                        CollapseButton(onClick = onCollapseToTriage)
                    }
                }

                if (uiState.isVoiceComposing) {
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(380.dp)
                            .height(300.dp)
                            .offset(x = 640.dp, z = 10.dp),
                    ) {
                        VoiceComposeOverlay(
                            draft = voiceDraft ?: uiState.voiceDraft,
                            composeState = voiceComposeState,
                        )
                    }
                }
            }
        }

        SpatialPanel(
            modifier = SubspaceModifier
                .width(if (uiState.showEmulatorHelp) 340.dp else 240.dp)
                .height(if (uiState.showEmulatorHelp) 460.dp else 32.dp)
                .offset(x = (-280).dp, y = 200.dp, z = 10.dp),
        ) {
            if (uiState.showEmulatorHelp) {
                EmulatorHelpOverlay(currentTier = uiState.tier)
            } else {
                EmulatorHelpHint()
            }
        }
    }
}

// -- Collapse button ----------------------------------------------------------

@Composable
private fun CollapseButton(onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = XREmailColors.surfaceElevated,
            contentColor = XREmailColors.onSurfaceVariant,
        ),
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = "Collapse to Triage",
            modifier = Modifier.size(20.dp),
        )
    }
}

// -- Persistent status bar ----------------------------------------------------

@Composable
private fun MinimalStatusBar(
    ttsState: TTSManager.PlaybackState,
    ttsProgress: Float,
    voiceState: GeminiLiveManager.SessionState,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(XREmailColors.surface.copy(alpha = 0.85f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TtsProgressBar(
            state = ttsState,
            progress = ttsProgress,
            modifier = Modifier.width(120.dp),
        )
        Spacer(Modifier.width(8.dp))
        VoiceStatusIndicator(voiceState = voiceState)
    }
}

package com.xremail.app.ui.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.ContentEdge
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.subspace.MovePolicy
import androidx.xr.compose.subspace.ResizePolicy
import androidx.xr.compose.subspace.SpatialCurvedRow
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.width
import com.xremail.app.data.Contact
import com.xremail.app.data.Email
import com.xremail.app.data.EmailCategory
import com.xremail.app.ui.compose.ComposeScreen
import com.xremail.app.ui.context.ContextSidebar
import com.xremail.app.ui.inbox.InboxScreen
import com.xremail.app.ui.notifications.NotificationPill
import com.xremail.app.ui.peripheral.VoiceComposeOverlay
import com.xremail.app.ui.reader.EmailReaderScreen
import com.xremail.app.ui.theme.XREmailColors
import com.xremail.app.viewmodel.AppMode
import com.xremail.app.viewmodel.VoiceDraft
import com.xremail.app.voice.VoiceComposeManager

@Composable
fun SpatialEmailLayout(
    emails: List<Email>,
    selectedEmail: Email?,
    selectedContact: Contact?,
    mode: AppMode,
    activeCategory: EmailCategory?,
    isAiSummaryExpanded: Boolean,
    unreadCount: Int,
    onEmailSelected: (Email) -> Unit,
    onCategorySelected: (EmailCategory?) -> Unit,
    onToggleAiSummary: () -> Unit,
    onReply: () -> Unit,
    onArchive: () -> Unit,
    onSnooze: () -> Unit,
    onForward: () -> Unit,
    onSend: () -> Unit,
    onCancelCompose: () -> Unit,
    onCollapse: (() -> Unit)? = null,
    /** Fires a simulated voice instruction — shows up as "Voice" button in action bar. */
    onVoiceReply: (() -> Unit)? = null,
    /** Voice draft + state for the inline compose overlay (emulator-friendly). */
    voiceDraft: VoiceDraft? = null,
    voiceComposeState: VoiceComposeManager.ComposeState = VoiceComposeManager.ComposeState.IDLE,
    onConfirmVoiceSend: (() -> Unit)? = null,
    onCancelVoice: (() -> Unit)? = null,
    onGestureOverlay: @Composable () -> Unit = {},
) {
    SpatialCurvedRow(
        curveRadius = 825.dp,
    ) {
        SpatialPanel(
            modifier = SubspaceModifier
                .width(420.dp)
                .height(860.dp),
            dragPolicy = MovePolicy(),
            resizePolicy = ResizePolicy(),
        ) {
            Surface(color = XREmailColors.surface) {
                InboxScreen(
                    emails = emails,
                    selectedEmail = selectedEmail,
                    activeCategory = activeCategory,
                    onEmailSelected = onEmailSelected,
                    onCategorySelected = onCategorySelected,
                )
            }

            Orbiter(
                position = ContentEdge.Top,
                offset = 48.dp,
                alignment = Alignment.End,
            ) {
                NotificationPill(unreadCount = unreadCount)
            }
        }

        SpatialPanel(
            modifier = SubspaceModifier
                .width(840.dp)
                .height(860.dp),
            dragPolicy = MovePolicy(),
            resizePolicy = ResizePolicy(),
        ) {
            Surface(color = XREmailColors.surface) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (mode) {
                        AppMode.READING -> EmailReaderScreen(
                            email = selectedEmail,
                            isAiSummaryExpanded = isAiSummaryExpanded,
                            onToggleAiSummary = onToggleAiSummary,
                        )
                        AppMode.COMPOSING -> ComposeScreen(
                            replyTo = selectedEmail,
                            onSend = onSend,
                            onCancel = onCancelCompose,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 40.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        onGestureOverlay()
                    }
                }
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
                    onVoiceReply = onVoiceReply,
                )
            }

            // Voice compose overlay — floats above the reader in Focus tier.
            // Send/Cancel buttons are wired so emulator users can finish the flow
            // without a pinch gesture.
            if (voiceDraft != null && voiceComposeState != VoiceComposeManager.ComposeState.IDLE) {
                Orbiter(
                    position = ContentEdge.Top,
                    offset = 96.dp,
                    alignment = Alignment.CenterHorizontally,
                ) {
                    VoiceComposeOverlay(
                        draft = voiceDraft,
                        composeState = voiceComposeState,
                        onConfirmSend = onConfirmVoiceSend,
                        onCancel = onCancelVoice,
                    )
                }
            }

            if (onCollapse != null) {
                Orbiter(
                    position = ContentEdge.Top,
                    offset = 48.dp,
                    alignment = Alignment.End,
                ) {
                    CollapsePill(onClick = onCollapse)
                }
            }
        }

        SpatialPanel(
            modifier = SubspaceModifier
                .width(380.dp)
                .height(860.dp),
            dragPolicy = MovePolicy(),
            resizePolicy = ResizePolicy(),
        ) {
            Surface(color = XREmailColors.surface) {
                ContextSidebar(
                    contact = selectedContact,
                    attachments = selectedEmail?.attachments.orEmpty(),
                    actionItems = selectedEmail?.actionItems.orEmpty(),
                    threadCount = selectedEmail?.threadCount ?: 0,
                )
            }
        }
    }
}

@Composable
private fun CollapsePill(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 52.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(XREmailColors.surfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = "Collapse to Triage",
            tint = XREmailColors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Collapse",
            style = MaterialTheme.typography.labelMedium,
            color = XREmailColors.onSurfaceVariant,
        )
    }
}

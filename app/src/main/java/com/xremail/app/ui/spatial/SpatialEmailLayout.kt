package com.xremail.app.ui.spatial

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.ContentEdge
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.Subspace
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
import com.xremail.app.ui.reader.EmailReaderScreen
import com.xremail.app.ui.theme.XREmailColors
import com.xremail.app.viewmodel.AppMode
import com.xremail.app.voice.GeminiLiveManager

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
    draftBody: String,
    onDraftBodyChange: (String) -> Unit,
    voiceSessionState: GeminiLiveManager.SessionState,
    onDictateClick: () -> Unit,
    assistantStatus: String?,
) {
    Subspace {
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

            // Center panel: Reader or Compose
            SpatialPanel(
                modifier = SubspaceModifier
                    .width(840.dp)
                    .height(860.dp),
                dragPolicy = MovePolicy(),
                resizePolicy = ResizePolicy(),
            ) {
                Surface(color = XREmailColors.surface) {
                    when (mode) {
                        AppMode.READING -> EmailReaderScreen(
                            email = selectedEmail,
                            isAiSummaryExpanded = isAiSummaryExpanded,
                            onToggleAiSummary = onToggleAiSummary,
                        )
                        AppMode.COMPOSING -> ComposeScreen(
                            replyTo = selectedEmail,
                            draftBody = draftBody,
                            onDraftBodyChange = onDraftBodyChange,
                            voiceSessionState = voiceSessionState,
                            onDictateClick = onDictateClick,
                            assistantStatus = assistantStatus,
                            onSend = onSend,
                            onCancel = onCancelCompose,
                        )
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
                    )
                }
            }

            // Right panel: Context sidebar
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
}

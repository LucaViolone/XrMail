package com.xremail.app.ui.spatial

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

            if (onCollapse != null) {
                Orbiter(
                    position = ContentEdge.Top,
                    offset = 48.dp,
                    alignment = Alignment.Start,
                ) {
                    FilledIconButton(
                        onClick = onCollapse,
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

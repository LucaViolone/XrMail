package com.xremail.app.ui.spatial

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xremail.app.data.Contact
import com.xremail.app.data.Email
import com.xremail.app.data.EmailCategory
import com.xremail.app.ui.compose.ComposeScreen
import com.xremail.app.ui.context.ContextSidebar
import com.xremail.app.ui.inbox.InboxScreen
import com.xremail.app.ui.reader.EmailReaderScreen
import com.xremail.app.ui.theme.XREmailColors
import com.xremail.app.viewmodel.AppMode

/**
 * Three-column focus layout: inbox | reader/compose | context sidebar.
 *
 * Renders inside the single expanding SpatialPanel at the FOCUS tier.
 * Replaces the old SpatialCurvedRow + 3 SpatialPanel approach so that
 * the entire app lives in one panel that grows and shrinks.
 */
@Composable
fun FocusContent(
    emails: List<Email>,
    selectedEmail: Email?,
    selectedContact: Contact?,
    mode: AppMode,
    activeCategory: EmailCategory?,
    isAiSummaryExpanded: Boolean,
    onEmailSelected: (Email) -> Unit,
    onCategorySelected: (EmailCategory?) -> Unit,
    onToggleAiSummary: () -> Unit,
    onSend: () -> Unit,
    onCancelCompose: () -> Unit,
) {
    Surface(
        color = XREmailColors.surface,
        modifier = Modifier.fillMaxSize(),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left column: inbox list
            Surface(
                color = XREmailColors.surface,
                modifier = Modifier
                    .weight(0.25f)
                    .fillMaxHeight(),
            ) {
                InboxScreen(
                    emails = emails,
                    selectedEmail = selectedEmail,
                    activeCategory = activeCategory,
                    onEmailSelected = onEmailSelected,
                    onCategorySelected = onCategorySelected,
                )
            }

            VerticalDivider(color = XREmailColors.surfaceVariant)

            // Center column: reader or compose
            Surface(
                color = XREmailColors.surface,
                modifier = Modifier
                    .weight(0.50f)
                    .fillMaxHeight(),
            ) {
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

            VerticalDivider(color = XREmailColors.surfaceVariant)

            // Right column: context sidebar
            Surface(
                color = XREmailColors.surface,
                modifier = Modifier
                    .weight(0.25f)
                    .fillMaxHeight(),
            ) {
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

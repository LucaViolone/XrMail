package com.xremail.app.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xremail.app.data.Email
import com.xremail.app.data.EmailCategory
import com.xremail.app.data.Mailbox
import com.xremail.app.ui.theme.XREmailColors

@Composable
fun InboxScreen(
    emails: List<Email>,
    selectedEmail: Email?,
    activeCategory: EmailCategory?,
    activeMailbox: Mailbox,
    onEmailSelected: (Email) -> Unit,
    onCategorySelected: (EmailCategory?) -> Unit,
    onMailboxSelected: (Mailbox) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(XREmailColors.surface),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = when (activeMailbox) {
                        Mailbox.INBOX -> "Inbox"
                        Mailbox.SENT -> "Sent"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    color = XREmailColors.onSurfaceStrong,
                )
                if (activeMailbox == Mailbox.INBOX) {
                    val unread = emails.count { !it.isRead }
                    if (unread > 0) {
                        Text(
                            text = "$unread unread",
                            style = MaterialTheme.typography.labelMedium,
                            color = XREmailColors.onSurfaceDim,
                        )
                    }
                } else {
                    Text(
                        text = "${emails.size} sent",
                        style = MaterialTheme.typography.labelMedium,
                        color = XREmailColors.onSurfaceDim,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            MailboxTabs(
                activeMailbox = activeMailbox,
                onMailboxSelected = onMailboxSelected,
            )

            Spacer(Modifier.height(12.dp))

            CategoryChips(
                activeCategory = activeCategory,
                onCategorySelected = onCategorySelected,
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        XREmailColors.surfaceVariant.copy(alpha = 0.6f),
                        RoundedCornerShape(22.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search",
                    tint = XREmailColors.onSurfaceDim,
                )
                Text(
                    text = "Search",
                    style = MaterialTheme.typography.bodyMedium,
                    color = XREmailColors.onSurfaceDim,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(emails, key = { it.id }) { email ->
                EmailCard(
                    email = email,
                    isSelected = email.id == selectedEmail?.id,
                    onClick = { onEmailSelected(email) },
                )
            }
        }
    }
}

@Composable
private fun MailboxTabs(
    activeMailbox: Mailbox,
    onMailboxSelected: (Mailbox) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(Mailbox.INBOX to "Inbox", Mailbox.SENT to "Sent").forEach { (mailbox, label) ->
            FilterChip(
                selected = activeMailbox == mailbox,
                onClick = { onMailboxSelected(mailbox) },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = XREmailColors.surfaceVariant.copy(alpha = 0.5f),
                    selectedContainerColor = XREmailColors.primary.copy(alpha = 0.2f),
                    labelColor = XREmailColors.onSurfaceDim,
                    selectedLabelColor = XREmailColors.primary,
                ),
                shape = RoundedCornerShape(22.dp),
            )
        }
    }
}

@Composable
private fun CategoryChips(
    activeCategory: EmailCategory?,
    onCategorySelected: (EmailCategory?) -> Unit,
) {
    val categories = listOf(
        null to "All",
        EmailCategory.PEOPLE to "People",
        EmailCategory.UPDATES to "Updates",
        EmailCategory.PROMOTIONS to "Promos",
        EmailCategory.NEWSLETTERS to "News",
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        categories.forEach { (category, label) ->
            FilterChip(
                selected = activeCategory == category,
                onClick = { onCategorySelected(category) },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = XREmailColors.surfaceVariant.copy(alpha = 0.5f),
                    selectedContainerColor = XREmailColors.primary.copy(alpha = 0.14f),
                    labelColor = XREmailColors.onSurfaceDim,
                    selectedLabelColor = XREmailColors.primary,
                ),
                shape = RoundedCornerShape(22.dp),
            )
        }
    }
}

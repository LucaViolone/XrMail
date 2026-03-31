package com.xremail.app.ui.context

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xremail.app.data.ActionItem
import com.xremail.app.data.Attachment
import com.xremail.app.data.Contact
import com.xremail.app.ui.theme.XREmailColors

@Composable
fun ContextSidebar(
    contact: Contact?,
    attachments: List<Attachment>,
    actionItems: List<ActionItem>,
    threadCount: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(XREmailColors.surface)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        if (contact != null) {
            ContactCard(contact = contact)
            Spacer(Modifier.height(14.dp))
        }

        if (attachments.isNotEmpty()) {
            AttachmentSection(attachments)
            Spacer(Modifier.height(14.dp))
        }

        ActionItemsList(actionItems = actionItems)

        if (threadCount > 1) {
            Spacer(Modifier.height(14.dp))
            RelatedThreads(threadCount = threadCount)
        }
    }
}

@Composable
private fun AttachmentSection(attachments: List<Attachment>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(XREmailColors.surfaceVariant)
            .padding(14.dp),
    ) {
        Text(
            text = "Attachments",
            style = MaterialTheme.typography.labelLarge,
            color = XREmailColors.primary,
        )

        Spacer(Modifier.height(8.dp))

        attachments.forEach { attachment ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = null,
                    tint = XREmailColors.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = attachment.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = XREmailColors.onSurface,
                    )
                    Text(
                        text = "${attachment.type} · ${attachment.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = XREmailColors.onSurfaceDim,
                    )
                }
            }
        }
    }
}

@Composable
private fun RelatedThreads(threadCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(XREmailColors.surfaceVariant)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.Forum,
            contentDescription = null,
            tint = XREmailColors.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Thread ($threadCount messages)",
            style = MaterialTheme.typography.labelLarge,
            color = XREmailColors.onSurface,
        )
    }
}

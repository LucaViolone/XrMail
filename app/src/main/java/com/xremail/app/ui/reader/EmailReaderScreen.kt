package com.xremail.app.ui.reader

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xremail.app.data.Email
import com.xremail.app.data.Priority
import com.xremail.app.ui.theme.XREmailColors

@Composable
fun EmailReaderScreen(
    email: Email?,
    isAiSummaryExpanded: Boolean,
    onToggleAiSummary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (email == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(XREmailColors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Select an email to read",
                style = MaterialTheme.typography.bodyLarge,
                color = XREmailColors.onSurfaceDim,
            )
        }
        return
    }

    val priorityColor = when (email.priority) {
        Priority.HIGH -> XREmailColors.priorityHigh
        Priority.MEDIUM -> XREmailColors.priorityMedium
        Priority.LOW -> XREmailColors.priorityLow
        Priority.IGNORE -> XREmailColors.onSurfaceDim
    }

    val initials = email.sender.split(" ")
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercase() }
        .joinToString("")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(XREmailColors.surface)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(priorityColor.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.titleMedium,
                    color = priorityColor,
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = email.sender,
                    style = MaterialTheme.typography.titleMedium,
                    color = XREmailColors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = email.senderEmail,
                    style = MaterialTheme.typography.bodySmall,
                    color = XREmailColors.onSurfaceDim,
                )
            }

            if (email.isStarred) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Starred",
                    tint = XREmailColors.tertiary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
            }

            Text(
                text = email.timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = XREmailColors.onSurfaceDim,
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = email.subject,
            style = MaterialTheme.typography.headlineMedium,
            color = XREmailColors.onSurface,
        )

        Spacer(Modifier.height(16.dp))

        AISummaryCard(
            summary = email.aiSummary,
            isExpanded = isAiSummaryExpanded,
            onToggle = onToggleAiSummary,
        )

        Spacer(Modifier.height(16.dp))

        HorizontalDivider(
            color = XREmailColors.surfaceVariant,
            thickness = 1.dp,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = email.body,
            style = MaterialTheme.typography.bodyLarge,
            color = XREmailColors.onSurface,
            lineHeight = 28.sp,
        )

        if (email.threadCount > 1) {
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(XREmailColors.surfaceVariant)
                    .padding(14.dp),
            ) {
                Text(
                    text = "${email.threadCount} messages in this thread",
                    style = MaterialTheme.typography.labelMedium,
                    color = XREmailColors.onSurfaceVariant,
                )
            }
        }
    }
}

package com.xremail.app.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xremail.app.data.Email
import com.xremail.app.data.Priority
import com.xremail.app.ui.theme.XREmailColors

@Composable
fun EmailCard(
    email: Email,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor = if (isSelected) {
        XREmailColors.surfaceElevated
    } else {
        XREmailColors.surfaceVariant
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(priorityColor.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials,
                style = MaterialTheme.typography.labelMedium,
                color = priorityColor,
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = email.sender,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (!email.isRead) FontWeight.Bold else FontWeight.Normal,
                    ),
                    color = XREmailColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = email.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = XREmailColors.onSurfaceDim,
                )
            }

            Spacer(Modifier.height(2.dp))

            Text(
                text = email.subject,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (!email.isRead) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = XREmailColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = email.aiSummary,
                style = MaterialTheme.typography.labelSmall,
                color = XREmailColors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (email.priority == Priority.HIGH) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(XREmailColors.priorityHigh),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "High priority",
                        style = MaterialTheme.typography.labelSmall,
                        color = XREmailColors.priorityHigh,
                    )
                }
            }
        }
    }
}

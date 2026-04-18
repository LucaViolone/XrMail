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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xremail.app.data.Email
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
                text = "Select an email",
                style = MaterialTheme.typography.bodyLarge,
                color = XREmailColors.onSurfaceDim,
            )
        }
        return
    }

    val initials = email.sender.split(" ")
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercase() }
        .joinToString("")

    val avatarBrush = Brush.linearGradient(
        colors = listOf(Color(0xFF7C9CFF), Color(0xFFC48CFF)),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(XREmailColors.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 40.dp),
    ) {
        Text(
            text = email.subject,
            style = MaterialTheme.typography.headlineMedium,
            color = XREmailColors.onSurfaceStrong,
            modifier = Modifier.widthIn(max = 560.dp),
        )

        Spacer(Modifier.height(20.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(avatarBrush),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.labelLarge,
                    color = XREmailColors.surface,
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = email.sender,
                    style = MaterialTheme.typography.titleMedium,
                    color = XREmailColors.onSurfaceStrong,
                )
                Text(
                    text = email.senderEmail,
                    style = MaterialTheme.typography.labelSmall,
                    color = XREmailColors.onSurfaceDim,
                )
            }

            if (email.isStarred) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Starred",
                    tint = XREmailColors.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
            }

            Text(
                text = email.timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = XREmailColors.onSurfaceDim,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(XREmailColors.onSurfaceDim.copy(alpha = 0.12f)),
        )

        Spacer(Modifier.height(20.dp))

        AISummaryCard(
            summary = email.aiSummary,
            isExpanded = isAiSummaryExpanded,
            onToggle = onToggleAiSummary,
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = email.body,
            style = MaterialTheme.typography.bodyLarge,
            color = XREmailColors.onSurface,
            modifier = Modifier.widthIn(max = 620.dp),
        )

        if (email.threadCount > 1) {
            Spacer(Modifier.height(28.dp))
            Text(
                text = "${email.threadCount} messages in this thread",
                style = MaterialTheme.typography.labelMedium,
                color = XREmailColors.onSurfaceDim,
            )
        }
    }
}

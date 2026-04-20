package com.xremail.app.ui.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.xremail.app.ui.theme.XREmailColors

@Composable
fun QuickActionBar(
    onReply: () -> Unit,
    onArchive: () -> Unit,
    onSnooze: () -> Unit,
    onForward: () -> Unit,
    /** When non-null, a Voice Reply button is shown next to Reply. */
    onVoiceReply: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(XREmailColors.surfaceElevated.copy(alpha = 0.78f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SecondaryAction(
            icon = Icons.Default.Archive,
            label = "Archive",
            onClick = onArchive,
        )
        SecondaryAction(
            icon = Icons.Default.AccessTime,
            label = "Snooze",
            onClick = onSnooze,
        )

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(20.dp)
                .background(XREmailColors.onSurfaceDim.copy(alpha = 0.3f)),
        )

        PrimaryAction(
            icon = Icons.AutoMirrored.Filled.Reply,
            label = "Reply",
            onClick = onReply,
        )
        if (onVoiceReply != null) {
            SecondaryAction(
                icon = Icons.Default.Mic,
                label = "Voice",
                onClick = onVoiceReply,
            )
        }
        SecondaryAction(
            icon = Icons.Default.Forward,
            label = "Forward",
            onClick = onForward,
        )
    }
}

@Composable
private fun PrimaryAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(XREmailColors.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = XREmailColors.surface,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = XREmailColors.surface,
        )
    }
}

@Composable
private fun SecondaryAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = XREmailColors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = XREmailColors.onSurfaceVariant,
        )
    }
}

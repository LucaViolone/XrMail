package com.xremail.app.ui.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.xremail.app.ui.theme.XREmailColors

@Composable
fun QuickActionBar(
    onReply: () -> Unit,
    onArchive: () -> Unit,
    onSnooze: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(XREmailColors.surfaceElevated)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButton(
            icon = Icons.AutoMirrored.Filled.Reply,
            label = "Reply",
            onClick = onReply,
        )
        ActionButton(
            icon = Icons.Default.Archive,
            label = "Archive",
            onClick = onArchive,
        )
        ActionButton(
            icon = Icons.Default.AccessTime,
            label = "Snooze",
            onClick = onSnooze,
        )
        ActionButton(
            icon = Icons.Default.Forward,
            label = "Forward",
            onClick = onForward,
        )
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = XREmailColors.surfaceVariant,
            contentColor = XREmailColors.onSurface,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

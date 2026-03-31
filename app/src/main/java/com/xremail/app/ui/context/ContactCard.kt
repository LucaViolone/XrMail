package com.xremail.app.ui.context

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xremail.app.data.Contact
import com.xremail.app.ui.theme.XREmailColors

@Composable
fun ContactCard(
    contact: Contact,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(XREmailColors.surfaceVariant)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(XREmailColors.primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = contact.avatarInitials,
                style = MaterialTheme.typography.titleLarge,
                color = XREmailColors.primary,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = contact.name,
            style = MaterialTheme.typography.titleMedium,
            color = XREmailColors.onSurface,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )

        if (contact.title.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = contact.title,
                style = MaterialTheme.typography.bodySmall,
                color = XREmailColors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        if (contact.organization.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = contact.organization,
                style = MaterialTheme.typography.labelSmall,
                color = XREmailColors.onSurfaceDim,
                textAlign = TextAlign.Center,
            )
        }
    }
}

package com.xremail.app.ui.peripheral

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xremail.app.data.Email
import com.xremail.app.data.Priority
import com.xremail.app.ui.theme.XREmailColors
import kotlinx.coroutines.delay

@Composable
fun SummaryTicker(
    emails: List<Email>,
    modifier: Modifier = Modifier,
) {
    val unread = remember(emails) { emails.filter { !it.isRead } }
    if (unread.isEmpty()) return

    var currentIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(unread.size) {
        while (true) {
            delay(4000)
            currentIndex = (currentIndex + 1) % unread.size
        }
    }

    val email = unread[currentIndex.coerceIn(unread.indices)]
    val color = when (email.priority) {
        Priority.HIGH -> XREmailColors.priorityHigh
        Priority.MEDIUM -> XREmailColors.primary
        else -> XREmailColors.onSurfaceVariant
    }

    AnimatedContent(
        targetState = currentIndex,
        transitionSpec = {
            (slideInVertically { it } + fadeIn()) togetherWith
                (slideOutVertically { -it } + fadeOut())
        },
        label = "ticker",
        modifier = modifier.fillMaxWidth(),
    ) { _ ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 2.dp),
        ) {
            Text(
                text = email.sender.split(" ").firstOrNull() ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = email.aiSummary,
                style = MaterialTheme.typography.labelSmall,
                color = XREmailColors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

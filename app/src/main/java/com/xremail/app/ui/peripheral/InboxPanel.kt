package com.xremail.app.ui.peripheral

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xremail.app.data.Email
import com.xremail.app.ui.inbox.EmailCard
import com.xremail.app.ui.theme.XREmailColors
import com.xremail.app.voice.TTSManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun InboxPanel(
    emails: List<Email>,
    selectedEmail: Email?,
    ttsState: TTSManager.PlaybackState,
    ttsSummary: String,
    tiltScrollDelta: Float,
    onEmailSelected: (Email) -> Unit,
    onArchive: (Email) -> Unit,
    onSnooze: (Email) -> Unit,
    onCollapseToHud: () -> Unit,
    onExpandToFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        snapshotFlow { tiltScrollDelta }
            .distinctUntilChanged()
            .filter { it != 0f }
            .collect { delta ->
                listState.animateScrollBy(delta)
            }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(XREmailColors.surface)
            .padding(16.dp),
    ) {
        // Header with tier navigation buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = onCollapseToHud,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = XREmailColors.surfaceElevated,
                    contentColor = XREmailColors.onSurfaceVariant,
                ),
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Minimize to HUD",
                    modifier = Modifier.size(20.dp),
                )
            }

            Text(
                text = "Inbox",
                style = MaterialTheme.typography.labelMedium,
                color = XREmailColors.onSurfaceVariant,
            )

            FilledIconButton(
                onClick = onExpandToFocus,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = XREmailColors.surfaceElevated,
                    contentColor = XREmailColors.onSurfaceVariant,
                ),
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.OpenInFull,
                    contentDescription = "Expand to Focus Mode",
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (ttsState != TTSManager.PlaybackState.IDLE && ttsSummary.isNotBlank()) {
            TtsSummaryHeader(summary = ttsSummary, state = ttsState)
            Spacer(Modifier.height(12.dp))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(emails, key = { it.id }) { email ->
                SwipeableEmailCard(
                    email = email,
                    isSelected = email.id == selectedEmail?.id,
                    onSelect = { onEmailSelected(email) },
                    onArchive = { onArchive(email) },
                    onSnooze = { onSnooze(email) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        GestureHintStrip()
    }
}

@Composable
private fun TtsSummaryHeader(
    summary: String,
    state: TTSManager.PlaybackState,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(XREmailColors.surfaceElevated)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val stateLabel = when (state) {
            TTSManager.PlaybackState.PLAYING -> "▶"
            TTSManager.PlaybackState.PAUSED -> "⏸"
            TTSManager.PlaybackState.IDLE -> ""
        }
        Text(
            text = stateLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = XREmailColors.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = XREmailColors.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableEmailCard(
    email: Email,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onArchive: () -> Unit,
    onSnooze: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onArchive()
                    true
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onSnooze()
                    true
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val direction = dismissState.dismissDirection

            val bgColor by animateColorAsState(
                targetValue = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> XREmailColors.secondary.copy(alpha = 0.2f)
                    SwipeToDismissBoxValue.EndToStart -> XREmailColors.tertiary.copy(alpha = 0.2f)
                    else -> XREmailColors.surfaceVariant
                },
                label = "swipeBg",
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgColor)
                    .padding(horizontal = 20.dp),
            ) {
                if (direction == SwipeToDismissBoxValue.StartToEnd) {
                    Icon(
                        Icons.Default.Archive,
                        contentDescription = "Archive",
                        tint = XREmailColors.secondary,
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.CenterStart),
                    )
                }
                if (direction == SwipeToDismissBoxValue.EndToStart) {
                    Icon(
                        Icons.Default.Snooze,
                        contentDescription = "Snooze",
                        tint = XREmailColors.tertiary,
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.CenterEnd),
                    )
                }
            }
        },
    ) {
        EmailCard(
            email = email,
            isSelected = isSelected,
            onClick = onSelect,
        )
    }
}

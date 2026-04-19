package com.xremail.app.ui.notifications

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xremail.app.data.Email
import com.xremail.app.data.Priority
import com.xremail.app.ui.theme.XREmailColors
import com.xremail.app.util.XrLog
import kotlinx.coroutines.delay

private const val MAX_VISIBLE_CARDS = 5

/**
 * iPhone-style notification banner for the Ambient HUD.
 * Shows the unread count pill alongside a rotating preview of the
 * latest sender + AI summary. Tapping or gaze-dwelling expands
 * to the NOTIFICATION_CARDS tier.
 *
 * Visually similar to iOS lock screen banners — rounded pill with
 * avatar, sender, and one-line preview. Pulses gently when HIGH
 * priority emails are present.
 */
@Composable
fun NotificationBanner(
    emails: List<Email>,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unread = remember(emails) { emails.filter { !it.isRead } }
    if (unread.isEmpty()) return

    val hasHigh = unread.any { it.priority == Priority.HIGH }

    val pulseScale = if (hasHigh) {
        val transition = rememberInfiniteTransition(label = "bannerPulse")
        val scale by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "bannerScale",
        )
        scale
    } else {
        1f
    }

    var currentIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(unread.size) {
        if (unread.size > 1) {
            while (true) {
                delay(4000)
                currentIndex = (currentIndex + 1) % unread.size
            }
        }
    }

    val email = unread[currentIndex.coerceIn(unread.indices)]
    val priorityColor = when (email.priority) {
        Priority.HIGH -> XREmailColors.priorityHigh
        Priority.MEDIUM -> XREmailColors.priorityMedium
        Priority.LOW -> XREmailColors.priorityLow
        Priority.IGNORE -> XREmailColors.onSurfaceDim
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(pulseScale)
            .clip(RoundedCornerShape(16.dp))
            .background(XREmailColors.surfaceVariant.copy(alpha = 0.9f))
            .clickable(onClick = onExpand)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar circle with sender initial
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(priorityColor.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = email.sender.take(1).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = priorityColor,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.width(10.dp))

        // Sender + summary preview (cycles through unread)
        AnimatedContent(
            targetState = currentIndex,
            transitionSpec = {
                (slideInVertically { it } + fadeIn()) togetherWith
                    (slideOutVertically { -it } + fadeOut())
            },
            label = "bannerCycle",
            modifier = Modifier.weight(1f),
        ) { _ ->
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = email.sender.split(" ").firstOrNull() ?: email.sender,
                    style = MaterialTheme.typography.labelMedium,
                    color = XREmailColors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = email.aiSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = XREmailColors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // Count badge
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (hasHigh) XREmailColors.priorityHigh.copy(alpha = 0.2f)
                    else XREmailColors.primary.copy(alpha = 0.15f)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Mail,
                contentDescription = null,
                tint = if (hasHigh) XREmailColors.priorityHigh else XREmailColors.primary,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "${unread.size}",
                style = MaterialTheme.typography.labelSmall,
                color = if (hasHigh) XREmailColors.priorityHigh else XREmailColors.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Expanded notification cards list — shown in the NOTIFICATION_CARDS tier.
 * Each card is swipeable for archive/snooze directly, or tappable to
 * open in Triage with that email pre-selected.
 *
 * Auto-collapses when gaze moves away (handled by the tier router +
 * FaceAttentionTracker gaze zone logic).
 */
@Composable
fun NotificationCardStack(
    emails: List<Email>,
    highlightedId: String?,
    onSelectEmail: (Email) -> Unit,
    onArchiveEmail: (Email) -> Unit,
    onSnoozeEmail: (Email) -> Unit,
    onCollapseToHud: () -> Unit,
    onExpandToTriage: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Head-tilt-driven scroll delta from [com.xremail.app.tracking.TiltScrollController].
     * When the user looks down/up while the card stack is open, this is animated
     * into the scroll state below. Lets the user navigate the notification stack
     * hands-free while walking — no second-hand pinch needed to scroll.
     */
    tiltScrollDelta: Float = 0f,
) {
    val unread = emails.filter { !it.isRead }
        .sortedWith(
            compareBy<Email> { priorityOrder(it.priority) }
                .thenByDescending { it.urgencyScore }
        )

    val scrollState = rememberScrollState()
    // KNOWN-LIMITATION: this `LaunchedEffect` only re-runs when
    // `tiltScrollDelta` *changes*. If `TiltScrollController` ever publishes
    // the same float twice in a row (e.g. user holds a steady tilt that the
    // controller maps to a constant px/frame value), the second emission is
    // conflated by `StateFlow` and we lose a scroll tick. In practice the
    // tilt-derived delta is continuous and noisy enough that two identical
    // values almost never arrive consecutively, but if scrolling ever feels
    // "sticky" this is the place to switch the source to a SharedFlow of
    // discrete (id, delta) events and key the LaunchedEffect on the id.
    LaunchedEffect(tiltScrollDelta) {
        if (tiltScrollDelta != 0f) {
            XrLog.v("NotifScroll", "tilt -> animateScrollBy($tiltScrollDelta)")
            scrollState.animateScrollBy(tiltScrollDelta)
        }
    }

    Column(
        modifier = modifier
            .width(320.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(XREmailColors.surface.copy(alpha = 0.94f))
            .padding(12.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left header: tap to expand the stack into the TRIAGE panel.
            // Gives a keyboard/mouse-reachable forward path that doesn't require
            // a pinch gesture — needed on the emulator and as a fallback when
            // hand tracking loses the user's hand.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onExpandToTriage)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Mail,
                    contentDescription = null,
                    tint = XREmailColors.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${unread.size} new",
                    style = MaterialTheme.typography.labelMedium,
                    color = XREmailColors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // Right header: tap to collapse back to the ambient HUD.
            // Previously onCollapseToHud was a declared-but-unused parameter,
            // so there was no way out of this tier except voice/gesture.
            Text(
                text = "tap ✕ to close",
                style = MaterialTheme.typography.labelSmall,
                color = XREmailColors.onSurfaceDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCollapseToHud)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        Spacer(Modifier.height(2.dp))

        val visible = unread.take(MAX_VISIBLE_CARDS)
        visible.forEachIndexed { index, email ->
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(
                        durationMillis = 250,
                        delayMillis = index * 50,
                    ),
                ) + fadeIn(tween(250, delayMillis = index * 50)),
                exit = fadeOut(tween(150)),
            ) {
                NotificationCard(
                    email = email,
                    isHighlighted = email.id == highlightedId,
                    onSelect = { onSelectEmail(email) },
                    onArchive = { onArchiveEmail(email) },
                    onSnooze = { onSnoozeEmail(email) },
                )
            }
        }

        if (unread.size > MAX_VISIBLE_CARDS) {
            Text(
                text = "+${unread.size - MAX_VISIBLE_CARDS} more — pinch to see all",
                style = MaterialTheme.typography.labelSmall,
                color = XREmailColors.onSurfaceDim,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpandToTriage)
                    .padding(vertical = 6.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(XREmailColors.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            HintLabel("→ archive")
            HintLabel("← snooze")
            HintLabel("pinch open")
            HintLabel("look away ✕")
        }
    }
}

@Composable
private fun HintLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = XREmailColors.onSurfaceDim,
    )
}

private fun priorityOrder(priority: Priority): Int = when (priority) {
    Priority.HIGH -> 0
    Priority.MEDIUM -> 1
    Priority.LOW -> 2
    Priority.IGNORE -> 3
}

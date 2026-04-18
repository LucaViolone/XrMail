package com.xremail.app.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xremail.app.data.Email
import com.xremail.app.ui.theme.XREmailColors
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.consumePositionChange
//test
@Composable
fun ComposeScreen(
    replyTo: Email?,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val toField = replyTo?.let { "${it.sender} <${it.senderEmail}>" } ?: ""
    val subjectField = replyTo?.let { "Re: ${it.subject}" } ?: ""
    val suggestedDraft = replyTo?.suggestedReply ?: ""
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 20.dp.toPx() }  // tune this
    var body by remember(replyTo?.id) { mutableStateOf(suggestedDraft) }
    var isVoiceMode by remember { mutableStateOf(false) }

    val transparentFieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedTextColor = XREmailColors.onSurface,
        unfocusedTextColor = XREmailColors.onSurface,
        cursorColor = XREmailColors.primary,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        focusedLabelColor = XREmailColors.onSurfaceVariant,
        unfocusedLabelColor = XREmailColors.onSurfaceDim,
    )
    Box(
    modifier = Modifier
        .fillMaxSize()
        .pointerInput(body, onSend) {
            awaitEachGesture {
                // Wait for first down of this gesture
                val firstDown = awaitFirstDown(requireUnconsumed = false)
                // Track active pointers (fingers) by id -> last position
                val lastPos = mutableMapOf<PointerId, androidx.compose.ui.geometry.Offset>()
                lastPos[firstDown.id] = firstDown.position
                var accumulatedDx = 0f
                var armed = false
                var sent = false
                while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Main)
                    // Update positions
                    for (ch in event.changes) {
                        if (ch.pressed) {
                            lastPos[ch.id] = ch.position
                        } else if (ch.changedToUpIgnoreConsumed()) {
                            lastPos.remove(ch.id)
                        }
                    }
                    val pressedCount = event.changes.count { it.pressed }
                    // Only engage when 2+ fingers are down
                    if (pressedCount >= 2) {
                        if (!armed) {
                            // when we first reach 2 fingers, zero out motion
                            accumulatedDx = 0f
                            armed = true
                        }
                        // Use average horizontal movement of the pressed pointers this frame
                        val pressed = event.changes.filter { it.pressed }
                        val frameDx =
                            pressed.map { it.positionChange().x }.average().toFloat()
                        accumulatedDx += frameDx
                        // Once we decide it's a horizontal 2-finger swipe, consume X to avoid scroll fights
                        if (kotlin.math.abs(accumulatedDx) > 8f) {
                            pressed.forEach { it.consumePositionChange() }
                        }
                        // Trigger send once when passing threshold
                        if (!sent && kotlin.math.abs(accumulatedDx) >= swipeThresholdPx) {
                            if (body.isNotBlank()) {
                                onSend()
                            }
                            sent = true
                        }
                    }
                    // End gesture when no pointers are pressed anymore
                    if (pressedCount == 0) break
                }
            }
        }
) {
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
            Text(
                text = "Compose",
                style = MaterialTheme.typography.headlineMedium,
                color = XREmailColors.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Cancel",
                    tint = XREmailColors.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        TextField(
            value = toField,
            onValueChange = {},
            label = { Text("To") },
            readOnly = true,
            singleLine = true,
            colors = transparentFieldColors,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider(color = XREmailColors.surfaceVariant)

        TextField(
            value = subjectField,
            onValueChange = {},
            label = { Text("Subject") },
            readOnly = true,
            singleLine = true,
            colors = transparentFieldColors,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider(color = XREmailColors.surfaceVariant)

        Spacer(Modifier.height(12.dp))

        if (suggestedDraft.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        1.dp,
                        XREmailColors.aiAccent.copy(alpha = 0.3f),
                        RoundedCornerShape(12.dp),
                    )
                    .background(XREmailColors.aiAccent.copy(alpha = 0.06f))
                    .padding(10.dp),
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = XREmailColors.aiAccent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "AI Draft — review before sending",
                    style = MaterialTheme.typography.labelMedium,
                    color = XREmailColors.aiAccent,
                )
                Spacer(Modifier.weight(1f))
                replyTo?.let {
                    Text(
                        text = "${(it.replyConfidence * 100).toInt()}% confidence",
                        style = MaterialTheme.typography.labelSmall,
                        color = XREmailColors.aiAccent.copy(alpha = 0.7f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        AnimatedVisibility(
            visible = !isVoiceMode,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            TextField(
                value = body,
                onValueChange = { body = it },
                placeholder = { Text("Write your email...") },
                colors = transparentFieldColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
            )
        }

        AnimatedVisibility(
            visible = isVoiceMode,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(XREmailColors.surfaceVariant.copy(alpha = 0.5f))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    tint = XREmailColors.secondary,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Listening...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = XREmailColors.secondary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Describe your reply and AI will draft it",
                    style = MaterialTheme.typography.bodySmall,
                    color = XREmailColors.onSurfaceDim,
                    fontStyle = FontStyle.Italic,
                )
                if (body.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = XREmailColors.onSurface,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { isVoiceMode = !isVoiceMode },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isVoiceMode) XREmailColors.secondary
                    else XREmailColors.onSurfaceVariant,
                ),
                shape = RoundedCornerShape(24.dp),
            ) {
                Icon(
                    if (isVoiceMode) Icons.Default.Keyboard else Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (isVoiceMode) "Keyboard" else "Dictate")
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onSend,
                enabled = body.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = XREmailColors.primary,
                    contentColor = XREmailColors.surface,
                ),
                shape = RoundedCornerShape(24.dp),
            ) {
                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Send", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
}

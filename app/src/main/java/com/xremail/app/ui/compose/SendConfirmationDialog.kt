package com.xremail.app.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xremail.app.ui.theme.XREmailColors
import com.xremail.app.viewmodel.VoiceDraft

/**
 * Full-screen visual send-confirmation for a voice-composed draft.
 *
 * Surfaced when Gemini calls the `show_send_confirmation` tool (the
 * model's "show it to me before sending" path) or when the user
 * explicitly asks to see the draft instead of hearing it. Gives the
 * user concrete, scannable context — recipient, subject, full body —
 * and two big buttons: Send (commits) or Cancel (keeps the draft but
 * dismisses the dialog so they can revise it verbally).
 *
 * Blocks touches behind the scrim so a stray tap on the underlying
 * tier content can't trigger a send-adjacent action by accident.
 * Deliberately does NOT speak — the UX contract is: this dialog is
 * the alternative to reading aloud, not an addition.
 */
@Composable
fun SendConfirmationDialog(
    draft: VoiceDraft,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(XREmailColors.surface.copy(alpha = 0.86f))
            // Eat taps on the scrim so they don't leak to the tier
            // beneath. Without this a miss on the card would still
            // click whatever's under the scrim.
            .pointerInput(Unit) { },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = XREmailColors.surfaceElevated,
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .widthIn(min = 360.dp, max = 520.dp)
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
            ) {
                Text(
                    text = "Send this reply?",
                    style = MaterialTheme.typography.titleLarge,
                    color = XREmailColors.onSurfaceStrong,
                    fontWeight = FontWeight.SemiBold,
                )

                Spacer(Modifier.height(14.dp))

                LabelRow(label = "To", value = draft.recipientName)
                Spacer(Modifier.height(6.dp))
                LabelRow(label = "Subject", value = draft.subject)

                Spacer(Modifier.height(14.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 360.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(XREmailColors.surfaceVariant.copy(alpha = 0.55f))
                        .padding(16.dp),
                ) {
                    val scroll = rememberScrollState()
                    Text(
                        text = draft.draftText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = XREmailColors.onSurface,
                        modifier = Modifier.verticalScroll(scroll),
                    )
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = onSend,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = XREmailColors.primary,
                            contentColor = XREmailColors.onSurfaceStrong,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Send")
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    text = "You can also say \"send it\" or \"cancel\".",
                    style = MaterialTheme.typography.labelSmall,
                    color = XREmailColors.onSurfaceDim,
                )
            }
        }
    }
}

@Composable
private fun LabelRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = XREmailColors.onSurfaceDim,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = XREmailColors.onSurface,
        )
    }
}

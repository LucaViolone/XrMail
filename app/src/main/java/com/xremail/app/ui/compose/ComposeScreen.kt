package com.xremail.app.ui.compose

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xremail.app.data.Email
import com.xremail.app.ui.theme.XREmailColors

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

    var body by remember(replyTo?.id) { mutableStateOf(suggestedDraft) }

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

        TextField(
            value = body,
            onValueChange = { body = it },
            placeholder = { Text("Write your email...") },
            colors = transparentFieldColors,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        )

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {},
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = XREmailColors.onSurfaceVariant,
                ),
                shape = RoundedCornerShape(24.dp),
            ) {
                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Dictate")
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

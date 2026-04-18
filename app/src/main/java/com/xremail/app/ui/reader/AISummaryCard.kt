package com.xremail.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xremail.app.ui.theme.XREmailColors

@Composable
fun AISummaryCard(
    summary: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(XREmailColors.primary),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp),
        ) {
            Text(
                text = "GEMINI · SUMMARY",
                style = MaterialTheme.typography.labelSmall,
                color = XREmailColors.primary,
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = XREmailColors.onSurface,
                maxLines = if (isExpanded) Int.MAX_VALUE else 2,
            )
        }
    }
}

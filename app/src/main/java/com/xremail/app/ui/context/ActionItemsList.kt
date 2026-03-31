package com.xremail.app.ui.context

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xremail.app.data.ActionItem
import com.xremail.app.ui.theme.XREmailColors

@Composable
fun ActionItemsList(
    actionItems: List<ActionItem>,
    modifier: Modifier = Modifier,
) {
    if (actionItems.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(XREmailColors.surfaceVariant)
            .padding(14.dp),
    ) {
        Text(
            text = "Action Items",
            style = MaterialTheme.typography.labelLarge,
            color = XREmailColors.secondary,
        )

        Spacer(Modifier.height(8.dp))

        actionItems.forEach { item ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                Checkbox(
                    checked = item.isCompleted,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(
                        checkedColor = XREmailColors.secondary,
                        uncheckedColor = XREmailColors.onSurfaceDim,
                        checkmarkColor = XREmailColors.surface,
                    ),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isCompleted) {
                        XREmailColors.onSurfaceDim
                    } else {
                        XREmailColors.onSurface
                    },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

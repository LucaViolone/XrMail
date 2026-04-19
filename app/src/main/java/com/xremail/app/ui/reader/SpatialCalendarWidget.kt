package com.xremail.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xremail.app.data.CalendarEvent
import com.xremail.app.ui.theme.XREmailColors
import java.time.format.DateTimeFormatter

@Composable
fun SpatialCalendarWidget(
    events: List<CalendarEvent>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(320.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(XREmailColors.surfaceElevated)
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.CalendarMonth,
                contentDescription = "Calendar",
                tint = XREmailColors.primary,
                modifier = Modifier.padding(end = 12.dp)
            )
            Text(
                text = "Your Schedule",
                style = MaterialTheme.typography.titleMedium,
                color = XREmailColors.onSurfaceStrong
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (events.isEmpty()) {
            Text(
                text = "No upcoming events.",
                style = MaterialTheme.typography.bodyMedium,
                color = XREmailColors.onSurfaceDim
            )
        } else {
            val formatter = DateTimeFormatter.ofPattern("h:mm a")
            
            events.take(4).forEach { event ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Time indicator
                    Text(
                        text = event.startTime.format(formatter),
                        style = MaterialTheme.typography.labelMedium,
                        color = XREmailColors.onSurfaceDim,
                        modifier = Modifier.width(70.dp)
                    )
                    
                    // Event block
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (event.isBusy) XREmailColors.tertiary.copy(alpha = 0.2f) else XREmailColors.primary.copy(alpha = 0.2f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = event.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (event.isBusy) XREmailColors.tertiary else XREmailColors.primary
                        )
                    }
                }
            }
        }
    }
}

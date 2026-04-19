package com.xremail.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
fun CalendarScreen(
    events: List<CalendarEvent>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(XREmailColors.surface)
            .padding(40.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CalendarMonth,
                contentDescription = "Calendar",
                tint = XREmailColors.primary,
                modifier = Modifier.padding(end = 16.dp)
            )
            Text(
                text = "My Calendar",
                style = MaterialTheme.typography.headlineMedium,
                color = XREmailColors.onSurfaceStrong,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Calendar",
                    tint = XREmailColors.onSurfaceVariant
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Agenda View
        if (events.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No upcoming events.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = XREmailColors.onSurfaceDim
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                val formatter = DateTimeFormatter.ofPattern("h:mm a")
                val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")
                
                // Group by date
                val groupedEvents = events.groupBy { it.startTime.toLocalDate() }
                
                groupedEvents.forEach { (date, dailyEvents) ->
                    Text(
                        text = date.format(dateFormatter),
                        style = MaterialTheme.typography.titleLarge,
                        color = XREmailColors.onSurfaceStrong,
                        modifier = Modifier.padding(top = 24.dp, bottom = 16.dp)
                    )
                    
                    dailyEvents.forEach { event ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${event.startTime.format(formatter)} - ${event.endTime.format(formatter)}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = XREmailColors.onSurfaceDim,
                                modifier = Modifier.width(160.dp)
                            )
                            
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (event.isBusy) XREmailColors.tertiary.copy(alpha = 0.15f) 
                                        else XREmailColors.primary.copy(alpha = 0.15f)
                                    )
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = event.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (event.isBusy) XREmailColors.tertiary else XREmailColors.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

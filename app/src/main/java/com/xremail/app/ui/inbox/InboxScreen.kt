package com.xremail.app.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xremail.app.data.Email
import com.xremail.app.data.EmailCategory
import com.xremail.app.ui.theme.XREmailColors

@Composable
fun InboxScreen(
    emails: List<Email>,
    selectedEmail: Email?,
    activeCategory: EmailCategory?,
    onEmailSelected: (Email) -> Unit,
    onCategorySelected: (EmailCategory?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(XREmailColors.surface)
            .padding(16.dp),
    ) {
        Text(
            text = "Inbox",
            style = MaterialTheme.typography.headlineMedium,
            color = XREmailColors.onSurface,
        )

        Spacer(Modifier.height(12.dp))

        CategoryChips(
            activeCategory = activeCategory,
            onCategorySelected = onCategorySelected,
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    XREmailColors.surfaceVariant,
                    RoundedCornerShape(24.dp),
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = "Search",
                tint = XREmailColors.onSurfaceDim,
            )
            Text(
                text = "Search emails...",
                style = MaterialTheme.typography.bodyMedium,
                color = XREmailColors.onSurfaceDim,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(emails, key = { it.id }) { email ->
                EmailCard(
                    email = email,
                    isSelected = email.id == selectedEmail?.id,
                    onClick = { onEmailSelected(email) },
                )
            }
        }
    }
}

@Composable
private fun CategoryChips(
    activeCategory: EmailCategory?,
    onCategorySelected: (EmailCategory?) -> Unit,
) {
    val categories = listOf(
        null to "All",
        EmailCategory.PEOPLE to "People",
        EmailCategory.UPDATES to "Updates",
        EmailCategory.PROMOTIONS to "Promos",
        EmailCategory.NEWSLETTERS to "News",
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        categories.forEach { (category, label) ->
            FilterChip(
                selected = activeCategory == category,
                onClick = { onCategorySelected(category) },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = XREmailColors.surfaceVariant,
                    selectedContainerColor = XREmailColors.primary.copy(alpha = 0.2f),
                    labelColor = XREmailColors.onSurfaceVariant,
                    selectedLabelColor = XREmailColors.primary,
                ),
                shape = RoundedCornerShape(24.dp),
            )
        }
    }
}

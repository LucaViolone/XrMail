package com.xremail.app.ui.peripheral

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xremail.app.tracking.KeyboardGestureDispatcher
import com.xremail.app.ui.theme.XREmailColors
import com.xremail.app.viewmodel.InteractionTier

@Composable
fun EmulatorHelpOverlay(
    currentTier: InteractionTier,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(XREmailColors.surface.copy(alpha = 0.92f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "KEYBOARD CONTROLS",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = XREmailColors.primary,
            letterSpacing = 1.sp,
        )

        Text(
            text = "Tier: ${currentTier.name}",
            fontSize = 10.sp,
            color = XREmailColors.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )

        KeyboardGestureDispatcher.KEY_MAPPINGS.forEach { mapping ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = mapping.keyLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = XREmailColors.onSurface,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(56.dp),
                )
                Text(
                    text = mapping.description,
                    fontSize = 11.sp,
                    color = XREmailColors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Text(
            text = "Press H to hide",
            fontSize = 9.sp,
            color = XREmailColors.onSurfaceDim,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Compact one-line hint shown when the full overlay is hidden.
 */
@Composable
fun EmulatorHelpHint(modifier: Modifier = Modifier) {
    Text(
        text = "Press H for keyboard shortcuts",
        fontSize = 10.sp,
        color = XREmailColors.onSurfaceDim,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(XREmailColors.surface.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

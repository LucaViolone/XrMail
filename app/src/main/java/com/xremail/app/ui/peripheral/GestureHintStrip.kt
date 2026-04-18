package com.xremail.app.ui.peripheral

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xremail.app.ui.theme.XREmailColors
import kotlinx.coroutines.delay

@Composable
fun GestureHintStrip(
    modifier: Modifier = Modifier,
    autoHide: Boolean = true,
) {
    if (autoHide) {
        var visible by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            delay(5000)
            visible = false
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = modifier,
        ) {
            HintRow()
        }
    } else {
        HintRow(modifier = modifier)
    }
}

@Composable
private fun HintRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(XREmailColors.surfaceElevated.copy(alpha = 0.8f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        HintItem("\u2192 archive")
        HintItem("\u2190 snooze")
        HintItem("\u2191 star")
        HintItem("pinch read")
    }
}

@Composable
private fun HintItem(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = XREmailColors.onSurfaceDim,
    )
}

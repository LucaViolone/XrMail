package com.xremail.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xremail.app.ui.spatial.DisplayMode
import com.xremail.app.ui.spatial.DisplayModeRouter
import com.xremail.app.ui.spatial.GlimmerEmailApp
import com.xremail.app.ui.spatial.SpatialEmailLayout
import com.xremail.app.ui.theme.XREmailTheme
import com.xremail.app.viewmodel.EmailViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            XREmailTheme {
                XREmailApp()
            }
        }
    }
}

@Composable
fun XREmailApp() {
    val displayMode = DisplayModeRouter.detect()

    when (displayMode) {
        DisplayMode.GLASSES_ADDITIVE -> GlimmerEmailApp()
        DisplayMode.HEADSET -> HeadsetEmailApp()
    }
}

@Composable
private fun HeadsetEmailApp() {
    val viewModel: EmailViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SpatialEmailLayout(
        emails = uiState.emails,
        selectedEmail = uiState.selectedEmail,
        selectedContact = uiState.selectedContact,
        mode = uiState.mode,
        activeCategory = uiState.activeCategory,
        isAiSummaryExpanded = uiState.isAiSummaryExpanded,
        unreadCount = uiState.unreadCount,
        onEmailSelected = viewModel::selectEmail,
        onCategorySelected = viewModel::filterByCategory,
        onToggleAiSummary = viewModel::toggleAiSummary,
        onReply = viewModel::startCompose,
        onArchive = viewModel::archiveSelected,
        onSnooze = viewModel::snoozeSelected,
        onForward = viewModel::forwardSelected,
        onSend = viewModel::sendDraft,
        onCancelCompose = viewModel::cancelCompose,
    )
}

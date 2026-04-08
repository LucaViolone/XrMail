package com.xremail.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xremail.app.backend.service.AuthRepository
import com.xremail.app.backend.service.NetworkClient
import com.xremail.app.backend.service.TokenManager
import com.xremail.app.backend.service.GmailRepository
import com.xremail.app.backend.mock.MockEmailRepository
import com.xremail.app.ui.spatial.DisplayMode
import com.xremail.app.ui.spatial.DisplayModeRouter
import com.xremail.app.ui.spatial.GlimmerEmailApp
import com.xremail.app.ui.spatial.SpatialEmailLayout
import com.xremail.app.ui.theme.XREmailTheme
import com.xremail.app.viewmodel.EmailViewModel
import com.xremail.app.voice.GeminiLiveManager

class MainActivity : ComponentActivity() {

    // ---------------------------------------------------------------------------
    // Backend wiring — swap USE_REAL_BACKEND to true once the server is running
    // ---------------------------------------------------------------------------

    private val USE_REAL_BACKEND = false
    private val BACKEND_URL = "http://10.0.2.2:8080/" // emulator → host loopback

    private lateinit var tokenManager: TokenManager
    private lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        tokenManager = TokenManager(applicationContext)

        val emailRepository = if (USE_REAL_BACKEND) {
            val api = NetworkClient.create(
                baseUrl = BACKEND_URL,
                tokenManager = tokenManager,
                debug = true,
            )
            authRepository = AuthRepository(api, tokenManager)
            GmailRepository(api)
        } else {
            // Phase 1: use mock data so the UI works without a running backend
            authRepository = AuthRepository(
                api = NetworkClient.create(BACKEND_URL, tokenManager),
                tokenManager = tokenManager,
            )
            MockEmailRepository()
        }

        setContent {
            XREmailTheme {
                XREmailApp(
                    viewModelFactory = EmailViewModel.Factory(application, emailRepository),
                )
            }
        }

        // Handle OAuth deep-link if the activity was launched via xrmail://auth/...
        intent?.let { handleOAuthIntent(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthIntent(intent)
    }

    /**
     * Processes the xrmail://auth/success or xrmail://auth/error deep link
     * that the Ktor backend sends after the Gmail OAuth flow completes.
     *
     * On success: saves the JWT via [AuthRepository] and reloads emails.
     * On error:   logs the reason (production would show a UI error state).
     */
    private fun handleOAuthIntent(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "xrmail" || uri.host != "auth") return

        when (uri.path) {
            "/success" -> {
                val state = authRepository.handleCallback(uri)
                Log.i(TAG, "OAuth success — user: ${tokenManager.getUserEmail()}, state: $state")
                // The ViewModel will reload emails on its next recomposition because
                // TokenManager now returns a valid JWT, enabling real API calls.
            }
            "/error" -> {
                val reason = uri.getQueryParameter("reason") ?: "unknown"
                Log.e(TAG, "OAuth error: $reason")
                // TODO: surface an error snackbar or re-auth prompt in the XR UI
            }
        }
    }

    companion object {
        private const val TAG = "XrMailAuth"
    }
}

@Composable
fun XREmailApp(viewModelFactory: EmailViewModel.Factory) {
    val displayMode = DisplayModeRouter.detect()

    when (displayMode) {
        DisplayMode.GLASSES_ADDITIVE -> GlimmerEmailApp()
        DisplayMode.HEADSET -> HeadsetEmailApp(viewModelFactory)
    }
}

@Composable
private fun HeadsetEmailApp(factory: EmailViewModel.Factory) {
    val viewModel: EmailViewModel = viewModel(factory = factory)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val geminiLive = remember { GeminiLiveManager() }
    val liveVoiceState by geminiLive.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                geminiLive.toggleListening(context)
            } else {
                Toast.makeText(context, "Microphone permission is required for dictation", Toast.LENGTH_SHORT).show()
            }
        },
    )

    SideEffect {
        geminiLive.setVoiceSendGate { viewModel.isVoiceSendArmed() }
    }

    LaunchedEffect(Unit) {
        geminiLive.commands.collect { viewModel.handleVoiceCommand(it) }
    }

    LaunchedEffect(Unit) {
        geminiLive.lastError.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            geminiLive.disconnect()
        }
    }

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
        onSend = { viewModel.sendDraft(isFromVoice = false) },
        onCancelCompose = viewModel::cancelCompose,
        draftBody = uiState.draftBody,
        onDraftBodyChange = viewModel::updateDraftBody,
        voiceSessionState = liveVoiceState,
        onDictateClick = {
            when {
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED -> {
                    geminiLive.toggleListening(context)
                }
                else -> audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        assistantStatus = uiState.assistantStatus,
    )
}

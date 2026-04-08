package com.xremail.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.xr.compose.platform.LocalSession
import com.xremail.app.backend.service.AuthRepository
import com.xremail.app.backend.service.NetworkClient
import com.xremail.app.backend.service.TokenManager
import com.xremail.app.backend.service.GmailRepository
import com.xremail.app.backend.mock.MockEmailRepository
import com.xremail.app.tracking.FaceAttentionTracker
import com.xremail.app.tracking.GestureToActionMapper
import com.xremail.app.tracking.KeyboardGestureDispatcher
import com.xremail.app.tracking.SecondaryHandGestures
import com.xremail.app.tracking.TiltScrollController
import com.xremail.app.tracking.XrSessionManager
import com.xremail.app.ui.spatial.InteractionTierRouter
import com.xremail.app.ui.theme.XREmailTheme
import com.xremail.app.viewmodel.EmailViewModel
import com.xremail.app.viewmodel.InteractionTier
import com.xremail.app.voice.GeminiLiveManager
import com.xremail.app.voice.TTSManager
import com.xremail.app.voice.VoiceCommandExecutor
import com.xremail.app.voice.VoiceComposeManager

class MainActivity : ComponentActivity() {

    private val USE_REAL_BACKEND = false
    private val BACKEND_URL = "http://10.0.2.2:8080/"

    private lateinit var tokenManager: TokenManager
    private lateinit var authRepository: AuthRepository

    var keyboardDispatcher: KeyboardGestureDispatcher? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Let Compose panels handle key events first (when they have focus)
        if (super.dispatchKeyEvent(event)) return true
        // Fallback for when no Compose panel has focus
        if (event.action == KeyEvent.ACTION_DOWN) {
            return keyboardDispatcher?.onKeyDown(event.keyCode) ?: false
        }
        return false
    }

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
            authRepository = AuthRepository(
                api = NetworkClient.create(BACKEND_URL, tokenManager),
                tokenManager = tokenManager,
            )
            MockEmailRepository()
        }

        setContent {
            XREmailTheme {
                XrMailApp(viewModelFactory = EmailViewModel.Factory(emailRepository))
            }
        }

        intent?.let { handleOAuthIntent(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthIntent(intent)
    }

    private fun handleOAuthIntent(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != "xrmail" || uri.host != "auth") return

        when (uri.path) {
            "/success" -> {
                val state = authRepository.handleCallback(uri)
                Log.i(TAG, "OAuth success — user: ${tokenManager.getUserEmail()}, state: $state")
            }
            "/error" -> {
                val reason = uri.getQueryParameter("reason") ?: "unknown"
                Log.e(TAG, "OAuth error: $reason")
            }
        }
    }

    companion object {
        private const val TAG = "XrMailAuth"
    }
}

@Composable
fun XrMailApp(viewModelFactory: EmailViewModel.Factory) {
    val viewModel: EmailViewModel = viewModel(factory = viewModelFactory)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val ttsManager = remember { TTSManager(context) }
    val geminiLive = remember { GeminiLiveManager() }
    val voiceCompose = remember { VoiceComposeManager(ttsManager) }
    val executor = remember(viewModel, ttsManager, voiceCompose, geminiLive) {
        VoiceCommandExecutor(viewModel, ttsManager, voiceCompose, geminiLive)
    }

    val faceTracker = remember { FaceAttentionTracker() }
    val handGestures = remember { SecondaryHandGestures() }
    val tiltScroll = remember { TiltScrollController() }
    val gestureMapper = remember(viewModel) { GestureToActionMapper(viewModel) }
    val keyboardDispatcher = remember(viewModel, handGestures) {
        KeyboardGestureDispatcher(viewModel, handGestures)
    }
    val xrSessionManager = remember { XrSessionManager(faceTracker, handGestures, tiltScroll) }

    // Wire keyboard dispatcher to Activity for emulator key events
    LaunchedEffect(keyboardDispatcher) {
        (context as? MainActivity)?.keyboardDispatcher = keyboardDispatcher
    }

    val ttsState by ttsManager.playbackState.collectAsStateWithLifecycle()
    val ttsProgress by ttsManager.progress.collectAsStateWithLifecycle()
    val voiceSessionState by geminiLive.state.collectAsStateWithLifecycle()
    val voiceComposeState by voiceCompose.state.collectAsStateWithLifecycle()
    val voiceDraft by voiceCompose.draft.collectAsStateWithLifecycle()
    val tiltScrollDelta by tiltScroll.scrollDelta.collectAsStateWithLifecycle()

    val xrSession = LocalSession.current

    // -- Runtime permission for microphone ----------------------------------------

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMicPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // -- Gemini Live voice session ------------------------------------------------

    LaunchedEffect(hasMicPermission) {
        if (!hasMicPermission) return@LaunchedEffect
        geminiLive.connect()
    }

    LaunchedEffect(hasMicPermission, voiceSessionState) {
        if (!hasMicPermission) return@LaunchedEffect
        if (voiceSessionState == GeminiLiveManager.SessionState.CONNECTED) {
            geminiLive.startAudioCapture()
        }
    }

    LaunchedEffect(geminiLive) {
        geminiLive.functionCalls.collect { fc ->
            val spokenResponse = executor.execute(fc.command)
            geminiLive.sendToolResponse(fc.id, fc.name, spokenResponse)
        }
    }

    // -- XR session ---------------------------------------------------------------

    LaunchedEffect(xrSession) {
        xrSessionManager.startAll(
            session = xrSession,
            contentResolver = context.contentResolver,
            scope = scope,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            xrSessionManager.stopAll()
            geminiLive.destroy()
            ttsManager.destroy()
        }
    }

    // -- Gesture wiring -----------------------------------------------------------

    LaunchedEffect(handGestures, gestureMapper) {
        handGestures.gestures.collect { gesture ->
            gestureMapper.onGesture(gesture, viewModel.uiState.value.tier)
        }
    }

    LaunchedEffect(faceTracker, ttsManager) {
        faceTracker.isAttentive.collect { attentive ->
            ttsManager.onAttentionChanged(attentive)
        }
    }

    LaunchedEffect(faceTracker) {
        faceTracker.isGazingAtNotificationZone.collect { gazing ->
            val currentTier = viewModel.uiState.value.tier
            if (gazing && currentTier == InteractionTier.AMBIENT_HUD) {
                viewModel.expandToNotificationCards()
            } else if (!gazing && currentTier == InteractionTier.NOTIFICATION_CARDS) {
                viewModel.collapseFromNotificationCards()
            }
        }
    }

    // -- UI -----------------------------------------------------------------------

    InteractionTierRouter(
        uiState = uiState,
        prioritySortedEmails = viewModel.prioritySortedEmails(),
        ttsState = ttsState,
        ttsProgress = ttsProgress,
        tiltScrollDelta = tiltScrollDelta,
        voiceSessionState = voiceSessionState,
        voiceComposeState = voiceComposeState,
        voiceDraft = voiceDraft,
        onKeyDown = keyboardDispatcher::onKeyDown,
        onExpandToNotifications = viewModel::expandToNotificationCards,
        onCollapseFromNotifications = viewModel::collapseFromNotificationCards,
        onExpandToTriage = viewModel::expandToTriage,
        onCollapseToHud = viewModel::collapseToHud,
        onExpandToFocus = viewModel::expandToFocus,
        onCollapseToTriage = viewModel::collapseToTriage,
        onEmailSelected = viewModel::selectEmail,
        onOpenFromNotification = viewModel::openFromNotification,
        onCategorySelected = viewModel::filterByCategory,
        onToggleAiSummary = viewModel::toggleAiSummary,
        onReply = viewModel::startCompose,
        onArchive = viewModel::archiveSelected,
        onArchiveEmail = viewModel::archiveEmail,
        onSnooze = viewModel::snoozeSelected,
        onSnoozeEmail = viewModel::snoozeEmail,
        onForward = viewModel::forwardSelected,
        onSend = viewModel::sendDraft,
        onCancelCompose = viewModel::cancelCompose,
        onDismissToast = viewModel::dismissToast,
    )
}

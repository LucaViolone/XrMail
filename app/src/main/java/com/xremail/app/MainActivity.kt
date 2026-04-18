package com.xremail.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.xr.compose.platform.LocalSession
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.xremail.app.backend.service.AuthRepository
import com.xremail.app.backend.service.NetworkClient
import com.xremail.app.backend.service.TokenManager
import com.xremail.app.backend.service.GmailRepository
import com.xremail.app.backend.mock.MockEmailRepository
import com.xremail.app.tracking.FaceAttentionTracker
import com.xremail.app.tracking.GestureToActionMapper
import com.xremail.app.tracking.SecondaryHandGestures
import com.xremail.app.tracking.TiltScrollController
import com.xremail.app.tracking.XrSessionManager
import com.xremail.app.ui.spatial.DisplayMode
import com.xremail.app.ui.spatial.DisplayModeRouter
import com.xremail.app.ui.feedback.GestureFeedbackOverlay
import com.xremail.app.ui.spatial.GlimmerEmailApp
import com.xremail.app.ui.spatial.InteractionTierRouter
import com.xremail.app.ui.theme.XREmailTheme
import com.xremail.app.viewmodel.EmailViewModel
import com.xremail.app.viewmodel.InteractionTier
import com.xremail.app.voice.GeminiLiveManager
import com.xremail.app.voice.TTSManager
import com.xremail.app.voice.VoiceCommandDispatcher
import com.xremail.app.voice.VoiceComposeManager

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
                    viewModelFactory = EmailViewModel.Factory(emailRepository)
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val ttsManager = remember { TTSManager(context) }
    val geminiLive = remember { GeminiLiveManager() }
    val voiceCompose = remember { VoiceComposeManager(geminiLive, ttsManager) }
    val voiceDispatcher = remember(viewModel) {
        VoiceCommandDispatcher(viewModel, ttsManager)
    }

    // Runtime permissions — mic for Gemini Live, XR sensors for session.configure.
    // Missing XR perms crash OpenXrManager.configure with SecurityException.
    val requiredPerms = remember {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            "android.permission.HAND_TRACKING",
            "android.permission.FACE_TRACKING",
            "android.permission.EYE_TRACKING_COARSE",
            "android.permission.SCENE_UNDERSTANDING",
        )
    }
    fun allGranted(): Boolean = requiredPerms.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var xrGranted by remember { mutableStateOf(allGranted()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        micGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        xrGranted = allGranted()
    }
    LaunchedEffect(Unit) {
        if (!allGranted()) permissionLauncher.launch(requiredPerms)
    }

    // Connect / disconnect Gemini Live once the mic permission is settled.
    LaunchedEffect(micGranted) {
        if (micGranted) {
            geminiLive.setContextProvider { voiceDispatcher.currentContextSummary() }
            geminiLive.connect(scope)
        }
    }
    DisposableEffect(geminiLive) {
        onDispose { geminiLive.disconnect() }
    }

    // Route function calls the model emits into the ViewModel.
    LaunchedEffect(geminiLive, voiceDispatcher) {
        geminiLive.commands.collect { voiceDispatcher.dispatch(it) }
    }

    // Speak the AI summary whenever the selected email changes — the core
    // "can I hear it?" demo path. Keeps Gemini Live as the conversational layer
    // and uses on-device TTS for deterministic narration.
    LaunchedEffect(Unit) {
        viewModel.uiState
            .map { it.selectedEmail?.id to it.selectedEmail?.aiSummary }
            .distinctUntilChanged()
            .collect { (_, summary) ->
                if (!summary.isNullOrBlank()) ttsManager.speak(summary)
            }
    }

    // Keep the model grounded in what the user is looking at.
    LaunchedEffect(Unit) {
        viewModel.uiState
            .map { it.selectedEmail?.id }
            .distinctUntilChanged()
            .collect {
                geminiLive.sendContextUpdate(voiceDispatcher.currentContextSummary())
            }
    }

    DisposableEffect(ttsManager) {
        onDispose { ttsManager.shutdown() }
    }

    val faceTracker = remember { FaceAttentionTracker() }
    val handGestures = remember { SecondaryHandGestures() }
    val tiltScroll = remember { TiltScrollController() }
    val gestureMapper = remember(viewModel) { GestureToActionMapper(viewModel) }
    val xrSessionManager = remember { XrSessionManager(faceTracker, handGestures, tiltScroll) }

    val ttsState by ttsManager.playbackState.collectAsStateWithLifecycle()
    val ttsProgress by ttsManager.progress.collectAsStateWithLifecycle()
    val voiceSessionState by geminiLive.state.collectAsStateWithLifecycle()
    val voiceComposeState by voiceCompose.state.collectAsStateWithLifecycle()
    val voiceDraft by voiceCompose.draft.collectAsStateWithLifecycle()
    val tiltScrollDelta by tiltScroll.scrollDelta.collectAsStateWithLifecycle()

    val xrSession = LocalSession.current

    LaunchedEffect(xrSession, xrGranted) {
        if (xrGranted) {
            try {
                xrSessionManager.startAll(
                    session = xrSession,
                    contentResolver = context.contentResolver,
                    scope = scope,
                )
            } catch (t: Throwable) {
                Log.e("XrMail", "XR session start failed — voice will still work", t)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            xrSessionManager.stopAll()
        }
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
        InteractionTierRouter(
            uiState = uiState,
            prioritySortedEmails = viewModel.prioritySortedEmails(),
            ttsState = ttsState,
            ttsProgress = ttsProgress,
            tiltScrollDelta = tiltScrollDelta,
            voiceSessionState = voiceSessionState,
            voiceComposeState = voiceComposeState,
            voiceDraft = voiceDraft,
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

        GestureFeedbackOverlay(gestures = handGestures.gestures)
    }
}

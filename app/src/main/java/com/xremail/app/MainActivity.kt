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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.xr.compose.platform.LocalSession
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.SessionConfigureSuccess
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.xremail.app.backend.service.AuthRepository
import com.xremail.app.backend.service.JwtPayload
import com.xremail.app.backend.service.NetworkClient
import com.xremail.app.backend.service.TokenManager
import com.xremail.app.backend.service.GmailRepository
import com.xremail.app.backend.mock.MockEmailRepository
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import com.xremail.app.tracking.FaceAttentionTracker
import com.xremail.app.tracking.GestureToActionMapper
import com.xremail.app.tracking.KeyboardGestureDispatcher
import com.xremail.app.tracking.SecondaryHandGestures
import com.xremail.app.tracking.TiltScrollController
import com.xremail.app.tracking.XrSessionManager
import com.xremail.app.util.XrLog
import com.xremail.app.ui.auth.SignInScreen
import com.xremail.app.ui.spatial.DisplayMode
import com.xremail.app.ui.spatial.DisplayModeRouter
import com.xremail.app.ui.feedback.GestureFeedbackOverlay
import com.xremail.app.ui.spatial.GlimmerEmailApp
import com.xremail.app.ui.spatial.InteractionTierRouter
import com.xremail.app.ui.theme.XREmailTheme
import com.xremail.app.viewmodel.EmailViewModel
import com.xremail.app.viewmodel.InteractionTier
import com.xremail.app.voice.GeminiTextService
import com.xremail.app.voice.PushToTalkSession
import com.xremail.app.voice.TTSManager
import com.xremail.app.voice.VoiceCommandDispatcher
import com.xremail.app.voice.VoiceComposeManager

class MainActivity : ComponentActivity() {

    // ---------------------------------------------------------------------------
    // Backend wiring.
    //
    // Both values come from `app/build.gradle.kts`:
    //   BuildConfig.USE_REAL_BACKEND — gates SignInScreen and picks
    //     GmailRepository over MockEmailRepository. Debug builds = true,
    //     release builds = false (by default). Voice commands operate on
    //     whichever repository the ViewModel gets.
    //   BuildConfig.BACKEND_URL — defaults to http://localhost:8081/ which
    //     works on both emulator and real XR hardware via
    //     `adb reverse tcp:8081 tcp:8081` (start.sh wires this up).
    //     DO NOT use 10.0.2.2 — it's the emulator-only host alias and
    //     silently times out on a physical Galaxy XR, which is exactly
    //     what made "Sign in with Google" hang on the spinner.
    //
    // Running checklist before using the real backend:
    //   1. `cd backend && ./gradlew :backend:run` (reads backend/.env)
    //      — start.sh now kicks this off automatically.
    //   2. backend/.env must have GOOGLE_CLIENT_ID + GOOGLE_CLIENT_SECRET
    //      with "http://localhost:8081/auth/callback" whitelisted in the
    //      Google Cloud OAuth client's authorized redirect URIs.
    //   3. xrmail://auth/success must be whitelisted client-side — see
    //      AndroidManifest.xml's xrmail:// intent-filter (already there).
    //
    // If the backend is unreachable, SignInScreen now surfaces the error
    // string from AuthRepository instead of silently stopping the spinner.
    // You can also tap "Use mock data" to bypass the round-trip.
    // ---------------------------------------------------------------------------

    /**
     * OAuth / network errors shown beneath the Sign-in button. Cleared on
     * a successful xrmail://auth/success callback; set by AuthRepository
     * return values and xrmail://auth/error deep links.
     */
    private val authErrorState = mutableStateOf<String?>(null)

    private lateinit var tokenManager: TokenManager
    private lateinit var authRepository: AuthRepository

    // Wired by XrMailApp composable so emulator key events reach gesture logic
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

        // Wrap the platform default uncaught-exception handler so any process
        // death gets a structured XrLog entry tagged with the thread that
        // threw — Android's default crash dump goes to the system "crash"
        // logcat buffer which is sometimes wiped between sessions on Galaxy
        // XR. Mirroring it into our normal log makes "the app crashed when I
        // tapped X" reproducible after the fact (`adb logcat -d | grep
        // XrMail/Crash`). We chain to the platform handler so the OS still
        // shows the crash dialog and writes a tombstone.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            try {
                XrLog.e(
                    "Crash",
                    "UNCAUGHT on thread=${thread.name} (id=${thread.id}) — " +
                        "msg=${err.message}",
                    err,
                )
            } catch (_: Throwable) {
                // Logging itself failed — we're already crashing, swallow so
                // we still hand off to the system handler below.
            }
            previous?.uncaughtException(thread, err)
        }

        tokenManager = TokenManager(applicationContext)

        // Build BOTH repositories up front so the SignInScreen's "Use mock
        // data" button can swap at runtime without restarting the activity.
        // GmailRepository is a thin Retrofit wrapper — constructing it
        // without a JWT is cheap; it only makes network calls if you
        // actually hit it signed-out, and in that case AuthInterceptor
        // sends unauthenticated requests which the backend rejects
        // cleanly. The repository the ViewModel sees is chosen in
        // composition below based on login state + mock override.
        val api = NetworkClient.create(
            baseUrl = BuildConfig.BACKEND_URL,
            tokenManager = tokenManager,
            debug = true,
        )
        authRepository = AuthRepository(api, tokenManager)
        val realRepository = GmailRepository(api)
        val mockRepository = MockEmailRepository()

        setContent {
            XREmailTheme {
                // Reactively track login state so the UI swaps from
                // SignInScreen -> XREmailApp the moment the OAuth deep link
                // fires. The Ktor backend redirects to xrmail://auth/success
                // which calls handleOAuthIntent() -> onNewIntent -> onResume,
                // and the LifecycleEventEffect below re-reads isLoggedIn.
                // Using rememberSaveable so a config change (rotation, XR
                // orientation lock toggle) doesn't bounce us back to the
                // sign-in screen after we already have a token.
                var isLoggedIn by rememberSaveable {
                    mutableStateOf(authRepository.isLoggedIn)
                }
                // Runtime override: "Use mock data" on the sign-in screen
                // sets this to true, which skips auth and routes the
                // ViewModel at MockEmailRepository. Intentionally NOT
                // persisted across process restarts — relaunching the
                // app re-shows the sign-in screen so the real OAuth path
                // is still the default behaviour.
                var useMockOverride by remember { mutableStateOf(!BuildConfig.USE_REAL_BACKEND) }

                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    isLoggedIn = authRepository.isLoggedIn
                }

                val showSignIn = BuildConfig.USE_REAL_BACKEND && !isLoggedIn && !useMockOverride
                val activeRepository = if (BuildConfig.USE_REAL_BACKEND && isLoggedIn && !useMockOverride) {
                    realRepository
                } else {
                    mockRepository
                }

                if (showSignIn) {
                    SignInScreen(
                        authRepository = authRepository,
                        authError = authErrorState,
                        onSignedIn = { isLoggedIn = true },
                        onUseMockData = { useMockOverride = true },
                    )
                } else {
                    // Proactively refresh the JWT if it's within an hour of
                    // expiring. Runs on every ON_RESUME while signed in so
                    // we don't surprise the user with a 401 mid-session.
                    if (BuildConfig.USE_REAL_BACKEND && isLoggedIn) {
                        val refreshScope = rememberCoroutineScope()
                        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                            refreshScope.launch {
                                refreshJwtIfNeeded(authRepository, tokenManager)
                            }
                        }
                    }
                    XREmailApp(
                        viewModelFactory = EmailViewModel.Factory(activeRepository)
                    )
                }
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
                authErrorState.value = null
                val state = authRepository.handleCallback(uri)
                Log.i(TAG, "OAuth success — user: ${tokenManager.getUserEmail()}, state: $state")
            }
            "/error" -> {
                val raw = uri.getQueryParameter("reason") ?: "unknown"
                val decoded = runCatching {
                    URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
                }.getOrDefault(raw)
                authErrorState.value = "Sign-in failed: $decoded"
                Log.e(TAG, "OAuth error: $decoded")
            }
        }
    }

    companion object {
        private const val TAG = "XrMailAuth"
    }
}

/**
 * Refresh the JWT early if it's close to expiring (within 1 h). The
 * backend re-issues a new JWT as long as the underlying Google refresh
 * token is still valid; if it fails we let the next authenticated call
 * hit a 401 and AuthInterceptor will surface the failure through the
 * normal error path.
 */
private suspend fun refreshJwtIfNeeded(
    authRepository: AuthRepository,
    tokenManager: TokenManager,
) {
    val token = tokenManager.getToken() ?: return
    val exp = JwtPayload.expiresAtEpochSeconds(token) ?: return
    val now = System.currentTimeMillis() / 1000L
    if (exp - now < 3600) {
        authRepository.refreshToken()
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
    val geminiText = remember { GeminiTextService() }
    val voiceCompose = remember { VoiceComposeManager(ttsManager) }
    val voiceDispatcher = remember(viewModel) {
        VoiceCommandDispatcher(viewModel, ttsManager, geminiText = geminiText, scope = scope)
    }

    // Runtime permissions — mic for Gemini Live, XR sensors for session.configure.
    // Missing XR perms crash OpenXrManager.configure with SecurityException.
    // Galaxy XR (alpha12) doesn't ship `android.permission.SCENE_UNDERSTANDING`
    // — only the suffixed `_COARSE` / `_FINE` variants. Listing the bare name
    // here used to permanently pin `allGranted()` to false because
    // checkSelfPermission() can't resolve a permission the platform has never
    // heard of. That broke FollowingSubspace silently — the head-lock branch
    // in InteractionTierRouter never engaged and the AMBIENT_HUD fell back to
    // a world-anchored Subspace, which is what the user saw as "headlock isn't
    // following when I turn around".
    //
    // We don't list HEAD_TRACKING here either: the alpha12 DeviceTrackingMode
    // enum exposes only DISABLED and LAST_KNOWN, and LAST_KNOWN doesn't
    // require the dangerous HEAD_TRACKING permission. Adding it would force a
    // second prompt that the user could deny without functional consequence.
    val requiredPerms = remember {
        arrayOf(
            Manifest.permission.RECORD_AUDIO,
            "android.permission.HAND_TRACKING",
            "android.permission.FACE_TRACKING",
            "android.permission.EYE_TRACKING_COARSE",
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

    // ---------------------------------------------------------------------------
    // Push-to-talk voice path (replaces the old Gemini Live streaming + always-
    // on local recognizer combo).
    //
    // Why the rewrite: on Galaxy XR, GeminiLiveManager's continuous bidi
    // audio stream stalled indefinitely ("Listening…" with no response) —
    // the SDK's internal VAD never flipped "user done" and its TTS output
    // was routed to a stream the headset doesn't actually play. Every
    // available knob (enableInterruptions, initializationHandler, model
    // pinning) was tried without success.
    //
    // PushToTalkSession uses Android's SpeechRecognizer for ASR (one-shot
    // per tap) + non-live Gemini (generateContent with tools) for reply +
    // platform TextToSpeech for output. Every piece of this pipeline is
    // already proven working on the exact same Galaxy XR hardware in the
    // user's EVA devfest-2026 project, and the same SpeechRecognizer
    // component-pinning trick (prefer com.google.android.tts) that made
    // LocalCommandRecognizer functional is reused here.
    //
    // Trade-offs we accepted:
    //   * Manual tap-to-start / tap-to-stop (no wake word) — the user
    //     explicitly asked for push-to-talk after the Live-API path failed.
    //   * ~1.5-3s one-shot latency vs Live's sub-second streaming — fine
    //     for a system that actually completes turns.
    // ---------------------------------------------------------------------------
    val ptt = remember(voiceDispatcher, ttsManager, geminiText, context) {
        PushToTalkSession(
            context = context,
            tts = ttsManager,
            gemini = geminiText,
            dispatchCommand = voiceDispatcher::dispatch,
            contextProvider = { voiceDispatcher.currentContextSummary() },
        )
    }
    LaunchedEffect(micGranted) {
        if (!micGranted) {
            viewModel.showError(
                "Voice",
                "Mic permission required for voice. Tap the system prompt to allow.",
            )
        }
    }
    DisposableEffect(ptt) {
        onDispose { ptt.shutdown() }
    }
    LaunchedEffect(ptt, viewModel) {
        ptt.lastError.collect { err ->
            if (!err.isNullOrBlank()) viewModel.showError("Voice", err)
        }
    }

    // Auto-summary TTS is INTENTIONALLY DISABLED.
    //
    // User directive (verbatim): "Gemini should only talk if I ask it to
    // and say the 'Hey Gemini'." So no unsolicited narration at all,
    // regardless of tier. Spoken summary is now opt-in via voice command:
    // the user says "Hey Gemini, summarize this" (or "read this") and
    // Gemini Live (or the local dispatcher's "read" command in
    // VoiceCommandDispatcher) reads it back.
    //
    // The previous LaunchedEffect that watched selectedEmail.aiSummary
    // and called ttsManager.speak(summary) on change has been removed.
    // Leaving this block as documentation so future contributors don't
    // re-introduce the auto-narration without checking with the user.

    // NOTE: we used to fire `geminiLive.sendContextUpdate(...)` on every
    // selection change. That counted as a user-role turn and frequently
    // provoked the model to emit a spurious "Got it." or "Okay." reply,
    // which (a) talked over the user's next sentence and (b) burned ~500ms
    // of round-trip on every selection. The model already has access to the
    // current selection through the `setContextProvider` callback that runs
    // at the start of each REAL user turn, so we don't need to push it
    // proactively. Reintroduce a targeted push only if the model starts
    // hallucinating about what's on screen.

    DisposableEffect(ttsManager) {
        onDispose { ttsManager.shutdown() }
    }

    val faceTracker = remember { FaceAttentionTracker() }
    val handGestures = remember { SecondaryHandGestures() }
    val tiltScroll = remember { TiltScrollController() }
    val gestureMapper = remember(viewModel) { GestureToActionMapper(viewModel) }
    val keyboardDispatcher = remember(viewModel, handGestures) { KeyboardGestureDispatcher(viewModel, handGestures) }
    val xrSessionManager = remember { XrSessionManager(faceTracker, handGestures, tiltScroll) }

    // Wire keyboard dispatcher to Activity for emulator key events
    LaunchedEffect(keyboardDispatcher) {
        (context as? MainActivity)?.keyboardDispatcher = keyboardDispatcher
    }

    val ttsState by ttsManager.playbackState.collectAsStateWithLifecycle()
    val ttsProgress by ttsManager.progress.collectAsStateWithLifecycle()
    val pttState by ptt.state.collectAsStateWithLifecycle()
    // The standalone VoiceComposeManager is still around for future
    // Gemini-driven flows (edit/confirm via live voice). For the current
    // "Voice" button + canned-reply demo the ViewModel is the source of
    // truth: `viewModel.voiceReply(body)` pushes the draft into
    // `uiState.voiceDraft` / `uiState.isVoiceComposing`, and we derive a
    // matching compose-state below so the overlay UI (which gates on
    // `VoiceComposeManager.ComposeState`) actually renders.
    val _voiceComposeManagerState by voiceCompose.state.collectAsStateWithLifecycle()
    val _voiceComposeManagerDraft by voiceCompose.draft.collectAsStateWithLifecycle()
    val voiceDraft = _voiceComposeManagerDraft ?: uiState.voiceDraft
    val voiceComposeState: VoiceComposeManager.ComposeState = when {
        _voiceComposeManagerState != VoiceComposeManager.ComposeState.IDLE ->
            _voiceComposeManagerState
        uiState.isVoiceComposing && uiState.voiceDraft?.isGenerating == true ->
            VoiceComposeManager.ComposeState.GENERATING
        uiState.isVoiceComposing && uiState.voiceDraft?.draftText?.isNotBlank() == true ->
            VoiceComposeManager.ComposeState.AWAITING_CONFIRM
        uiState.isVoiceComposing ->
            VoiceComposeManager.ComposeState.LISTENING
        else ->
            VoiceComposeManager.ComposeState.IDLE
    }
    val tiltScrollDelta by tiltScroll.scrollDelta.collectAsStateWithLifecycle()

    val xrSession = LocalSession.current

    // CRITICAL: configure DeviceTrackingMode SYNCHRONOUSLY here, *before* the
    // child composition gets to call `FollowTarget.ArDevice(xrSession)`.
    //
    // Galaxy XR ships its default `Config.deviceTracking = DISABLED`. The
    // FollowingSubspace API's `FollowTarget.ArDevice(session)` constructor
    // calls `ArDevice.getInstance(session)` which throws
    // `IllegalStateException("Config.DeviceTrackingMode is set to DISABLED")`
    // unless device tracking is enabled FIRST. We can't rely on
    // `XrSessionManager.startAll` to do this from a `LaunchedEffect` — that
    // coroutine fires after composition, which means the very first
    // recomposition of `InteractionTierRouter` while the user is in
    // AMBIENT_HUD would crash. Doing the configure inside `remember(xrSession)`
    // gives us a synchronous gate: composition only proceeds to construct
    // the FollowTarget once we've set LAST_KNOWN and gotten a success result.
    val deviceTrackingReady: Boolean = remember(xrSession, xrGranted) {
        if (xrSession == null || !xrGranted) {
            XrLog.i("Session", "deviceTrackingReady=false (session=$xrSession granted=$xrGranted)")
            false
        } else {
            try {
                val cfg = xrSession.config.copy(deviceTracking = DeviceTrackingMode.LAST_KNOWN)
                val result = xrSession.configure(cfg)
                val ok = result is SessionConfigureSuccess
                if (ok) {
                    XrLog.i("Session", "deviceTrackingReady=true (LAST_KNOWN configured)")
                } else {
                    XrLog.w("Session", "deviceTracking configure failed: $result")
                }
                ok
            } catch (t: Throwable) {
                XrLog.e("Session", "deviceTracking configure threw", t)
                false
            }
        }
    }

    LaunchedEffect(xrSession, xrGranted) {
        if (xrGranted) {
            try {
                xrSessionManager.startAll(
                    session = xrSession,
                    contentResolver = context.contentResolver,
                    scope = scope,
                )
            } catch (t: Throwable) {
                XrLog.e("Session", "XR session start failed — voice will still work", t)
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
            XrLog.i("Tier", "gesture=$gesture in tier=${viewModel.uiState.value.tier.name}")
            // bumpInteraction is fired inside SecondaryHandGestures.emit() at
            // the source of every gesture, so we don't re-bump here. The two
            // happen on the same coroutine path before any consumer sees the
            // gesture, which means by the time `gestureMapper.onGesture` runs,
            // `lastInteractionMs` is already current — the gaze-dwell lockout
            // observes the bump just like the dispatcher does.
            gestureMapper.onGesture(gesture, viewModel.uiState.value.tier)
        }
    }

    // Verbose tier-transition trail — single source of truth for "what tier
    // are we in right now" so timeline-debugging multimodal interactions
    // becomes a one-grep operation in logcat.
    LaunchedEffect(viewModel) {
        var previous: InteractionTier? = null
        viewModel.uiState
            .map { it.tier }
            .distinctUntilChanged()
            .collect { tier ->
                XrLog.tier(
                    from = previous?.name ?: "init",
                    to = tier.name,
                    via = "uiState.collect",
                )
                previous = tier
            }
    }

    LaunchedEffect(faceTracker, ttsManager) {
        faceTracker.isAttentive.collect { attentive ->
            ttsManager.onAttentionChanged(attentive)
        }
    }

    // Tier escalation is gesture-only. Gaze NEVER expands or collapses a
    // tier — the previous gaze-dwell-on-banner experiment (deleted along
    // with GazeDwellNotificationBanner.kt) felt random because the user
    // didn't realize a 400 ms look fired the same code path as a pinch.
    // FaceAttentionTracker.isGazingAtNotificationZone is still computed
    // for telemetry / future ambient-priority work, but is intentionally
    // not wired to any tier transition.

    Box(modifier = Modifier.fillMaxSize()) {
        InteractionTierRouter(
            uiState = uiState,
            prioritySortedEmails = viewModel.prioritySortedEmails(),
            ttsState = ttsState,
            ttsProgress = ttsProgress,
            tiltScrollDelta = tiltScrollDelta,
            pttState = pttState,
            voiceComposeState = voiceComposeState,
            voiceDraft = voiceDraft,
            handGestures = handGestures,
            deviceTrackingReady = deviceTrackingReady,
            onKeyDown = keyboardDispatcher::onKeyDown,
            onExpandToNotifications = viewModel::expandToNotificationCards,
            onCollapseFromNotifications = viewModel::collapseFromNotificationCards,
            onExpandToInbox = viewModel::expandToInbox,
            onCollapseToHud = viewModel::collapseToHud,
            onExpandToFocus = viewModel::expandToFocus,
            onCollapseToInbox = viewModel::collapseToInbox,
            onEmailSelected = viewModel::selectEmail,
            onOpenFromNotification = viewModel::openFromNotification,
            onCategorySelected = viewModel::filterByCategory,
            onMailboxSelected = viewModel::selectMailbox,
            onToggleAiSummary = viewModel::toggleAiSummary,
            onReply = viewModel::startCompose,
            onArchive = viewModel::archiveSelected,
            onArchiveEmail = viewModel::archiveEmail,
            onSnooze = viewModel::snoozeSelected,
            onSnoozeEmail = viewModel::snoozeEmail,
            onForward = viewModel::forwardSelected,
            onSend = { viewModel.sendDraft() },
            onCancelCompose = viewModel::cancelCompose,
            onDismissToast = viewModel::dismissToast,
            onConfirmSend = viewModel::confirmSend,
            onDismissSendConfirmation = viewModel::dismissSendConfirmation,
            onToggleVoice = { ptt.toggle() },
            // Voice compose — "Voice" button fires a canned instruction so you can
            // exercise the full GENERATING → draft → send flow without a mic/headset.
            onVoiceReply = { viewModel.voiceReply("I'll get back to you by Friday") },
            onConfirmVoiceSend = viewModel::sendDraft,
            onCancelVoice = viewModel::cancelCompose,
        )

        // Gesture feedback pill is rendered UNCONDITIONALLY in every
        // tier. The user explicitly asked for "gesture recognition
        // feedback and consistency across tiers" — gating it to
        // INBOX/FOCUS meant users in AMBIENT_HUD or NOTIFICATION_CARDS
        // fired gestures with zero visible confirmation. The pill
        // overlays the main panel (which is a placeholder in peripheral
        // tiers, so the pill just shows up against that placeholder;
        // good enough — we get consistent feedback across every tier).
        GestureFeedbackOverlay(gestures = handGestures.gestures)
    }

    // NOTE: the head-locked gesture-feedback pill for AMBIENT_HUD /
    // NOTIFICATION_CARDS used to live here, in its own FollowingSubspace.
    // It now rides INSIDE the shared FollowingSubspace owned by
    // InteractionTierRouter so:
    //   1. There's only one ArDevice tracker for peripheral tiers (was
    //      three: HUD, cards, gesture pill — all racing each other).
    //   2. The pill stops sliding relative to the HUD when the user turns,
    //      because both panels are now in the same follow scope.
    //   3. Tier transitions don't tear down two separate FollowingSubspaces
    //      back-to-back — see TierRouter for the full rationale.
}

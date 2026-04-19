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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.xr.compose.platform.LocalSession
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.SessionConfigureSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
import com.xremail.app.util.XrLog
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
import com.xremail.app.voice.LocalCommandRecognizer

class MainActivity : ComponentActivity() {

    // ---------------------------------------------------------------------------
    // Backend wiring — swap USE_REAL_BACKEND to true once the server is running
    // ---------------------------------------------------------------------------

    private val USE_REAL_BACKEND = false
    private val BACKEND_URL = "http://10.0.2.2:8080/" // emulator → host loopback

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
    val voiceCompose = remember { VoiceComposeManager(ttsManager) }
    val voiceDispatcher = remember(viewModel) {
        VoiceCommandDispatcher(viewModel, ttsManager)
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

    // Connect Gemini Live once the mic permission is settled — but DON'T
    // open the mic. The WebSocket warms up so the wake-word path can flip
    // straight from CONNECTED -> LISTENING in <100ms. Without this split
    // we used to stream the user's mic to the cloud as soon as the app
    // opened, and the model would respond to background conversation /
    // ambient noise — exactly the "always on, working really weirdly"
    // behaviour the user reported.
    LaunchedEffect(micGranted) {
        if (micGranted) {
            geminiLive.setContextProvider { voiceDispatcher.currentContextSummary() }
            geminiLive.connect(scope)
        }
    }
    DisposableEffect(geminiLive) {
        onDispose { geminiLive.disconnect() }
    }

    // ---------------------------------------------------------------------------
    // Local-first voice path — replaces the old wake-word + always-on Gemini
    // Live flow that the user described as "tons of delay, not conversational".
    //
    // Architecture:
    //   1. LocalCommandRecognizer runs continuously on-device (Android
    //      SpeechRecognizer, on-device variant when available). It captures
    //      the WHOLE utterance — wake phrase + command — in a single round.
    //   2. CommandGrammar parses the transcript:
    //        - "hey gemini archive this"   -> Command.ArchiveEmail (instant)
    //        - "hey gemini draft a reply"  -> Escalate("draft a reply")
    //                                          → summon Gemini Live + send text
    //        - "open the door"             -> NotAddressed (no wake) → ignored
    //   3. Matched commands fire DIRECTLY into VoiceCommandDispatcher with
    //      ZERO cloud round-trip (~300-500 ms total: ASR partial + dispatch).
    //   4. Only escalations open Gemini Live's mic / WebSocket — so the
    //      "always-on" model behaviour the user disliked is gone.
    //
    // Why we kept Gemini Live around at all:
    //   The grammar is good for the obvious commands (archive/snooze/next/
    //   open/close/summarize/refresh/reply/send/search/filter), but it can't
    //   handle "draft a reply saying I'm running late" or "what's the most
    //   urgent thing in my inbox". Those still need a model. The Escalate
    //   path forwards the post-wake remainder to Gemini as a text turn so
    //   the user doesn't have to repeat themselves after the SDK warms the
    //   mic — which was the OTHER big latency source ("hey gemini" → SDK
    //   spin-up → user re-says command).
    val localCommands = remember(geminiLive, voiceDispatcher) {
        LocalCommandRecognizer(
            context = context,
            onLocalCommand = { command ->
                XrLog.i("Voice", "local-dispatch: $command (no cloud round-trip)")
                voiceDispatcher.dispatch(command)
            },
            onEscalateToGemini = { remainder ->
                XrLog.i("Voice", "escalate-to-gemini: \"$remainder\"")
                geminiLive.summon()
                if (remainder.isNotBlank()) {
                    // Forward the user's intent as a TEXT turn so Gemini
                    // doesn't have to re-listen from a cold mic. Saves
                    // 1-2s of perceived latency vs the old flow.
                    scope.launch {
                        try {
                            geminiLive.sendContextUpdate(remainder)
                        } catch (t: Throwable) {
                            XrLog.w("Voice", "forward-to-gemini failed: ${t.message}")
                        }
                    }
                }
            },
        )
    }
    LaunchedEffect(micGranted) {
        if (micGranted) localCommands.start()
    }
    DisposableEffect(localCommands) {
        onDispose { localCommands.stop() }
    }

    // Coordinate the recognizer with Gemini's mic ownership. SAME contract
    // as the old wake-word coordinator: pause us while Gemini holds the
    // mic (Android won't share the AudioRecord), resume us when Gemini
    // dismisses. Without this, every recognizer round returns
    // ERROR_RECOGNIZER_BUSY and the local command path silently dies
    // after the first Gemini conversation.
    LaunchedEffect(geminiLive, localCommands) {
        geminiLive.state
            .map { it == GeminiLiveManager.SessionState.LISTENING }
            .distinctUntilChanged()
            .collect { isListening ->
                if (isListening) {
                    localCommands.pause()
                } else if (micGranted) {
                    localCommands.resume()
                }
            }
    }

    // Idle-dismiss: after IDLE_TIMEOUT_MS of no model activity (no
    // function calls, no manual summon), close the mic so the user has
    // to wake Gemini again. Keeps the sense that Gemini is "asleep
    // unless addressed", and stops the perception that the model is
    // randomly responding to passing conversation.
    LaunchedEffect(geminiLive) {
        geminiLive.lastActivityMs.collect { lastActivity ->
            if (lastActivity == 0L) return@collect
            if (geminiLive.state.value != GeminiLiveManager.SessionState.LISTENING) return@collect
            scope.launch {
                delay(IDLE_TIMEOUT_MS)
                val sinceLast = System.currentTimeMillis() - geminiLive.lastActivityMs.value
                val stillListening = geminiLive.state.value ==
                    GeminiLiveManager.SessionState.LISTENING
                if (stillListening && sinceLast >= IDLE_TIMEOUT_MS) {
                    XrLog.i("Wake", "idle-dismiss: ${sinceLast}ms since last activity")
                    geminiLive.dismiss()
                }
            }
        }
    }

    // Route function calls the model emits into the ViewModel. After each
    // dispatch we wait briefly and then mark the model idle so the auto-TTS
    // summary path can re-engage. The grace window covers the model's short
    // verbal confirmation that follows most function calls — without it, a
    // selection-via-voice would fire local TTS on top of the model's "Done."
    LaunchedEffect(geminiLive, voiceDispatcher) {
        geminiLive.commands.collect { command ->
            voiceDispatcher.dispatch(command)
            geminiLive.bumpActivity()
            scope.launch {
                delay(MODEL_TURN_GRACE_MS)
                geminiLive.markModelIdle()
            }
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
    val voiceSessionState by geminiLive.state.collectAsStateWithLifecycle()
    val localRecognizerState by localCommands.state.collectAsStateWithLifecycle()
    val voiceComposeState by voiceCompose.state.collectAsStateWithLifecycle()
    val voiceDraft by voiceCompose.draft.collectAsStateWithLifecycle()
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
            voiceSessionState = voiceSessionState,
            localRecognizerState = localRecognizerState,
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
            onSummonGemini = { geminiLive.summon() },
        )

        // Show the 2D pill *only* when the main panel actually owns the user's
        // foveal view (INBOX / FOCUS). For peripheral / lazy-follow tiers the
        // main panel is offscreen-or-tiny and we'd rather render the pill
        // head-locked in front of the user — see [HeadLockedGestureFeedback].
        // Without this gate, both copies would collect from the same
        // SharedFlow and the user would feel a double-haptic on every pinch.
        val showInline2dFeedback = uiState.tier == InteractionTier.INBOX ||
            uiState.tier == InteractionTier.FOCUS
        if (showInline2dFeedback) {
            GestureFeedbackOverlay(gestures = handGestures.gestures)
        }
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

/**
 * Grace period after a function call before we mark the model "idle" again.
 * Sized to cover the model's typical short verbal confirmation ("Done.",
 * "Archived.", "Got it.") so the local TTS auto-summary doesn't talk over
 * the tail end of Gemini's reply.
 */
private const val MODEL_TURN_GRACE_MS = 1_500L

/**
 * After this much time without any Gemini activity (no function call, no
 * explicit re-summon), close the mic and require another "hey gemini" wake
 * word to re-open the conversation. Tuned long enough that natural pauses
 * mid-task ("um, archive that one... and the next") don't dismiss, but
 * short enough that the user doesn't accidentally leave the mic open
 * while putting the headset down.
 */
private const val IDLE_TIMEOUT_MS = 18_000L


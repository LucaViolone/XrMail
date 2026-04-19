package com.xremail.app.ui.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.FollowBehavior
import androidx.xr.compose.subspace.FollowTarget
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.TrackedDimensions
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.width
import com.xremail.app.data.Email
import com.xremail.app.data.EmailCategory
import com.xremail.app.data.Priority
import com.xremail.app.tracking.SecondaryHandGestures
import com.xremail.app.ui.notifications.NotificationCardStack
import com.xremail.app.ui.peripheral.AmbientHud
import com.xremail.app.ui.peripheral.CollapseAffordance
import com.xremail.app.ui.peripheral.InboxPanel
import com.xremail.app.ui.peripheral.VoiceComposeOverlay
import com.xremail.app.ui.peripheral.VoicePrompt
import com.xremail.app.ui.theme.XREmailColors
import com.xremail.app.util.XrLog
import com.xremail.app.viewmodel.EmailUiState
import com.xremail.app.viewmodel.InteractionTier
import com.xremail.app.viewmodel.VoiceDraft
import com.xremail.app.voice.GeminiLiveManager
import com.xremail.app.voice.LocalCommandRecognizer
import com.xremail.app.voice.TTSManager
import com.xremail.app.voice.VoiceComposeManager

private const val TAG = "TierRouter"

// ---------------------------------------------------------------------------
// PERIPHERAL HUD architecture (post-2026-04-18-pm rewrite).
//
// User goal (verbatim): "Off to the right side that stays in my peripheral
// vision." Not a center-stage full panel. Not something that disappears
// when the head turns.
//
// Design:
//
//   * One ALWAYS-MOUNTED FollowingSubspace at the top of the composition.
//     It hosts a SMALL SpatialPanel (320dp x 460dp) positioned to the
//     right and slightly forward of the user. Tier content swaps INSIDE
//     this panel with AnimatedContent — the panel itself never unmounts,
//     so the user never sees it disappear during a tier transition.
//
//   * FollowBehavior.Soft(80ms) so the panel drifts naturally with head
//     yaw without feeling rigidly nailed to the eyeballs (which causes
//     motion-sickness) and without lagging so far behind that it walks
//     out of view.
//
//   * TrackedDimensions tracks all 3 translation axes + yaw only. We
//     intentionally DO NOT track pitch/roll: when the user looks up at
//     the ceiling or tilts their head, the panel stays upright and at
//     comfortable eye level rather than swinging overhead.
//
//   * The panel is positioned at offset(x=180dp, y=-40dp, z=-260dp).
//     That puts it ~26cm in front of the user, ~18cm to the right,
//     ~4cm below eye level — squarely in right peripheral, in arm's reach
//     for pinch interaction, but out of the way of forward gaze.
//
//   * If the XR session isn't ready (emulator / no headset), we fall
//     back to rendering the same content as the main activity window
//     so dev iteration still works.
//
//   * FOCUS tier still spawns its own multi-panel SpatialEmailLayout in
//     a separate Subspace because that's the explicit "spread email
//     across 3D space" mode. The peripheral HUD stays mounted alongside
//     it so the user can always see voice / notification status.
//
// Tier expansion is GESTURE-ONLY (gaze dwell removed).
// ---------------------------------------------------------------------------

@OptIn(androidx.xr.compose.spatial.ExperimentalFollowingSubspaceApi::class)
@Composable
fun InteractionTierRouter(
    uiState: EmailUiState,
    prioritySortedEmails: List<Email>,
    ttsState: TTSManager.PlaybackState,
    ttsProgress: Float,
    tiltScrollDelta: Float,
    voiceSessionState: GeminiLiveManager.SessionState,
    localRecognizerState: LocalCommandRecognizer.State =
        LocalCommandRecognizer.State.IDLE,
    voiceComposeState: VoiceComposeManager.ComposeState,
    voiceDraft: VoiceDraft?,
    handGestures: SecondaryHandGestures? = null,
    deviceTrackingReady: Boolean = false,
    onKeyDown: (Int) -> Boolean = { false },
    onExpandToNotifications: () -> Unit,
    onCollapseFromNotifications: () -> Unit,
    onExpandToInbox: () -> Unit,
    onCollapseToHud: () -> Unit,
    onExpandToFocus: () -> Unit,
    onCollapseToInbox: () -> Unit,
    onEmailSelected: (Email) -> Unit,
    onOpenFromNotification: (Email) -> Unit,
    onCategorySelected: (EmailCategory?) -> Unit,
    onToggleAiSummary: () -> Unit,
    onReply: () -> Unit,
    onArchive: () -> Unit,
    onArchiveEmail: (Email) -> Unit,
    onSnooze: () -> Unit,
    onSnoozeEmail: (Email) -> Unit,
    onForward: () -> Unit,
    onSend: () -> Unit,
    onCancelCompose: () -> Unit,
    onDismissToast: () -> Unit,
) {
    val keyEventModifier = Modifier
        .onPreviewKeyEvent { event: KeyEvent ->
            if (event.type == KeyEventType.KeyDown) onKeyDown(event.key.nativeKeyCode) else false
        }
        .focusTarget()

    val xrSession = LocalSession.current
    val canUsePeripheral = deviceTrackingReady && xrSession != null

    // Live 0f→1f progress of the user's open-palm "collapse" hold.
    // Drives the CollapseAffordance ring so users see real-time feedback
    // as they perform the gesture (and can release to cancel mid-hold).
    val openPalmProgress by (handGestures?.openPalmProgress
        ?: kotlinx.coroutines.flow.MutableStateFlow(0f)).collectAsState()

    LaunchedEffect(uiState.tier, canUsePeripheral) {
        XrLog.i(
            TAG,
            "rendering tier=${uiState.tier.name} peripheral=$canUsePeripheral " +
                "(session=${xrSession != null} tracking=$deviceTrackingReady)",
        )
    }

    // ---------------------------------------------------------------------------
    // Main activity panel: deliberately minimal. The user doesn't read mail
    // here — this is just the OS-mandated host window. We render an
    // instructional placeholder so when the user happens to glance at it
    // they understand the real UI is the small peripheral panel to the right.
    // ---------------------------------------------------------------------------
    Surface(
        color = XREmailColors.surface,
        modifier = Modifier.fillMaxSize().then(keyEventModifier),
    ) {
        if (canUsePeripheral) {
            MainPanelPlaceholder(
                voiceSessionState = voiceSessionState,
                localRecognizerState = localRecognizerState,
            )
        } else {
            // Emulator / no headset: render the active tier here so dev
            // iteration still works without an XR runtime.
            FallbackTierContent(
                uiState = uiState,
                prioritySortedEmails = prioritySortedEmails,
                ttsState = ttsState,
                ttsProgress = ttsProgress,
                tiltScrollDelta = tiltScrollDelta,
                voiceSessionState = voiceSessionState,
                localRecognizerState = localRecognizerState,
                voiceComposeState = voiceComposeState,
                voiceDraft = voiceDraft,
                openPalmProgress = openPalmProgress,
                onExpandToNotifications = onExpandToNotifications,
                onCollapseFromNotifications = onCollapseFromNotifications,
                onExpandToInbox = onExpandToInbox,
                onCollapseToHud = onCollapseToHud,
                onExpandToFocus = onExpandToFocus,
                onEmailSelected = onEmailSelected,
                onOpenFromNotification = onOpenFromNotification,
                onArchiveEmail = onArchiveEmail,
                onSnoozeEmail = onSnoozeEmail,
                onDismissToast = onDismissToast,
            )
        }
    }

    // ---------------------------------------------------------------------------
    // PERIPHERAL HUD — small, head-locked, ALWAYS mounted (including during
    // FOCUS — we just hide content during FOCUS so the entity stays alive
    // and never has to remount, which was the source of the disappear/
    // reappear glitch).
    //
    // STABILITY: every parameter passed to FollowingSubspace is `remember`d
    // by xrSession identity. Without this, every recomposition (and there
    // are MANY — every tier transition, every voice state change, every
    // TTS tick) constructs a fresh FollowTarget / FollowBehavior /
    // TrackedDimensions instance. Each new instance has a new hash, which
    // trips FollowingSubspace's internal LaunchedEffect, which tears down
    // and recreates the spatial entity. That entity recreation IS the
    // glitch the user kept reporting: panel goes invisible for ~300ms
    // every time anything happens.
    //
    // Also: NO AnimatedContent inside the SpatialPanel. AnimatedContent
    // briefly renders both old and new content during a tier change, and
    // for reasons not entirely clear that confuses the spatial panel's
    // measurement pass and produces visible flicker. Direct `when`-based
    // swap is fine — the panel itself stays stable; only its contents
    // change.
    // ---------------------------------------------------------------------------
    if (canUsePeripheral && xrSession != null) {
        // Stable references. NEVER recompute these per recomposition.
        val followTarget = remember(xrSession) { FollowTarget.ArDevice(xrSession) }
        // 280ms = noticeably smoother than 120ms. The panel "drifts" with
        // your head turn instead of snapping. Big enough to feel relaxed,
        // small enough that a deliberate look-to-the-right brings the panel
        // back into view in well under half a second.
        val followBehavior = remember { FollowBehavior.Soft(durationMs = 280) }
        val trackedDims = remember {
            // Track XYZ position + yaw only. Pitch/roll untracked so the
            // panel stays upright at eye level when the user tilts/looks
            // up or down.
            TrackedDimensions(
                isTranslationXTracked = true,
                isTranslationYTracked = true,
                isTranslationZTracked = true,
                isRotationXTracked = false,
                isRotationYTracked = true,
                isRotationZTracked = false,
            )
        }

        androidx.xr.compose.spatial.FollowingSubspace(
            target = followTarget,
            behavior = followBehavior,
            dimensions = trackedDims,
        ) {
            // Tier-dependent panel size. AMBIENT_HUD is a small ambient
            // banner; NOTIFICATION_CARDS is a medium peripheral preview;
            // INBOX is the full-size reading panel. We resize the panel
            // itself (via the SubspaceModifier) instead of constraining
            // content because rendering small content inside a giant
            // phantom panel wastes spatial real estate and looks weird.
            // Modifier changes do NOT remount the SpatialPanel entity —
            // only the followTarget / behavior / dimensions identity
            // matters for that, and those are still remember()'d above.
            // Per-tier panel width is fixed (a panel that grows wider on
            // each tier transition feels jarring), but per-tier HEIGHT is
            // content-driven for the tiers where empty space wastes
            // peripheral real estate.
            val panelWidth = when (uiState.tier) {
                InteractionTier.AMBIENT_HUD -> 320.dp
                InteractionTier.NOTIFICATION_CARDS -> 380.dp
                else -> 480.dp
            }
            // NOTIFICATION_CARDS height = chrome (header + voice prompt +
            // collapse affordance + footer hint + outer padding) plus the
            // actual visible card count. Without this, an inbox with 1
            // unread email would render in a panel sized for 5 — exactly
            // the "still really big with empty space around them" the
            // user complained about after collapsing back from FOCUS.
            //
            // Magic numbers tuned to the current NotificationCardStack /
            // NotificationCard layout. If those padding/sizes change,
            // bump CHROME_DP / PER_CARD_DP accordingly.
            val visibleNotifCards = uiState.unreadCount
                .coerceAtMost(5)
                .coerceAtLeast(1)
            val panelHeight = when (uiState.tier) {
                // Just a small ambient banner + voice prompt + hint.
                // 240 had ~70dp of dead space below the banner; 200 is
                // tight to the actual content.
                InteractionTier.AMBIENT_HUD -> 200.dp
                InteractionTier.NOTIFICATION_CARDS -> {
                    val CHROME_DP = 180
                    val PER_CARD_DP = 70
                    val computed = CHROME_DP + visibleNotifCards * PER_CARD_DP
                    // Clamp: never grow past 540 (one cards-worth shy of
                    // the old 460 hard cap was still cramped at 5; 540
                    // gives the 5th card room to breathe). Never shrink
                    // below 240 — below that the header row + hint look
                    // squashed even with 0 cards.
                    computed.coerceIn(240, 540).dp
                }
                else -> 680.dp
            }
            // Recenter horizontal offset so smaller panels don't drift
            // way out into the user's far-right peripheral. Bigger panel
            // → push further right so it doesn't crowd central vision.
            val panelOffsetX = when (uiState.tier) {
                InteractionTier.AMBIENT_HUD -> 200.dp
                InteractionTier.NOTIFICATION_CARDS -> 220.dp
                else -> 260.dp
            }
            SpatialPanel(
                modifier = SubspaceModifier
                    .width(panelWidth)
                    .height(panelHeight)
                    .offset(x = panelOffsetX, y = (-40).dp, z = (-340).dp),
            ) {
                // Hide content during FOCUS so the panel "looks empty" but
                // the entity stays mounted — no remount glitch on collapse.
                if (uiState.tier != InteractionTier.FOCUS) {
                    Surface(
                        color = XREmailColors.surface,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        PeripheralTierContent(
                            tier = uiState.tier,
                            uiState = uiState,
                            prioritySortedEmails = prioritySortedEmails,
                            ttsState = ttsState,
                            ttsProgress = ttsProgress,
                            tiltScrollDelta = tiltScrollDelta,
                            voiceSessionState = voiceSessionState,
                            localRecognizerState = localRecognizerState,
                            voiceComposeState = voiceComposeState,
                            voiceDraft = voiceDraft,
                            openPalmProgress = openPalmProgress,
                            onExpandToNotifications = onExpandToNotifications,
                            onCollapseFromNotifications = onCollapseFromNotifications,
                            onExpandToInbox = onExpandToInbox,
                            onCollapseToHud = onCollapseToHud,
                            onExpandToFocus = onExpandToFocus,
                            onEmailSelected = onEmailSelected,
                            onOpenFromNotification = onOpenFromNotification,
                            onArchiveEmail = onArchiveEmail,
                            onSnoozeEmail = onSnoozeEmail,
                            onDismissToast = onDismissToast,
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // FOCUS tier: deliberate spatial expansion. Uses a separate Subspace
    // for the multi-panel curved row.
    // ---------------------------------------------------------------------------
    if (uiState.tier == InteractionTier.FOCUS) {
        Subspace {
            SpatialEmailLayout(
                emails = uiState.emails,
                selectedEmail = uiState.selectedEmail,
                selectedContact = uiState.selectedContact,
                mode = uiState.mode,
                activeCategory = uiState.activeCategory,
                isAiSummaryExpanded = uiState.isAiSummaryExpanded,
                unreadCount = uiState.unreadCount,
                onEmailSelected = onEmailSelected,
                onCategorySelected = onCategorySelected,
                onToggleAiSummary = onToggleAiSummary,
                onReply = onReply,
                onArchive = onArchive,
                onSnooze = onSnooze,
                onForward = onForward,
                onSend = onSend,
                onCancelCompose = onCancelCompose,
                onCollapse = onCollapseToInbox,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// MAIN PANEL PLACEHOLDER
// ---------------------------------------------------------------------------

@Composable
private fun MainPanelPlaceholder(
    voiceSessionState: GeminiLiveManager.SessionState,
    localRecognizerState: LocalCommandRecognizer.State,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "XR Mail",
                color = XREmailColors.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Look to your right",
                color = XREmailColors.onSurfaceDim,
            )
            Text(
                text = "Your peripheral HUD is anchored to the right side of your view.",
                color = XREmailColors.onSurfaceDim,
            )
            VoicePrompt(
                voiceState = voiceSessionState,
                localState = localRecognizerState,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// PERIPHERAL TIER CONTENT — what shows up inside the head-locked panel
// for each tier. Compact layouts sized for a 320x460 surface.
// ---------------------------------------------------------------------------

@Composable
private fun PeripheralTierContent(
    tier: InteractionTier,
    uiState: EmailUiState,
    prioritySortedEmails: List<Email>,
    ttsState: TTSManager.PlaybackState,
    ttsProgress: Float,
    tiltScrollDelta: Float,
    voiceSessionState: GeminiLiveManager.SessionState,
    localRecognizerState: LocalCommandRecognizer.State,
    @Suppress("UNUSED_PARAMETER") voiceComposeState: VoiceComposeManager.ComposeState,
    voiceDraft: VoiceDraft?,
    openPalmProgress: Float,
    onExpandToNotifications: () -> Unit,
    onCollapseFromNotifications: () -> Unit,
    onExpandToInbox: () -> Unit,
    onCollapseToHud: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onExpandToFocus: () -> Unit,
    onEmailSelected: (Email) -> Unit,
    onOpenFromNotification: (Email) -> Unit,
    onArchiveEmail: (Email) -> Unit,
    onSnoozeEmail: (Email) -> Unit,
    onDismissToast: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VoicePrompt(
                voiceState = voiceSessionState,
                localState = localRecognizerState,
                compact = true,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Visible collapse affordance — only when there's somewhere
            // to collapse to. AMBIENT_HUD is the floor of the tier
            // hierarchy so it doesn't get one (open-palm there is a no-op
            // in the gesture mapper anyway). Ring fills 0→1 as the user
            // holds an open palm so the gesture is discoverable AND the
            // user gets live confirmation it's being recognized.
            if (tier != InteractionTier.AMBIENT_HUD) {
                CollapseAffordance(
                    progress = openPalmProgress,
                    label = when (tier) {
                        InteractionTier.NOTIFICATION_CARDS -> "Open palm to dismiss"
                        InteractionTier.INBOX -> "Open palm to back out"
                        InteractionTier.FOCUS -> "Open palm to back out"
                        else -> "Open palm to collapse"
                    },
                )
            }
        }

        when (tier) {
            InteractionTier.AMBIENT_HUD -> {
                AmbientHud(
                    unreadCount = uiState.unreadCount,
                    hasHighPriority = uiState.emails.any {
                        it.priority == Priority.HIGH && !it.isRead
                    },
                    emails = uiState.emails,
                    ttsState = ttsState,
                    ttsProgress = ttsProgress,
                    voiceState = voiceSessionState,
                    toastMessage = uiState.toastMessage,
                    onExpandToNotifications = {
                        XrLog.tier(
                            "AMBIENT_HUD", "NOTIFICATION_CARDS",
                            "banner.expand (pinch / click)",
                        )
                        onExpandToNotifications()
                    },
                    onDismissToast = onDismissToast,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Pinch + hold to expand",
                    color = XREmailColors.onSurfaceDim,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            InteractionTier.NOTIFICATION_CARDS -> {
                NotificationCardStack(
                    emails = uiState.emails,
                    highlightedId = uiState.highlightedNotificationId,
                    tiltScrollDelta = tiltScrollDelta,
                    onSelectEmail = onOpenFromNotification,
                    onArchiveEmail = onArchiveEmail,
                    onSnoozeEmail = onSnoozeEmail,
                    onCollapseToHud = onCollapseFromNotifications,
                    onExpandToInbox = onExpandToInbox,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                )
                Text(
                    text = "Pinch a card to open it",
                    color = XREmailColors.onSurfaceDim,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            InteractionTier.INBOX -> {
                Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                    InboxPanel(
                        emails = prioritySortedEmails,
                        selectedEmail = uiState.selectedEmail,
                        ttsState = ttsState,
                        ttsSummary = uiState.selectedEmail?.aiSummary ?: "",
                        tiltScrollDelta = tiltScrollDelta,
                        onEmailSelected = onEmailSelected,
                        onArchive = onArchiveEmail,
                        onSnooze = onSnoozeEmail,
                        onCollapseToHud = onCollapseToHud,
                        onExpandToFocus = onExpandToFocus,
                    )
                }
            }

            InteractionTier.FOCUS -> {
                // Should never reach here — FOCUS suppresses the peripheral
                // panel. Render nothing as a safety fallback.
            }
        }

        if (uiState.isVoiceComposing &&
            (tier == InteractionTier.AMBIENT_HUD ||
                tier == InteractionTier.NOTIFICATION_CARDS)
        ) {
            VoiceComposeOverlay(
                draft = voiceDraft ?: uiState.voiceDraft,
                composeState = voiceComposeState,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// FALLBACK (no XR session — emulator / dev iteration). Renders the active
// tier content as the main panel so devs can still iterate without a
// headset.
// ---------------------------------------------------------------------------

@Composable
private fun FallbackTierContent(
    uiState: EmailUiState,
    prioritySortedEmails: List<Email>,
    ttsState: TTSManager.PlaybackState,
    ttsProgress: Float,
    tiltScrollDelta: Float,
    voiceSessionState: GeminiLiveManager.SessionState,
    localRecognizerState: LocalCommandRecognizer.State,
    voiceComposeState: VoiceComposeManager.ComposeState,
    voiceDraft: VoiceDraft?,
    openPalmProgress: Float,
    onExpandToNotifications: () -> Unit,
    onCollapseFromNotifications: () -> Unit,
    onExpandToInbox: () -> Unit,
    onCollapseToHud: () -> Unit,
    onExpandToFocus: () -> Unit,
    onEmailSelected: (Email) -> Unit,
    onOpenFromNotification: (Email) -> Unit,
    onArchiveEmail: (Email) -> Unit,
    onSnoozeEmail: (Email) -> Unit,
    onDismissToast: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .fillMaxHeight()
                .align(Alignment.Center)
                .clip(RoundedCornerShape(20.dp))
                .background(XREmailColors.surfaceVariant.copy(alpha = 0.6f))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "XR Mail (no headset)",
                    color = XREmailColors.onSurface,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                VoicePrompt(
                    voiceState = voiceSessionState,
                    localState = localRecognizerState,
                    compact = true,
                )
            }
            PeripheralTierContent(
                tier = uiState.tier,
                uiState = uiState,
                prioritySortedEmails = prioritySortedEmails,
                ttsState = ttsState,
                ttsProgress = ttsProgress,
                tiltScrollDelta = tiltScrollDelta,
                voiceSessionState = voiceSessionState,
                localRecognizerState = localRecognizerState,
                voiceComposeState = voiceComposeState,
                voiceDraft = voiceDraft,
                openPalmProgress = openPalmProgress,
                onExpandToNotifications = onExpandToNotifications,
                onCollapseFromNotifications = onCollapseFromNotifications,
                onExpandToInbox = onExpandToInbox,
                onCollapseToHud = onCollapseToHud,
                onExpandToFocus = onExpandToFocus,
                onEmailSelected = onEmailSelected,
                onOpenFromNotification = onOpenFromNotification,
                onArchiveEmail = onArchiveEmail,
                onSnoozeEmail = onSnoozeEmail,
                onDismissToast = onDismissToast,
            )
        }
    }
}

package com.xremail.app.ui.spatial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
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
import com.xremail.app.ui.peripheral.TtsProgressBar
import com.xremail.app.ui.peripheral.TriagePanel
import com.xremail.app.ui.peripheral.VoiceComposeOverlay
import com.xremail.app.ui.peripheral.VoiceStatusIndicator
import com.xremail.app.ui.theme.XREmailColors
import com.xremail.app.util.XrLog
import com.xremail.app.viewmodel.EmailUiState
import com.xremail.app.viewmodel.InteractionTier
import com.xremail.app.viewmodel.VoiceDraft
import com.xremail.app.voice.GeminiLiveManager
import com.xremail.app.voice.TTSManager
import com.xremail.app.voice.VoiceComposeManager
import kotlinx.coroutines.flow.MutableStateFlow

private const val TAG = "TierRouter"
// Why we DON'T use FollowingSubspace + FollowTarget.ArDevice for the
// AMBIENT_HUD anymore (despite that being the documented "head-locked"
// primitive in androidx.xr.compose alpha12):
//
//   1. ArDeviceTarget.poseUpdates is a runtime-AR pose flow that ships
//      poses in METERS, but the SpatialPanel placed inside it is
//      offset in dp via SubspaceModifier.offset(...). The dp-meter
//      conversion factor is computed off the device's spatial density
//      at first composition and is NOT recomputed when the device
//      pose is far from world origin — so once the user has walked a
//      few meters away from where the session was first configured,
//      the offset blows up to "panel sometimes super far away".
//   2. TightFollowBehavior bypasses TrackedDimensions entirely (it
//      slams the entity to the full device pose every frame), so the
//      "isRotationXTracked = false" guards we set below were dead
//      code — head pitch/roll were tracked anyway, dragging the
//      panel out of view on every glance.
//   3. SoftFollowBehavior animates the entity over `durationMs` toward
//      the device pose. Any duration > MIN_SOFT_DURATION_MS (100 ms)
//      visibly lags the panel behind brisk head turns, which the user
//      sees as "the panel disappears when I turn".
//
// The cure that actually works on alpha12: render the AMBIENT_HUD as
// regular 2D Compose content inside the MAIN PANEL with corner
// alignment. In FULL_SPACE_MANAGED mode (see manifest) the OS
// auto-positions the main panel in front of the user's head, so a
// corner-aligned widget IS the head-locked HUD without going anywhere
// near the experimental FollowingSubspace pose tracker. This is the
// same approach TRIAGE already uses successfully.

/**
 * Depth-aware spatial layout using the three-plane system:
 *
 * - Background (MAIN PANEL overlay, top-end): Ambient HUD — notification banner
 * - Foreground (peripheral SpatialPanel): Notification Cards — gaze-expanded
 * - Content (MAIN PANEL, 2D): Triage — view-locked, stays with the user
 * - FOCUS: Curved spatial row of 3 panels the user can reposition
 *
 * Why TRIAGE / AMBIENT_HUD use the main panel instead of a SpatialPanel:
 *   Content rendered directly in setContent (outside Subspace) becomes the
 *   Android XR main panel — a regular 2D panel that stays in the user's
 *   field of view and can be repositioned by the system / user. SpatialPanels
 *   inside a Subspace are world-anchored and don't follow the head, which
 *   made TRIAGE "get stuck on the floor" when the user turned. Orbiters
 *   attach to panels for view-locked affordances, which matches the triage
 *   use case better than a bare world-anchored panel.
 */
@Composable
fun InteractionTierRouter(
    uiState: EmailUiState,
    prioritySortedEmails: List<Email>,
    ttsState: TTSManager.PlaybackState,
    ttsProgress: Float,
    tiltScrollDelta: Float,
    voiceSessionState: GeminiLiveManager.SessionState,
    voiceComposeState: VoiceComposeManager.ComposeState,
    voiceDraft: VoiceDraft?,
    handGestures: SecondaryHandGestures? = null,
    /**
     * True iff [LocalSession.current] has `Config.deviceTracking != DISABLED`.
     * Used to gate the `FollowingSubspace` branch — without device tracking,
     * `FollowTarget.ArDevice(session)` throws `IllegalStateException`.
     * The caller (MainActivity) configures the session synchronously before
     * the first composition and passes the result here.
     */
    deviceTrackingReady: Boolean = false,
    onKeyDown: (Int) -> Boolean = { false },
    onExpandToNotifications: () -> Unit,
    onCollapseFromNotifications: () -> Unit,
    onExpandToTriage: () -> Unit,
    onCollapseToHud: () -> Unit,
    onExpandToFocus: () -> Unit,
    onCollapseToTriage: () -> Unit,
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
    // Keyboard focus for emulator input — focusTarget() keeps keyboard events
    // routable without consuming taps (.focusable() eats pointer events at the
    // container level, which was killing clicks inside TriagePanel).
    val keyEventModifier = Modifier
        .onPreviewKeyEvent { event: KeyEvent ->
            if (event.type == KeyEventType.KeyDown) onKeyDown(event.key.nativeKeyCode) else false
        }
        .focusTarget()

    // Ambient HUD interaction tracking — the hand-gesture detector publishes
    // a "last interaction at" timestamp so the HUD can animate its dim/wake
    // state without polling. Hoisted here (above the main panel block) so
    // both the AMBIENT_HUD main-panel overlay and the Subspace fallback
    // can read the same flow without recomputing the remember key.
    val ambientLastInteraction = remember(handGestures) {
        handGestures?.lastInteractionMs ?: MutableStateFlow(0L)
    }
    val ambientBumpInteraction: () -> Unit = remember(handGestures) {
        { handGestures?.bumpInteraction() }
    }
    @Suppress("UNUSED_PARAMETER", "UNUSED_VARIABLE")
    val _suppressUnusedDeviceTracking = deviceTrackingReady

    // ---------------------------------------------------------------------------
    // MAIN PANEL (2D, view-locked) — primary interaction surfaces
    // ---------------------------------------------------------------------------
    Box(modifier = Modifier.fillMaxSize().then(keyEventModifier)) {
        when (uiState.tier) {
            InteractionTier.TRIAGE -> {
                Surface(
                    color = XREmailColors.surface,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    TriagePanel(
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
                // FOCUS renders a curved spatial row of 3 panels the user can
                // reposition via MovePolicy. This is genuinely spatial content,
                // so it gets its own Subspace wrapper below (main panel stays
                // empty/transparent behind it).
                Subspace {
                    SpatialEmailLayoutContent(
                        uiState = uiState,
                        onEmailSelected = onEmailSelected,
                        onCategorySelected = onCategorySelected,
                        onToggleAiSummary = onToggleAiSummary,
                        onReply = onReply,
                        onArchive = onArchive,
                        onSnooze = onSnooze,
                        onForward = onForward,
                        onSend = onSend,
                        onCancelCompose = onCancelCompose,
                        onCollapseToTriage = onCollapseToTriage,
                    )
                }
            }

            InteractionTier.AMBIENT_HUD -> {
                // The main panel itself is a transparent fillMaxSize Box;
                // the only opaque content is the corner-aligned AmbientHud
                // widget plus any active overlays. Because the main panel
                // is auto-positioned by the OS in FULL_SPACE_MANAGED mode,
                // the widget acts as a head-locked HUD without involving
                // the experimental FollowingSubspace tracker (see header
                // comment for why we abandoned that path).
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 32.dp, top = 32.dp)
                            .width(360.dp),
                    ) {
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
                                    "banner.expand (gaze.dwell or click)",
                                )
                                onExpandToNotifications()
                            },
                            onDismissToast = onDismissToast,
                            lastInteractionMs = ambientLastInteraction,
                            onBumpInteraction = ambientBumpInteraction,
                        )
                    }

                    if (uiState.isVoiceComposing) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 32.dp, bottom = 32.dp)
                                .width(380.dp),
                        ) {
                            VoiceComposeOverlay(
                                draft = voiceDraft ?: uiState.voiceDraft,
                                composeState = voiceComposeState,
                            )
                        }
                    }

                    if (handGestures != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 48.dp)
                                .width(280.dp),
                        ) {
                            com.xremail.app.ui.feedback.GestureFeedbackOverlay(
                                gestures = handGestures.gestures,
                            )
                        }
                    }
                }
            }

            InteractionTier.NOTIFICATION_CARDS -> {
                // Cards expand into world-anchored SpatialPanels below; the
                // main panel just hosts overlays (gesture pill, voice compose).
                Box(modifier = Modifier.fillMaxSize()) {
                    if (uiState.isVoiceComposing) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 32.dp, bottom = 32.dp)
                                .width(380.dp),
                        ) {
                            VoiceComposeOverlay(
                                draft = voiceDraft ?: uiState.voiceDraft,
                                composeState = voiceComposeState,
                            )
                        }
                    }

                    if (handGestures != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 48.dp)
                                .width(280.dp),
                        ) {
                            com.xremail.app.ui.feedback.GestureFeedbackOverlay(
                                gestures = handGestures.gestures,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // SPATIAL CONTENT (world-anchored Subspace)
    //
    // Only NOTIFICATION_CARDS spawns spatial panels here — those are the
    // gaze-expanded card stack and minimal status bar. They're explicitly
    // world-anchored because the user is stationary while reviewing, and
    // the previous head-locked attempt via FollowingSubspace had the
    // unit/pose drift bugs documented in the file header.
    //
    // TRIAGE's voice compose overlay also lives here so it can float
    // beside the main panel triage UI without being clipped by the
    // panel's bounds. AMBIENT_HUD voice compose lives in the main panel
    // overlay above (same view-locked surface as the HUD itself).
    // ---------------------------------------------------------------------------
    Subspace {
        when (uiState.tier) {
            InteractionTier.NOTIFICATION_CARDS -> {
                NotificationCardsPanels(
                    uiState = uiState,
                    tiltScrollDelta = tiltScrollDelta,
                    ttsState = ttsState,
                    ttsProgress = ttsProgress,
                    voiceSessionState = voiceSessionState,
                    onOpenFromNotification = onOpenFromNotification,
                    onArchiveEmail = onArchiveEmail,
                    onSnoozeEmail = onSnoozeEmail,
                    onCollapseFromNotifications = onCollapseFromNotifications,
                    onExpandToTriage = onExpandToTriage,
                )
            }

            InteractionTier.TRIAGE -> {
                if (uiState.isVoiceComposing) {
                    SpatialPanel(
                        modifier = SubspaceModifier
                            .width(380.dp)
                            .height(300.dp)
                            .offset(x = 240.dp, y = 0.dp, z = 10.dp),
                    ) {
                        VoiceComposeOverlay(
                            draft = voiceDraft ?: uiState.voiceDraft,
                            composeState = voiceComposeState,
                        )
                    }
                }
            }

            InteractionTier.AMBIENT_HUD,
            InteractionTier.FOCUS -> {
                // AMBIENT_HUD lives entirely in the main panel overlay above;
                // FOCUS renders its curved row in its own Subspace inside the
                // main panel branch above. No extra spatial content here.
            }
        }
    }
}

/**
 * The two SpatialPanels that make up the NOTIFICATION_CARDS tier — extracted
 * so the same Compose subtree can be hosted either inside a `FollowingSubspace`
 * (head-locked, default on Galaxy XR with device tracking) or inside the bare
 * `Subspace` fallback (emulator / no XR session). Keeping the offsets in one
 * place avoids drift between the two render paths and means design tweaks
 * land everywhere at once.
 *
 * NOTE on the [androidx.xr.compose.subspace.SubspaceComposable] receiver:
 * both `FollowingSubspace { ... }` and `Subspace { ... }` provide a Subspace
 * scope, so the SpatialPanel calls below are valid in either parent.
 */
@Composable
private fun NotificationCardsPanels(
    uiState: EmailUiState,
    tiltScrollDelta: Float,
    ttsState: TTSManager.PlaybackState,
    ttsProgress: Float,
    voiceSessionState: GeminiLiveManager.SessionState,
    onOpenFromNotification: (Email) -> Unit,
    onArchiveEmail: (Email) -> Unit,
    onSnoozeEmail: (Email) -> Unit,
    onCollapseFromNotifications: () -> Unit,
    onExpandToTriage: () -> Unit,
) {
    SpatialPanel(
        modifier = SubspaceModifier
            .width(340.dp)
            .height(520.dp)
            // Slightly to the right + below eye line so it doesn't occlude the
            // forward conversation field, ~30cm out (z=-300dp) when the
            // FollowingSubspace pose is the device. In the world-anchored
            // fallback the same offset places the panel at the original
            // spawn location plus ~30cm forward — also reasonable.
            .offset(x = 140.dp, y = (-80).dp, z = (-300).dp),
    ) {
        NotificationCardStack(
            emails = uiState.emails,
            highlightedId = uiState.highlightedNotificationId,
            tiltScrollDelta = tiltScrollDelta,
            onSelectEmail = onOpenFromNotification,
            onArchiveEmail = onArchiveEmail,
            onSnoozeEmail = onSnoozeEmail,
            onCollapseToHud = onCollapseFromNotifications,
            onExpandToTriage = onExpandToTriage,
        )
    }

    SpatialPanel(
        modifier = SubspaceModifier
            .width(200.dp)
            .height(60.dp)
            // Status bar floats above the card stack, far enough up so it
            // stays above whatever the user dwells on inside the cards.
            .offset(x = 220.dp, y = (-200).dp, z = (-300).dp),
    ) {
        MinimalStatusBar(
            ttsState = ttsState,
            ttsProgress = ttsProgress,
            voiceState = voiceSessionState,
        )
    }
}

/**
 * FOCUS tier content extracted so it can live inside a Subspace scope without
 * the outer Subspace wrapper competing with the main panel tree.
 */
@Composable
private fun SpatialEmailLayoutContent(
    uiState: EmailUiState,
    onEmailSelected: (Email) -> Unit,
    onCategorySelected: (EmailCategory?) -> Unit,
    onToggleAiSummary: () -> Unit,
    onReply: () -> Unit,
    onArchive: () -> Unit,
    onSnooze: () -> Unit,
    onForward: () -> Unit,
    onSend: () -> Unit,
    onCancelCompose: () -> Unit,
    onCollapseToTriage: () -> Unit,
) {
    // SpatialEmailLayout already uses SpatialCurvedRow — call it directly
    // but note it expects to be inside a Subspace (caller above provides it).
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
        onCollapse = onCollapseToTriage,
    )
}

/**
 * Tiny status bar shown during NOTIFICATION_CARDS tier so the user
 * can still see TTS progress and voice status in the corner.
 */
@Composable
private fun MinimalStatusBar(
    ttsState: TTSManager.PlaybackState,
    ttsProgress: Float,
    voiceState: GeminiLiveManager.SessionState,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(XREmailColors.surface.copy(alpha = 0.85f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TtsProgressBar(
            state = ttsState,
            progress = ttsProgress,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        VoiceStatusIndicator(voiceState = voiceState)
    }
}

package com.xremail.app.tracking

import android.util.Log
import com.xremail.app.viewmodel.EmailViewModel
import com.xremail.app.viewmodel.InteractionTier

private const val TAG = "GestureMapper"

/**
 * Context-aware gesture-to-action mapper. Routes the same physical gesture
 * to different ViewModel actions depending on the current InteractionTier.
 *
 * Tier escalation model (designed for walking/on-the-go use):
 *
 *   GLOBAL:
 *     OPEN_PALM_HOLD_COLLAPSE = back-up one tier (the only collapse gesture).
 *
 *   AMBIENT_HUD:
 *     PINCH_HOLD_EXPAND  -> NOTIFICATION_CARDS
 *     (a direct pinch on the visible banner does the same via OS click)
 *
 *   NOTIFICATION_CARDS:
 *     direct pinch on a card -> FOCUS for that email (OS click)
 *     PINCH_HOLD_EXPAND      -> INBOX
 *     SWIPE_LEFT / RIGHT     -> archive / snooze the highlighted card
 *
 *   INBOX:
 *     direct pinch on a row -> FOCUS for that email (OS click)
 *     SWIPE_LEFT / RIGHT    -> archive / snooze selected
 *     SWIPE_DOWN_DISMISS    -> back to AMBIENT_HUD
 *
 *   FOCUS:
 *     direct pinch in body -> standard interaction (OS click)
 *     SWIPE_DOWN_DISMISS   -> back to INBOX
 *
 * IMPORTANT — what's intentionally NOT mapped:
 *   - Secondary-hand PINCH_SELECT in NOTIFICATION_CARDS / INBOX / FOCUS.
 *   - Secondary-hand PINCH_HOLD_EXPAND in INBOX / FOCUS.
 *   These were removed because they fired silently on incidental hand
 *   movements and made tier transitions feel random. Forward escalation
 *   from any non-ambient tier requires either a direct pinch on a visible
 *   target (gaze + pinch) or an explicit swipe — both of which the user
 *   can see they're doing.
 */
class GestureToActionMapper(
    private val viewModel: EmailViewModel,
) {

    fun onGesture(gesture: SecondaryHandGestures.Gesture, tier: InteractionTier) {
        Log.d(TAG, "gesture=$gesture tier=$tier")
        // OPEN_PALM_HOLD_COLLAPSE is the universal "go back" — it's the
        // gestural inverse of PINCH_HOLD_EXPAND and behaves the same way
        // regardless of which tier we're in. Handling it here means we
        // don't have to repeat the same case in every per-tier when().
        if (gesture == SecondaryHandGestures.Gesture.OPEN_PALM_HOLD_COLLAPSE) {
            collapseOneTier(tier)
            return
        }
        when (tier) {
            InteractionTier.AMBIENT_HUD -> handleAmbientGesture(gesture)
            InteractionTier.NOTIFICATION_CARDS -> handleNotificationCardsGesture(gesture)
            InteractionTier.INBOX -> handleInboxGesture(gesture)
            InteractionTier.FOCUS -> handleFocusGesture(gesture)
        }
    }

    private fun collapseOneTier(tier: InteractionTier) {
        when (tier) {
            InteractionTier.FOCUS -> {
                Log.d(TAG, "  -> collapseToInbox() (open-palm)")
                viewModel.collapseToInbox()
            }
            InteractionTier.INBOX -> {
                Log.d(TAG, "  -> collapseToHud() (open-palm, skipping cards)")
                // From the user's perspective INBOX collapses straight back
                // to the ambient banner, not to the cards (which are a
                // peripheral preview, not a deeper state). Mirrors what
                // SWIPE_DOWN_DISMISS does in the INBOX handler.
                viewModel.collapseToHud()
            }
            InteractionTier.NOTIFICATION_CARDS -> {
                Log.d(TAG, "  -> collapseFromNotificationCards() (open-palm)")
                viewModel.collapseFromNotificationCards()
            }
            InteractionTier.AMBIENT_HUD -> {
                Log.d(TAG, "  open-palm in AMBIENT_HUD: nothing to collapse")
            }
        }
    }

    private fun handleAmbientGesture(gesture: SecondaryHandGestures.Gesture) {
        // ONLY the deliberate long-pinch (PINCH_HOLD_EXPAND, ≥600ms held)
        // expands. Short PINCH_SELECT taps are ignored here because
        // hand-tracking flicker — a brief moment where thumb and index
        // happen to be within the pinch threshold — was firing
        // PINCH_SELECT and instantly expanding the HUD without the user
        // having intended any gesture. The user still has the on-panel
        // "Pinch to expand" tap target if they want quick expansion via
        // direct interaction with the visible banner.
        when (gesture) {
            SecondaryHandGestures.Gesture.PINCH_HOLD_EXPAND -> {
                Log.d(TAG, "  -> expandToNotificationCards() (PINCH_HOLD_EXPAND)")
                viewModel.expandToNotificationCards()
            }
            else -> { /* no-op in ambient — every other gesture suppressed */ }
        }
    }

    private fun handleNotificationCardsGesture(gesture: SecondaryHandGestures.Gesture) {
        when (gesture) {
            // SECONDARY-HAND PINCH-SELECT IS DELIBERATELY NO-OP IN THIS TIER.
            //
            // Two reasons we used to handle it and now don't:
            //
            //   1. A pinch on a VISIBLE card already opens that card via the
            //      OS gaze+pinch click pipeline (Modifier.clickable on
            //      NotificationCardContent → openFromNotification → FOCUS).
            //      Adding a redundant secondary-hand path means the same
            //      pinch fires both, racing two tier transitions on the
            //      same recomposition.
            //
            //   2. The fallback "no highlighted card → expand to INBOX"
            //      branch was the user-reported "expanding and collapsing
            //      at random times": any incidental secondary-hand pinch
            //      (rummaging in a pocket, gesturing while talking) would
            //      jump tiers without the user touching the UI.
            //
            // Tier escalation from NOTIFICATION_CARDS now requires either
            // an explicit pinch on a card (OS click) or the
            // PINCH_HOLD_EXPAND deliberate hold (still mapped below).
            SecondaryHandGestures.Gesture.PINCH_SELECT -> {
                Log.v(TAG, "  PINCH_SELECT in NOTIFICATION_CARDS: ignored (use direct pinch on card)")
            }
            SecondaryHandGestures.Gesture.PINCH_HOLD_EXPAND -> {
                // Deliberate hold still takes you forward to INBOX so
                // there's a hands-free escalation when no card is
                // gaze-targeted.
                Log.d(TAG, "  -> expandToInbox() (PINCH_HOLD_EXPAND)")
                viewModel.expandToInbox()
            }
            SecondaryHandGestures.Gesture.SWIPE_LEFT_ARCHIVE -> {
                val highlighted = viewModel.uiState.value.highlightedNotificationId
                val email = viewModel.uiState.value.emails.find { it.id == highlighted }
                if (email != null) {
                    Log.d(TAG, "  -> archiveEmail(${email.id})")
                    viewModel.archiveEmail(email)
                }
            }
            SecondaryHandGestures.Gesture.SWIPE_RIGHT_SNOOZE -> {
                val highlighted = viewModel.uiState.value.highlightedNotificationId
                val email = viewModel.uiState.value.emails.find { it.id == highlighted }
                if (email != null) {
                    Log.d(TAG, "  -> snoozeEmail(${email.id})")
                    viewModel.snoozeEmail(email)
                }
            }
            SecondaryHandGestures.Gesture.SWIPE_DOWN_DISMISS -> {
                Log.d(TAG, "  -> collapseFromNotificationCards()")
                viewModel.collapseFromNotificationCards()
            }
            SecondaryHandGestures.Gesture.PINCH_HOLD_EXPAND -> {
                Log.d(TAG, "  -> expandToInbox()")
                viewModel.expandToInbox()
            }
            SecondaryHandGestures.Gesture.SWIPE_UP_STAR -> {
                val highlighted = viewModel.uiState.value.highlightedNotificationId
                val email = viewModel.uiState.value.emails.find { it.id == highlighted }
                if (email != null) {
                    Log.d(TAG, "  -> toggleStar(${email.id})")
                    viewModel.toggleStar(email)
                }
            }
            // OPEN_PALM_HOLD_COLLAPSE handled centrally in [onGesture]; this
            // case exists only to keep the when exhaustive after the new
            // enum value was added.
            SecondaryHandGestures.Gesture.OPEN_PALM_HOLD_COLLAPSE -> Unit
        }
    }

    private fun handleInboxGesture(gesture: SecondaryHandGestures.Gesture) {
        when (gesture) {
            SecondaryHandGestures.Gesture.SWIPE_LEFT_ARCHIVE -> {
                Log.d(TAG, "  -> archiveSelected()")
                viewModel.archiveSelected()
            }
            SecondaryHandGestures.Gesture.SWIPE_RIGHT_SNOOZE -> {
                Log.d(TAG, "  -> snoozeSelected()")
                viewModel.snoozeSelected()
            }
            // Re-selecting the already-selected email did nothing useful and
            // every accidental secondary-hand pinch in this tier was firing
            // a recompose. Direct pinch-on-row in the inbox list is what
            // selects an email.
            SecondaryHandGestures.Gesture.PINCH_SELECT -> {
                Log.v(TAG, "  PINCH_SELECT in INBOX: ignored (use direct pinch on row)")
            }
            // PINCH_HOLD_EXPAND used to auto-jump to FOCUS, which made the
            // inbox feel "haunted": any deliberate secondary-hand long-pinch
            // (or even a slow accidental pinch-and-release) would silently
            // throw the user into the full reader. Forward escalation from
            // INBOX → FOCUS now happens via direct pinch on a row, which is
            // intentional and visible.
            SecondaryHandGestures.Gesture.PINCH_HOLD_EXPAND -> {
                Log.v(TAG, "  PINCH_HOLD_EXPAND in INBOX: ignored (pinch a row to focus it)")
            }
            SecondaryHandGestures.Gesture.SWIPE_DOWN_DISMISS -> {
                Log.d(TAG, "  -> collapseToHud()")
                viewModel.collapseToHud()
            }
            SecondaryHandGestures.Gesture.SWIPE_UP_STAR -> {
                viewModel.uiState.value.selectedEmail?.let {
                    Log.d(TAG, "  -> toggleStar(${it.id})")
                    viewModel.toggleStar(it)
                }
            }
            // OPEN_PALM_HOLD_COLLAPSE handled centrally in [onGesture].
            SecondaryHandGestures.Gesture.OPEN_PALM_HOLD_COLLAPSE -> Unit
        }
    }

    private fun handleFocusGesture(gesture: SecondaryHandGestures.Gesture) {
        when (gesture) {
            SecondaryHandGestures.Gesture.SWIPE_DOWN_DISMISS -> {
                Log.d(TAG, "  -> collapseToInbox()")
                viewModel.collapseToInbox()
            }
            // PINCH_HOLD_EXPAND used to "escape" out of FOCUS by collapsing
            // to INBOX, which inverted the verb (`HOLD_EXPAND` doing a
            // collapse) and was the most-reported "random collapse" — a
            // small drift in the secondary hand during reading would yank
            // the user back to the inbox list. Open-palm-hold is the one
            // and only collapse gesture now; pinch never collapses.
            SecondaryHandGestures.Gesture.PINCH_HOLD_EXPAND -> {
                Log.v(TAG, "  PINCH_HOLD_EXPAND in FOCUS: ignored (open-palm-hold collapses)")
            }
            SecondaryHandGestures.Gesture.PINCH_SELECT -> {
                /* standard select — handled by existing panel tap */
            }
            else -> { /* no-op in focus */ }
        }
    }
}

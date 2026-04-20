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
 *     CLOSED_FIST_HOLD_COLLAPSE = back-up one tier (the only collapse gesture).
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
        // CLOSED_FIST_HOLD_COLLAPSE is the universal "go back" — it's the
        // gestural inverse of PINCH_HOLD_EXPAND and behaves the same way
        // regardless of which tier we're in. Handling it here means we
        // don't have to repeat the same case in every per-tier when().
        if (gesture == SecondaryHandGestures.Gesture.CLOSED_FIST_HOLD_COLLAPSE) {
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
                Log.d(TAG, "  -> collapseToInbox() (closed-fist)")
                viewModel.collapseToInbox()
            }
            InteractionTier.INBOX -> {
                Log.d(TAG, "  -> collapseToHud() (closed-fist, skipping cards)")
                // From the user's perspective INBOX collapses straight back
                // to the ambient banner, not to the cards (which are a
                // peripheral preview, not a deeper state). Mirrors what
                // SWIPE_DOWN_DISMISS does in the INBOX handler.
                viewModel.collapseToHud()
            }
            InteractionTier.NOTIFICATION_CARDS -> {
                Log.d(TAG, "  -> collapseFromNotificationCards() (closed-fist)")
                viewModel.collapseFromNotificationCards()
            }
            InteractionTier.AMBIENT_HUD -> {
                Log.d(TAG, "  closed-fist in AMBIENT_HUD: nothing to collapse")
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
            // Tier escalation from NOTIFICATION_CARDS:
            //   - Preferred path: OS gaze+pinch click on a card → opens
            //     that specific card. Lives on the NotificationCard
            //     Modifier.clickable.
            //   - Fallback (this branch): if there IS a gaze-highlighted
            //     card AND the OS click pipeline didn't fire (e.g. the
            //     SwipeToDismissBox stole the pointer event, hand
            //     tracking jitter put the click off the card surface,
            //     etc.), open the highlighted card on a secondary-hand
            //     PINCH_SELECT. The user explicitly reported "look at
            //     a notification, pinch, nothing happens" — this is
            //     the safety net so a near-miss still does the right
            //     thing instead of feeling completely unresponsive.
            //   - When NO card is highlighted (gaze isn't on the stack),
            //     a secondary-hand pinch is suppressed — that branch
            //     was the original "expanding at random times" bug.
            SecondaryHandGestures.Gesture.PINCH_SELECT -> {
                val highlighted = viewModel.uiState.value.highlightedNotificationId
                val email = viewModel.uiState.value.emails.find { it.id == highlighted }
                if (email != null) {
                    Log.d(TAG, "  -> openFromNotification(${email.id}) (PINCH_SELECT fallback for highlighted)")
                    viewModel.openFromNotification(email)
                } else {
                    Log.v(TAG, "  PINCH_SELECT in NOTIFICATION_CARDS: ignored (no highlighted card)")
                }
            }
            SecondaryHandGestures.Gesture.PINCH_HOLD_EXPAND -> {
                // Deliberate hold still takes you forward to INBOX so
                // there's a hands-free escalation when no card is
                // gaze-targeted.
                Log.d(TAG, "  -> expandToInbox() (PINCH_HOLD_EXPAND)")
                viewModel.expandToInbox()
            }
            // AIR-SWIPE ARCHIVE/SNOOZE/STAR DISABLED.
            //
            // Hand-tracking can't reliably distinguish "user swung their
            // hand through the air as a gesture" from "user was just
            // walking / gesturing while talking / shifting posture".
            // The thresholds in SecondaryHandGestures were tightened
            // considerably, but for DESTRUCTIVE actions (archive, snooze,
            // star) no threshold is safe enough — one false positive means
            // an email silently disappeared. The user directly reported
            // this as "things are getting randomly archived and snoozed
            // without intended actions".
            //
            // Archive/snooze/star are reachable via voice ("archive this",
            // "snooze for an hour", "star this") and via the inbox panel's
            // explicit buttons. Both are intentional by construction.
            SecondaryHandGestures.Gesture.SWIPE_LEFT_ARCHIVE -> {
                Log.v(TAG, "  SWIPE_LEFT_ARCHIVE in NOTIFICATION_CARDS: ignored (use voice or tap)")
            }
            SecondaryHandGestures.Gesture.SWIPE_RIGHT_SNOOZE -> {
                Log.v(TAG, "  SWIPE_RIGHT_SNOOZE in NOTIFICATION_CARDS: ignored (use voice or tap)")
            }
            SecondaryHandGestures.Gesture.SWIPE_DOWN_DISMISS -> {
                // Tier dismissal is non-destructive (user can always
                // re-expand) so we KEEP the swipe-down path — but
                // CLOSED_FIST_HOLD_COLLAPSE is the preferred collapse
                // gesture. Air-swipe-down is a legacy fallback and
                // SecondaryHandGestures thresholds are now tight enough
                // that it takes a deliberate downward flick to fire.
                Log.d(TAG, "  -> collapseFromNotificationCards()")
                viewModel.collapseFromNotificationCards()
            }
            SecondaryHandGestures.Gesture.SWIPE_UP_STAR -> {
                Log.v(TAG, "  SWIPE_UP_STAR in NOTIFICATION_CARDS: ignored (use voice or tap)")
            }
            // CLOSED_FIST_HOLD_COLLAPSE handled centrally in [onGesture];
            // this case exists only to keep the when exhaustive.
            SecondaryHandGestures.Gesture.CLOSED_FIST_HOLD_COLLAPSE -> Unit
        }
    }

    private fun handleInboxGesture(gesture: SecondaryHandGestures.Gesture) {
        when (gesture) {
            // Air-swipe archive/snooze in INBOX is disabled for the same
            // reason it's disabled in NOTIFICATION_CARDS — one false-
            // positive swipe silently destroys a message. Voice commands
            // ("archive this", "snooze") and the inbox buttons remain.
            SecondaryHandGestures.Gesture.SWIPE_LEFT_ARCHIVE -> {
                Log.v(TAG, "  SWIPE_LEFT_ARCHIVE in INBOX: ignored (use voice or tap)")
            }
            SecondaryHandGestures.Gesture.SWIPE_RIGHT_SNOOZE -> {
                Log.v(TAG, "  SWIPE_RIGHT_SNOOZE in INBOX: ignored (use voice or tap)")
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
                Log.v(TAG, "  SWIPE_UP_STAR in INBOX: ignored (use voice or tap)")
            }
            // CLOSED_FIST_HOLD_COLLAPSE handled centrally in [onGesture].
            SecondaryHandGestures.Gesture.CLOSED_FIST_HOLD_COLLAPSE -> Unit
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
            // the user back to the inbox list. Closed-fist-hold is the
            // one and only collapse gesture now; pinch never collapses.
            SecondaryHandGestures.Gesture.PINCH_HOLD_EXPAND -> {
                Log.v(TAG, "  PINCH_HOLD_EXPAND in FOCUS: ignored (closed-fist-hold collapses)")
            }
            SecondaryHandGestures.Gesture.PINCH_SELECT -> {
                /* standard select — handled by existing panel tap */
            }
            else -> { /* no-op in focus */ }
        }
    }
}

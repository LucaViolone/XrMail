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
 *   AMBIENT_HUD: pinch = expand to notification cards
 *   NOTIFICATION_CARDS: pinch = open highlighted email in the inbox panel,
 *                       swipe-left = archive from card, swipe-right = snooze,
 *                       swipe-down = collapse back to HUD
 *   INBOX: swipe-left = archive, swipe-right = snooze, pinch = select + TTS,
 *           pinch-hold = expand to focus, swipe-down = collapse to HUD
 *   FOCUS: swipe-down = collapse to inbox, pinch = standard select
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
            SecondaryHandGestures.Gesture.PINCH_SELECT -> {
                val highlighted = viewModel.uiState.value.highlightedNotificationId
                val email = viewModel.uiState.value.emails.find { it.id == highlighted }
                if (email != null) {
                    Log.d(TAG, "  -> openFromNotification(${email.id})")
                    viewModel.openFromNotification(email)
                } else {
                    Log.d(TAG, "  -> expandToInbox()")
                    viewModel.expandToInbox()
                }
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
            SecondaryHandGestures.Gesture.PINCH_SELECT -> {
                viewModel.uiState.value.selectedEmail?.let {
                    Log.d(TAG, "  -> selectEmail(${it.id})")
                    viewModel.selectEmail(it)
                }
            }
            SecondaryHandGestures.Gesture.PINCH_HOLD_EXPAND -> {
                Log.d(TAG, "  -> expandToFocus()")
                viewModel.expandToFocus()
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
            SecondaryHandGestures.Gesture.PINCH_HOLD_EXPAND -> {
                Log.d(TAG, "  -> collapseToInbox() (pinch-hold escape)")
                viewModel.collapseToInbox()
            }
            SecondaryHandGestures.Gesture.PINCH_SELECT -> {
                /* standard select — handled by existing panel tap */
            }
            else -> { /* no-op in focus */ }
        }
    }
}

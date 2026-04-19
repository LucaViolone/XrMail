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
 *   NOTIFICATION_CARDS: pinch = open highlighted email in triage,
 *                       swipe-left = archive from card, swipe-right = snooze,
 *                       swipe-down = collapse back to HUD
 *   TRIAGE: swipe-left = archive, swipe-right = snooze, pinch = select + TTS,
 *           pinch-hold = expand to focus, swipe-down = collapse to HUD
 *   FOCUS: swipe-down = collapse to triage, pinch = standard select
 */
class GestureToActionMapper(
    private val viewModel: EmailViewModel,
) {

    fun onGesture(gesture: SecondaryHandGestures.Gesture, tier: InteractionTier) {
        Log.d(TAG, "gesture=$gesture tier=$tier")
        when (tier) {
            InteractionTier.AMBIENT_HUD -> handleAmbientGesture(gesture)
            InteractionTier.NOTIFICATION_CARDS -> handleNotificationCardsGesture(gesture)
            InteractionTier.TRIAGE -> handleTriageGesture(gesture)
            InteractionTier.FOCUS -> handleFocusGesture(gesture)
            InteractionTier.CALENDAR -> handleCalendarGesture(gesture)
        }
    }

    private fun handleAmbientGesture(gesture: SecondaryHandGestures.Gesture) {
        when (gesture) {
            SecondaryHandGestures.Gesture.PINCH_SELECT -> {
                Log.d(TAG, "  -> expandToNotificationCards()")
                viewModel.expandToNotificationCards()
            }
            else -> { /* no-op in ambient — gaze handles expansion */ }
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
                    Log.d(TAG, "  -> expandToTriage()")
                    viewModel.expandToTriage()
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
                Log.d(TAG, "  -> expandToTriage()")
                viewModel.expandToTriage()
            }
            SecondaryHandGestures.Gesture.SWIPE_UP_STAR -> {
                val highlighted = viewModel.uiState.value.highlightedNotificationId
                val email = viewModel.uiState.value.emails.find { it.id == highlighted }
                if (email != null) {
                    Log.d(TAG, "  -> toggleStar(${email.id})")
                    viewModel.toggleStar(email)
                }
            }
        }
    }

    private fun handleTriageGesture(gesture: SecondaryHandGestures.Gesture) {
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
        }
    }

    private fun handleFocusGesture(gesture: SecondaryHandGestures.Gesture) {
        when (gesture) {
            SecondaryHandGestures.Gesture.SWIPE_DOWN_DISMISS -> {
                Log.d(TAG, "  -> collapseToTriage()")
                viewModel.collapseToTriage()
            }
            SecondaryHandGestures.Gesture.PINCH_HOLD_EXPAND -> {
                Log.d(TAG, "  -> collapseToTriage() (pinch-hold escape)")
                viewModel.collapseToTriage()
            }
            SecondaryHandGestures.Gesture.PINCH_SELECT -> {
                /* standard select — handled by existing panel tap */
            }
            else -> { /* no-op in focus */ }
        }
    }

    private fun handleCalendarGesture(gesture: SecondaryHandGestures.Gesture) {
        when (gesture) {
            SecondaryHandGestures.Gesture.SWIPE_DOWN_DISMISS,
            SecondaryHandGestures.Gesture.PINCH_HOLD_EXPAND -> {
                Log.d(TAG, "  -> hideCalendar()")
                viewModel.hideCalendar()
            }
            else -> { /* no-op in calendar */ }
        }
    }
}

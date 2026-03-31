package com.xremail.app.tracking

import com.xremail.app.viewmodel.EmailViewModel
import com.xremail.app.viewmodel.InteractionTier

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
        when (tier) {
            InteractionTier.AMBIENT_HUD -> handleAmbientGesture(gesture)
            InteractionTier.NOTIFICATION_CARDS -> handleNotificationCardsGesture(gesture)
            InteractionTier.TRIAGE -> handleTriageGesture(gesture)
            InteractionTier.FOCUS -> handleFocusGesture(gesture)
        }
    }

    private fun handleAmbientGesture(gesture: SecondaryHandGestures.Gesture) {
        when (gesture) {
            SecondaryHandGestures.Gesture.PINCH_SELECT -> viewModel.expandToNotificationCards()
            else -> { /* no-op in ambient — gaze handles expansion */ }
        }
    }

    private fun handleNotificationCardsGesture(gesture: SecondaryHandGestures.Gesture) {
        when (gesture) {
            SecondaryHandGestures.Gesture.PINCH_SELECT -> {
                val highlighted = viewModel.uiState.value.highlightedNotificationId
                val email = viewModel.uiState.value.emails.find { it.id == highlighted }
                if (email != null) {
                    viewModel.openFromNotification(email)
                } else {
                    viewModel.expandToTriage()
                }
            }
            SecondaryHandGestures.Gesture.SWIPE_LEFT_ARCHIVE -> {
                val highlighted = viewModel.uiState.value.highlightedNotificationId
                val email = viewModel.uiState.value.emails.find { it.id == highlighted }
                if (email != null) viewModel.archiveEmail(email)
            }
            SecondaryHandGestures.Gesture.SWIPE_RIGHT_SNOOZE -> {
                val highlighted = viewModel.uiState.value.highlightedNotificationId
                val email = viewModel.uiState.value.emails.find { it.id == highlighted }
                if (email != null) viewModel.snoozeEmail(email)
            }
            SecondaryHandGestures.Gesture.SWIPE_DOWN_DISMISS -> {
                viewModel.collapseFromNotificationCards()
            }
            SecondaryHandGestures.Gesture.PINCH_HOLD_EXPAND -> {
                viewModel.expandToTriage()
            }
            SecondaryHandGestures.Gesture.SWIPE_UP_STAR -> {
                val highlighted = viewModel.uiState.value.highlightedNotificationId
                val email = viewModel.uiState.value.emails.find { it.id == highlighted }
                if (email != null) viewModel.toggleStar(email)
            }
        }
    }

    private fun handleTriageGesture(gesture: SecondaryHandGestures.Gesture) {
        when (gesture) {
            SecondaryHandGestures.Gesture.SWIPE_LEFT_ARCHIVE -> viewModel.archiveSelected()
            SecondaryHandGestures.Gesture.SWIPE_RIGHT_SNOOZE -> viewModel.snoozeSelected()
            SecondaryHandGestures.Gesture.PINCH_SELECT -> {
                viewModel.uiState.value.selectedEmail?.let { viewModel.selectEmail(it) }
            }
            SecondaryHandGestures.Gesture.PINCH_HOLD_EXPAND -> viewModel.expandToFocus()
            SecondaryHandGestures.Gesture.SWIPE_DOWN_DISMISS -> viewModel.collapseToHud()
            SecondaryHandGestures.Gesture.SWIPE_UP_STAR -> {
                viewModel.uiState.value.selectedEmail?.let { viewModel.toggleStar(it) }
            }
        }
    }

    private fun handleFocusGesture(gesture: SecondaryHandGestures.Gesture) {
        when (gesture) {
            SecondaryHandGestures.Gesture.SWIPE_DOWN_DISMISS -> viewModel.collapseToTriage()
            SecondaryHandGestures.Gesture.PINCH_SELECT -> {
                /* standard select — handled by existing panel tap */
            }
            else -> { /* no-op in focus */ }
        }
    }
}

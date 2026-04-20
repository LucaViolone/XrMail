package com.xremail.app.tracking

import android.util.Log
import android.view.KeyEvent
import com.xremail.app.viewmodel.EmailViewModel
import com.xremail.app.viewmodel.InteractionTier

/**
 * Maps physical keyboard keys to gesture actions for emulator testing.
 * Allows full interaction without XR hand tracking.
 */
class KeyboardGestureDispatcher(
    private val viewModel: EmailViewModel,
    private val handGestures: SecondaryHandGestures,
) {
    companion object {
        private const val TAG = "KeyboardGestures"

        val KEY_MAPPINGS: List<Mapping> = listOf(
            Mapping(KeyEvent.KEYCODE_1, "1", "Expand to Notifications"),
            Mapping(KeyEvent.KEYCODE_2, "2", "Expand to Inbox"),
            Mapping(KeyEvent.KEYCODE_3, "3", "Expand to Focus"),
            Mapping(KeyEvent.KEYCODE_0, "0", "Collapse to HUD"),
            Mapping(KeyEvent.KEYCODE_DEL, "Bksp", "Collapse one tier back"),
            Mapping(KeyEvent.KEYCODE_DPAD_LEFT, "Left", "Swipe Left (Archive)"),
            Mapping(KeyEvent.KEYCODE_DPAD_RIGHT, "Right", "Swipe Right (Snooze)"),
            Mapping(KeyEvent.KEYCODE_DPAD_UP, "Up", "Swipe Up (Star)"),
            Mapping(KeyEvent.KEYCODE_DPAD_DOWN, "Down", "Swipe Down (Dismiss)"),
            Mapping(KeyEvent.KEYCODE_SPACE, "Space", "Pinch Select"),
            Mapping(KeyEvent.KEYCODE_ENTER, "Enter", "Pinch Hold Expand"),
            Mapping(KeyEvent.KEYCODE_P, "P", "Open Palm Hold (collapse)"),
            Mapping(KeyEvent.KEYCODE_N, "N", "Select next email"),
            Mapping(KeyEvent.KEYCODE_H, "H", "Toggle help overlay"),
        )
    }

    data class Mapping(val keyCode: Int, val keyLabel: String, val description: String)

    fun onKeyDown(keyCode: Int): Boolean {
        val tier = viewModel.uiState.value.tier

        val consumed = when (keyCode) {
            KeyEvent.KEYCODE_1 -> {
                viewModel.expandToNotificationCards(); true
            }
            KeyEvent.KEYCODE_2 -> {
                viewModel.expandToInbox(); true
            }
            KeyEvent.KEYCODE_3 -> {
                viewModel.expandToFocus(); true
            }
            KeyEvent.KEYCODE_0 -> {
                viewModel.collapseToHud(); true
            }
            KeyEvent.KEYCODE_DEL -> {
                when (tier) {
                    InteractionTier.FOCUS -> viewModel.collapseToInbox()
                    InteractionTier.INBOX -> viewModel.collapseToNotificationCards()
                    InteractionTier.NOTIFICATION_CARDS -> viewModel.collapseFromNotificationCards()
                    InteractionTier.AMBIENT_HUD -> {}
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                handGestures.simulateGesture(SecondaryHandGestures.Gesture.SWIPE_LEFT_ARCHIVE); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                handGestures.simulateGesture(SecondaryHandGestures.Gesture.SWIPE_RIGHT_SNOOZE); true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                handGestures.simulateGesture(SecondaryHandGestures.Gesture.SWIPE_UP_STAR); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                handGestures.simulateGesture(SecondaryHandGestures.Gesture.SWIPE_DOWN_DISMISS); true
            }
            KeyEvent.KEYCODE_SPACE -> {
                handGestures.simulateGesture(SecondaryHandGestures.Gesture.PINCH_SELECT); true
            }
            KeyEvent.KEYCODE_ENTER -> {
                handGestures.simulateGesture(SecondaryHandGestures.Gesture.REVERSE_PINCH_EXPAND); true
            }
            KeyEvent.KEYCODE_P -> {
                handGestures.simulateGesture(SecondaryHandGestures.Gesture.CLOSED_FIST_HOLD_COLLAPSE); true
            }
            KeyEvent.KEYCODE_N -> {
                selectNextEmail(); true
            }
            KeyEvent.KEYCODE_H -> {
                viewModel.toggleEmulatorHelp(); true
            }
            else -> false
        }

        if (consumed) {
            Log.d(TAG, "Key $keyCode -> action dispatched (tier=$tier)")
        }

        return consumed
    }

    private fun selectNextEmail() {
        val state = viewModel.uiState.value
        val emails = state.emails
        if (emails.isEmpty()) return
        val currentIdx = emails.indexOfFirst { it.id == state.selectedEmail?.id }
        val next = emails.getOrNull(currentIdx + 1) ?: emails.first()
        viewModel.selectEmail(next)
    }
}

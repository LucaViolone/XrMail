package com.xremail.app.tracking

import android.util.Log
import com.xremail.app.viewmodel.EmailViewModel
import com.xremail.app.viewmodel.InteractionTier

private const val TAG = "GestureMapper"

/**
 * Context-aware gesture-to-action mapper. Routes the same physical gesture
 * to different ViewModel actions depending on the current InteractionTier.
 *
 * Tier escalation model (post-2026-04-19 rewrite):
 *
 *   GLOBAL (fires the same way regardless of tier):
 *     REVERSE_PINCH_EXPAND         -> expandOneTier(tier) (open-fingered)
 *     CLOSED_FIST_HOLD_COLLAPSE    -> collapseOneTier(tier) (closed fist)
 *
 *   PINCH_SELECT is a no-op in every tier. The OS gaze+pinch pipeline
 *   already delivers a Compose click on whatever the user is looking at,
 *   which is the real "select/open" path. The custom PINCH_SELECT event
 *   is retained only for the confirmation pill — it must NEVER change
 *   tier, otherwise any incidental pinch fires a phantom navigation.
 *
 *   NOTIFICATION_CARDS:
 *     direct pinch on a card -> FOCUS for that email (OS click)
 *   INBOX:
 *     direct pinch on a row  -> FOCUS for that email (OS click)
 *     SWIPE_DOWN_DISMISS     -> back to AMBIENT_HUD
 *   FOCUS:
 *     SWIPE_DOWN_DISMISS     -> back to INBOX
 *     direct pinch in body   -> standard interaction (OS click)
 *
 * Air-swipe archive/snooze/star are intentionally unmapped: a single
 * false positive silently destroys a message.
 */
class GestureToActionMapper(
    private val viewModel: EmailViewModel,
) {

    fun onGesture(gesture: SecondaryHandGestures.Gesture, tier: InteractionTier) {
        Log.d(TAG, "gesture=$gesture tier=$tier")
        when (gesture) {
            SecondaryHandGestures.Gesture.CLOSED_FIST_HOLD_COLLAPSE -> {
                collapseOneTier(tier)
                return
            }
            SecondaryHandGestures.Gesture.REVERSE_PINCH_EXPAND -> {
                expandOneTier(tier)
                return
            }
            else -> Unit
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
                // INBOX collapses straight back to the ambient banner,
                // not to the cards (which are a peripheral preview, not
                // a deeper state). Mirrors SWIPE_DOWN_DISMISS.
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

    private fun expandOneTier(tier: InteractionTier) {
        when (tier) {
            InteractionTier.AMBIENT_HUD -> {
                Log.d(TAG, "  -> expandToNotificationCards() (reverse-pinch)")
                viewModel.expandToNotificationCards()
            }
            InteractionTier.NOTIFICATION_CARDS -> {
                Log.d(TAG, "  -> expandToInbox() (reverse-pinch)")
                viewModel.expandToInbox()
            }
            InteractionTier.INBOX -> {
                // INBOX → FOCUS needs a SPECIFIC email. The reverse-pinch
                // has no gaze target of its own, so we fall back to
                // opening the currently-selected email, if any. This is
                // the natural match for "I was reading this row, now
                // expand it to full focus".
                val selected = viewModel.uiState.value.selectedEmail
                if (selected != null) {
                    Log.d(TAG, "  -> openFromNotification(${selected.id}) (reverse-pinch, selected row)")
                    viewModel.openFromNotification(selected)
                } else {
                    Log.v(TAG, "  reverse-pinch in INBOX: no selected email, ignored")
                }
            }
            InteractionTier.FOCUS -> {
                Log.v(TAG, "  reverse-pinch in FOCUS: already at top tier, ignored")
            }
        }
    }

    private fun handleAmbientGesture(gesture: SecondaryHandGestures.Gesture) {
        // All expansion from AMBIENT_HUD now flows through expandOneTier()
        // via REVERSE_PINCH_EXPAND or via the banner's OS click path.
        // PINCH_SELECT is deliberately a no-op so incidental pinches
        // don't expand the HUD.
        when (gesture) {
            SecondaryHandGestures.Gesture.PINCH_SELECT -> {
                Log.v(TAG, "  PINCH_SELECT in AMBIENT_HUD: no-op (OS gaze+pinch handles clicks)")
            }
            else -> Unit
        }
    }

    private fun handleNotificationCardsGesture(gesture: SecondaryHandGestures.Gesture) {
        when (gesture) {
            // PINCH_SELECT is a no-op in this tier. The NotificationCard's
            // own Modifier.clickable carries the open-on-pinch path via
            // OS gaze+pinch. Firing a second custom handler here used to
            // race two tier transitions on the same recomposition and was
            // the root cause of "expanding and collapsing at random times".
            SecondaryHandGestures.Gesture.PINCH_SELECT -> {
                Log.v(TAG, "  PINCH_SELECT in NOTIFICATION_CARDS: no-op (card .clickable handles it)")
            }
            // Air-swipe archive/snooze/star disabled — hand tracking can't
            // reliably separate a deliberate swipe from walking/gesturing,
            // and one false positive silently destroys a message.
            SecondaryHandGestures.Gesture.SWIPE_LEFT_ARCHIVE -> {
                Log.v(TAG, "  SWIPE_LEFT_ARCHIVE in NOTIFICATION_CARDS: ignored (use voice or tap)")
            }
            SecondaryHandGestures.Gesture.SWIPE_RIGHT_SNOOZE -> {
                Log.v(TAG, "  SWIPE_RIGHT_SNOOZE in NOTIFICATION_CARDS: ignored (use voice or tap)")
            }
            SecondaryHandGestures.Gesture.SWIPE_DOWN_DISMISS -> {
                // Non-destructive tier dismiss — kept as a legacy fallback
                // beside the primary closed-fist collapse.
                Log.d(TAG, "  -> collapseFromNotificationCards() (swipe-down)")
                viewModel.collapseFromNotificationCards()
            }
            SecondaryHandGestures.Gesture.SWIPE_UP_STAR -> {
                Log.v(TAG, "  SWIPE_UP_STAR in NOTIFICATION_CARDS: ignored (use voice or tap)")
            }
            // Global gestures handled in [onGesture] — exhaustive-when no-ops:
            SecondaryHandGestures.Gesture.REVERSE_PINCH_EXPAND,
            SecondaryHandGestures.Gesture.CLOSED_FIST_HOLD_COLLAPSE -> Unit
        }
    }

    private fun handleInboxGesture(gesture: SecondaryHandGestures.Gesture) {
        when (gesture) {
            SecondaryHandGestures.Gesture.SWIPE_LEFT_ARCHIVE -> {
                Log.v(TAG, "  SWIPE_LEFT_ARCHIVE in INBOX: ignored (use voice or tap)")
            }
            SecondaryHandGestures.Gesture.SWIPE_RIGHT_SNOOZE -> {
                Log.v(TAG, "  SWIPE_RIGHT_SNOOZE in INBOX: ignored (use voice or tap)")
            }
            SecondaryHandGestures.Gesture.PINCH_SELECT -> {
                Log.v(TAG, "  PINCH_SELECT in INBOX: no-op (pinch a row via OS click to open)")
            }
            SecondaryHandGestures.Gesture.SWIPE_DOWN_DISMISS -> {
                Log.d(TAG, "  -> collapseToHud()")
                viewModel.collapseToHud()
            }
            SecondaryHandGestures.Gesture.SWIPE_UP_STAR -> {
                Log.v(TAG, "  SWIPE_UP_STAR in INBOX: ignored (use voice or tap)")
            }
            SecondaryHandGestures.Gesture.REVERSE_PINCH_EXPAND,
            SecondaryHandGestures.Gesture.CLOSED_FIST_HOLD_COLLAPSE -> Unit
        }
    }

    private fun handleFocusGesture(gesture: SecondaryHandGestures.Gesture) {
        when (gesture) {
            SecondaryHandGestures.Gesture.SWIPE_DOWN_DISMISS -> {
                Log.d(TAG, "  -> collapseToInbox()")
                viewModel.collapseToInbox()
            }
            SecondaryHandGestures.Gesture.PINCH_SELECT -> {
                // Standard select — OS gaze+pinch already delivered a
                // Compose click to the focused panel; nothing to do here.
            }
            else -> Unit
        }
    }
}

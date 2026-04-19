package com.xremail.app.tracking

import android.content.ContentResolver
import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.runtime.HandTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import androidx.xr.runtime.TrackingState
import androidx.xr.runtime.math.Vector3
import com.xremail.app.util.XrLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "HandGestures"

private const val PINCH_DISTANCE_THRESHOLD = 0.04f
private const val PINCH_HOLD_DURATION_MS = 600L
private const val SWIPE_DISTANCE_THRESHOLD = 0.08f
private const val SWIPE_VELOCITY_THRESHOLD = 0.15f
private const val DEDUP_WINDOW_MS = 250L
// Per-gesture dedup overrides for gestures whose natural re-fire interval
// (hold-duration + finger-recovery) is longer than DEDUP_WINDOW_MS but
// where firing twice in quick succession would be catastrophic.
// OPEN_PALM_HOLD_COLLAPSE collapses one tier per fire — two fires in a
// row jump the user from FOCUS straight to AMBIENT_HUD, which the user
// reported as "the collapse is super glitchy". 1500 ms covers the
// hold-duration (700 ms) + the typical hand-recovery delay where a brief
// tracking blip would otherwise let the timer restart and re-fire.
private const val OPEN_PALM_DEDUP_WINDOW_MS = 1_500L

// Open-palm collapse — minimum distance from each fingertip to the palm
// joint that we consider "extended". Hands at rest curl ~3-4cm; deliberate
// "stop / go back" palm extends to ~7-9cm. Tuned so a relaxed hand at the
// user's side doesn't constantly fire OPEN_PALM_HOLD on the secondary hand.
private const val OPEN_PALM_FINGER_EXTEND_MIN_M = 0.06f
private const val OPEN_PALM_HOLD_DURATION_MS = 700L
// Once OPEN_PALM_HOLD fires we lock it out until the user fully closes the
// hand again — without this, holding the palm extended for 2s fires once
// then re-fires at every dedup-window boundary as the timer keeps ticking.
private const val OPEN_PALM_RELEASE_FINGER_FOLDED_M = 0.04f
// Tracking-loss blips of <= this duration are treated as a continuation
// of the previous gesture (we don't reset openPalmEmitted on loss). On
// Galaxy XR a deliberate "stop hand" held in front of the face often
// causes 1-3 frame tracking dropouts as the palm occludes the camera
// arrays — without this grace period, every dropout restarts the
// open-palm timer and 700 ms later we re-fire the collapse, which the
// user observed as "collapses past the tier I wanted to land on".
private const val TRACKING_LOSS_GRACE_MS = 600L

/**
 * Custom-gesture detector for the user's *secondary* hand only.
 *
 * The Galaxy XR OS already routes pinch-on-the-primary-hand into Compose
 * pointer events on whatever the user is looking at — so attaching custom
 * gesture detection to the primary hand double-fires every system click
 * (this was the bug noted as G3 in the gap analysis). We therefore:
 *
 *   1. Read [Hand.getPrimaryHandSide] from the OS at startup.
 *   2. Attach our custom-gesture state collector ONLY to the opposite hand.
 *   3. Ignore the primary hand entirely; OS gaze + pinch handle clicks.
 *
 * If the primary hand is unknown (emulator, headless test), we attach to
 * both hands so manual testing still works, and [activeSide] reports
 * `Hand.HandSide.UNKNOWN` so callers can show a debug banner.
 */
class SecondaryHandGestures {

    enum class Gesture {
        PINCH_SELECT,
        PINCH_HOLD_EXPAND,
        /**
         * Open-palm hold (all five fingers extended for ~700ms). The natural
         * inverse of [PINCH_HOLD_EXPAND] — pinch-and-hold pushes deeper into
         * the tier hierarchy, palm-and-hold pulls one tier back. Mapped per
         * tier in [com.xremail.app.tracking.GestureToActionMapper].
         */
        OPEN_PALM_HOLD_COLLAPSE,
        SWIPE_LEFT_ARCHIVE,
        SWIPE_RIGHT_SNOOZE,
        SWIPE_DOWN_DISMISS,
        SWIPE_UP_STAR,
    }

    private val _gestures = MutableSharedFlow<Gesture>(extraBufferCapacity = 16)
    val gestures: SharedFlow<Gesture> = _gestures.asSharedFlow()

    /**
     * Wall-clock millis of the most recent gesture emission OR an external
     * pinch/click that should suppress the gaze-dwell expansion. Bumped by
     * both internal gestures and (externally) by [bumpInteraction] from the
     * OS pinch path so the gaze-dwell doesn't fire mid-click.
     */
    private val _lastInteractionMs = MutableStateFlow(0L)
    val lastInteractionMs: StateFlow<Long> = _lastInteractionMs.asStateFlow()

    private var trackingJobs: List<Job> = emptyList()
    private val handStates = mutableMapOf<String, HandState>()

    @Volatile private var lastEmitType: Gesture? = null
    @Volatile private var lastEmitMs: Long = 0L

    @Volatile private var activeSide: Hand.HandSide = Hand.HandSide.UNKNOWN

    fun startTracking(
        session: Session,
        contentResolver: ContentResolver,
        scope: CoroutineScope,
    ) {
        stopTracking()

        val configResult = session.configure(
            session.config.copy(handTracking = HandTrackingMode.BOTH)
        )
        if (configResult is SessionConfigureSuccess) {
            XrLog.d(TAG, "hand tracking configured (mode=BOTH)")
        } else {
            XrLog.w(TAG, "hand tracking config returned $configResult — gestures may be degraded")
        }

        val primarySide = try {
            Hand.getPrimaryHandSide(contentResolver)
        } catch (t: Throwable) {
            XrLog.w(TAG, "getPrimaryHandSide threw, defaulting to UNKNOWN", t)
            Hand.HandSide.UNKNOWN
        }
        activeSide = primarySide
        XrLog.i(TAG, "primary hand from OS = $primarySide")

        val left = Hand.left(session)
        val right = Hand.right(session)

        // Choose the hand(s) to actually attach to. Custom gestures should
        // ride the SECONDARY hand so they don't double-fire with OS pinch.
        // On UNKNOWN we attach to both so emulator testing still works.
        val attachLeft: Boolean
        val attachRight: Boolean
        when (primarySide) {
            Hand.HandSide.LEFT -> { attachLeft = false; attachRight = true }
            Hand.HandSide.RIGHT -> { attachLeft = true; attachRight = false }
            Hand.HandSide.UNKNOWN -> { attachLeft = true; attachRight = true }
        }

        val jobs = mutableListOf<Job>()

        if (attachLeft) {
            if (left == null) {
                XrLog.w(TAG, "wanted to attach to LEFT but Hand.left() returned null")
            } else {
                handStates["L"] = HandState("L")
                XrLog.i(TAG, "attaching custom-gesture collector -> LEFT hand (secondary)")
                jobs += scope.launch {
                    left.state.collect { handState ->
                        processHandState(handState, handStates.getValue("L"))
                    }
                }
            }
        }

        if (attachRight) {
            if (right == null) {
                XrLog.w(TAG, "wanted to attach to RIGHT but Hand.right() returned null")
            } else {
                handStates["R"] = HandState("R")
                XrLog.i(TAG, "attaching custom-gesture collector -> RIGHT hand (secondary)")
                jobs += scope.launch {
                    right.state.collect { handState ->
                        processHandState(handState, handStates.getValue("R"))
                    }
                }
            }
        }

        if (jobs.isEmpty()) {
            XrLog.w(TAG, "no hand collectors attached — gestures disabled")
        }
        trackingJobs = jobs
    }

    fun stopTracking() {
        trackingJobs.forEach { it.cancel() }
        trackingJobs = emptyList()
        handStates.values.forEach { it.reset() }
        handStates.clear()
    }

    /**
     * Surfaced for the AMBIENT_HUD gaze-dwell timer: any external interaction
     * (an OS pinch landing on a panel, a Compose click, etc.) should be able
     * to bump this so the dwell doesn't expand mid-click.
     */
    fun bumpInteraction() {
        val now = System.currentTimeMillis()
        _lastInteractionMs.value = now
        XrLog.v(TAG, "bumpInteraction() at $now")
    }

    private fun processHandState(handState: Hand.State, s: HandState) {
        val now = System.currentTimeMillis()
        if (handState.trackingState != TrackingState.TRACKING) {
            // Only log the *transition* into not-tracking, otherwise we'd spam
            // every frame where a hand is out of view.
            if (s.wasTracking) {
                XrLog.v(TAG, "${s.label} hand lost tracking (state=${handState.trackingState})")
                s.wasTracking = false
                s.trackingLostAtMs = now
                // INTENTIONALLY do NOT call s.reset() here. Brief tracking
                // dropouts (1-3 frames, common when an open palm occludes
                // the headset cameras) used to clear `openPalmEmitted` and
                // `pinchEmitted`, which let a sustained gesture re-fire a
                // hold the moment tracking recovered. The actual reset
                // happens below if the gap exceeds TRACKING_LOSS_GRACE_MS.
            }
            return
        }
        if (!s.wasTracking) {
            // Tracking just recovered. If it was offline longer than the
            // grace window, treat it as a fresh start (the old gesture
            // intent is stale). Otherwise keep all state — including
            // emit-lockouts — so a momentary blip during a held gesture
            // doesn't double-fire.
            val gap = now - s.trackingLostAtMs
            if (s.trackingLostAtMs != 0L && gap > TRACKING_LOSS_GRACE_MS) {
                XrLog.v(TAG, "${s.label} hand re-acquired after ${gap}ms gap — full reset")
                s.reset()
            } else {
                XrLog.v(TAG, "${s.label} hand re-acquired after ${gap}ms gap — preserving gesture state")
            }
            s.wasTracking = true
            s.trackingLostAtMs = 0L
        }

        val thumbTip = handState.handJoints[HandJointType.HAND_JOINT_TYPE_THUMB_TIP] ?: return
        val indexTip = handState.handJoints[HandJointType.HAND_JOINT_TYPE_INDEX_TIP] ?: return
        val middleTip = handState.handJoints[HandJointType.HAND_JOINT_TYPE_MIDDLE_TIP] ?: return
        val ringTip = handState.handJoints[HandJointType.HAND_JOINT_TYPE_RING_TIP] ?: return
        val littleTip = handState.handJoints[HandJointType.HAND_JOINT_TYPE_LITTLE_TIP] ?: return
        val palm = handState.handJoints[HandJointType.HAND_JOINT_TYPE_PALM] ?: return

        val pinchDistance = Vector3.distance(thumbTip.translation, indexTip.translation)

        detectPinch(pinchDistance, now, s)
        // Open-palm detection runs in parallel with pinch so a held pinch
        // can't accidentally satisfy "all fingers extended" — we explicitly
        // require the thumb-index distance to be wide too inside the helper.
        detectOpenPalmHold(
            pinchDistance = pinchDistance,
            indexTipDistFromPalm = Vector3.distance(indexTip.translation, palm.translation),
            middleTipDistFromPalm = Vector3.distance(middleTip.translation, palm.translation),
            ringTipDistFromPalm = Vector3.distance(ringTip.translation, palm.translation),
            littleTipDistFromPalm = Vector3.distance(littleTip.translation, palm.translation),
            now = now,
            s = s,
        )
        trackPalmForSwipe(palm.translation, now, s)
    }

    private fun detectPinch(distance: Float, now: Long, s: HandState) {
        val wasPinching = s.isPinching
        s.isPinching = distance < PINCH_DISTANCE_THRESHOLD

        if (s.isPinching && !wasPinching) {
            s.pinchStartTimeMs = now
            s.pinchEmitted = false
            XrLog.v(TAG, "${s.label} pinch START distance=${"%.3f".format(distance)}m")
        }

        if (s.isPinching && !s.pinchEmitted) {
            val holdDuration = now - s.pinchStartTimeMs
            if (holdDuration >= PINCH_HOLD_DURATION_MS) {
                XrLog.d(TAG, "${s.label} pinch HOLD ${holdDuration}ms -> PINCH_HOLD_EXPAND")
                emit(Gesture.PINCH_HOLD_EXPAND, s)
                s.pinchEmitted = true
            }
        }

        if (!s.isPinching && wasPinching && !s.pinchEmitted) {
            val held = now - s.pinchStartTimeMs
            XrLog.d(TAG, "${s.label} pinch RELEASE held=${held}ms -> PINCH_SELECT")
            emit(Gesture.PINCH_SELECT, s)
        }
    }

    /**
     * Open-palm hold (collapse) detector. Fires [Gesture.OPEN_PALM_HOLD_COLLAPSE]
     * when the user holds an open hand — all four non-thumb fingertips
     * stretched away from the palm center, AND the thumb-index distance well
     * past pinch threshold — for at least [OPEN_PALM_HOLD_DURATION_MS].
     *
     * Once it fires we lock out re-fires until the hand closes back down so
     * a sustained "stop" hand doesn't spam collapse events. Lock releases
     * the moment any non-thumb fingertip folds inside
     * [OPEN_PALM_RELEASE_FINGER_FOLDED_M].
     */
    private fun detectOpenPalmHold(
        pinchDistance: Float,
        indexTipDistFromPalm: Float,
        middleTipDistFromPalm: Float,
        ringTipDistFromPalm: Float,
        littleTipDistFromPalm: Float,
        now: Long,
        s: HandState,
    ) {
        // "Open" = thumb is also clearly NOT pinching the index. Otherwise
        // a deliberate pinch with a slightly-curled middle/ring finger could
        // satisfy the "all fingers extended" condition and double-fire.
        val thumbOpen = pinchDistance > PINCH_DISTANCE_THRESHOLD * 2f
        val allFingersExtended =
            indexTipDistFromPalm > OPEN_PALM_FINGER_EXTEND_MIN_M &&
                middleTipDistFromPalm > OPEN_PALM_FINGER_EXTEND_MIN_M &&
                ringTipDistFromPalm > OPEN_PALM_FINGER_EXTEND_MIN_M &&
                littleTipDistFromPalm > OPEN_PALM_FINGER_EXTEND_MIN_M
        val openNow = thumbOpen && allFingersExtended

        // Release the lockout the moment the hand closes (any non-thumb
        // finger folded back). Required so a second deliberate "stop" hand
        // is allowed to fire after the first has been acknowledged.
        if (s.openPalmEmitted) {
            val anyFingerFolded =
                indexTipDistFromPalm < OPEN_PALM_RELEASE_FINGER_FOLDED_M ||
                    middleTipDistFromPalm < OPEN_PALM_RELEASE_FINGER_FOLDED_M ||
                    ringTipDistFromPalm < OPEN_PALM_RELEASE_FINGER_FOLDED_M ||
                    littleTipDistFromPalm < OPEN_PALM_RELEASE_FINGER_FOLDED_M
            if (anyFingerFolded) {
                s.openPalmEmitted = false
                s.openPalmStartTimeMs = 0L
                XrLog.v(TAG, "${s.label} open-palm lockout released (hand closed)")
            }
        }

        if (openNow && !s.wasOpenPalm) {
            s.openPalmStartTimeMs = now
            XrLog.v(TAG, "${s.label} open-palm START " +
                "(idx=${"%.3f".format(indexTipDistFromPalm)}m " +
                "mid=${"%.3f".format(middleTipDistFromPalm)}m " +
                "ring=${"%.3f".format(ringTipDistFromPalm)}m " +
                "lit=${"%.3f".format(littleTipDistFromPalm)}m)")
        }

        if (openNow && !s.openPalmEmitted) {
            val held = now - s.openPalmStartTimeMs
            if (held >= OPEN_PALM_HOLD_DURATION_MS) {
                XrLog.d(TAG, "${s.label} open-palm HOLD ${held}ms -> OPEN_PALM_HOLD_COLLAPSE")
                emit(Gesture.OPEN_PALM_HOLD_COLLAPSE, s)
                s.openPalmEmitted = true
            }
        }

        s.wasOpenPalm = openNow
    }

    private fun trackPalmForSwipe(palmPos: Vector3, now: Long, s: HandState) {
        s.palmPositionHistory.add(now to palmPos)
        if (s.palmPositionHistory.size > POSITION_HISTORY_MAX) {
            s.palmPositionHistory.removeAt(0)
        }

        if (s.palmPositionHistory.size < 4) return

        val oldest = s.palmPositionHistory.first()
        val newest = s.palmPositionHistory.last()
        val dt = (newest.first - oldest.first) / 1000f
        if (dt < 0.05f) return

        val dx = newest.second.x - oldest.second.x
        val dy = newest.second.y - oldest.second.y

        val vx = dx / dt
        val vy = dy / dt

        val absDx = kotlin.math.abs(dx)
        val absDy = kotlin.math.abs(dy)

        if (absDx > SWIPE_DISTANCE_THRESHOLD && absDx > absDy * 1.5f) {
            if (kotlin.math.abs(vx) > SWIPE_VELOCITY_THRESHOLD) {
                val gesture = if (vx > 0) Gesture.SWIPE_RIGHT_SNOOZE
                              else Gesture.SWIPE_LEFT_ARCHIVE
                XrLog.d(TAG, "${s.label} swipe vx=${"%.2f".format(vx)} -> $gesture")
                emit(gesture, s)
                s.palmPositionHistory.clear()
            }
        } else if (absDy > SWIPE_DISTANCE_THRESHOLD && absDy > absDx * 1.5f) {
            if (kotlin.math.abs(vy) > SWIPE_VELOCITY_THRESHOLD) {
                val gesture = if (vy > 0) Gesture.SWIPE_UP_STAR
                              else Gesture.SWIPE_DOWN_DISMISS
                XrLog.d(TAG, "${s.label} swipe vy=${"%.2f".format(vy)} -> $gesture")
                emit(gesture, s)
                s.palmPositionHistory.clear()
            }
        }
    }

    private fun emit(gesture: Gesture, s: HandState) {
        val now = System.currentTimeMillis()
        // Some gestures get a longer dedup window than the default 250 ms
        // because their natural emit cadence is slower than that AND a
        // double-fire produces a user-visible regression (skipping past
        // the tier the user wanted to land on).
        val dedupWindow = when (gesture) {
            Gesture.OPEN_PALM_HOLD_COLLAPSE -> OPEN_PALM_DEDUP_WINDOW_MS
            else -> DEDUP_WINDOW_MS
        }
        if (lastEmitType == gesture && now - lastEmitMs < dedupWindow) {
            XrLog.v(TAG, "  dedup ${s.label} $gesture (within ${dedupWindow}ms)")
            return
        }
        lastEmitType = gesture
        lastEmitMs = now
        _lastInteractionMs.value = now
        XrLog.i(TAG, "EMIT ${s.label} $gesture")
        _gestures.tryEmit(gesture)
    }

    fun onGestureDetected(gesture: Gesture) {
        XrLog.d(TAG, "onGestureDetected($gesture) — external/test path")
        _gestures.tryEmit(gesture)
        _lastInteractionMs.value = System.currentTimeMillis()
    }

    fun simulateGesture(gesture: Gesture) {
        XrLog.d(TAG, "simulateGesture($gesture) — emulator path")
        _gestures.tryEmit(gesture)
        _lastInteractionMs.value = System.currentTimeMillis()
    }

    private class HandState(val label: String) {
        var wasTracking = false
        // Wall-clock ms when tracking was lost most recently, or 0 if
        // currently tracking (or never tracked). Used by processHandState
        // to decide whether a tracking-recovery should fully reset gesture
        // state (long gap → user clearly let go) or preserve it (brief
        // dropout during a held pose → user is still mid-gesture).
        var trackingLostAtMs = 0L
        var isPinching = false
        var pinchStartTimeMs = 0L
        var pinchEmitted = false
        var wasOpenPalm = false
        var openPalmStartTimeMs = 0L
        var openPalmEmitted = false
        val palmPositionHistory = mutableListOf<Pair<Long, Vector3>>()

        fun reset() {
            isPinching = false
            pinchStartTimeMs = 0L
            pinchEmitted = false
            wasOpenPalm = false
            openPalmStartTimeMs = 0L
            openPalmEmitted = false
            palmPositionHistory.clear()
        }
    }

    companion object {
        private const val POSITION_HISTORY_MAX = 10
    }
}

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

// Tighter pinch threshold (was 0.04 = 4cm). Hand-tracking noise on Galaxy XR
// would intermittently report thumb-index within 4cm even when the user's
// hand was at rest, firing spurious PINCH_SELECT events. 2.5cm requires a
// genuinely deliberate pinch.
private const val PINCH_DISTANCE_THRESHOLD = 0.025f
// Pinch-hold duration before we fire PINCH_HOLD_EXPAND (tier expansion).
//
// History: 600ms felt sluggish, dropped to 350ms for snappier feel — but
// the user reported the HUD "opens without a gesture sometimes" with
// the 350 setting. Diagnosis: 350ms is shorter than the time the user's
// SECONDARY (non-dominant) hand spends in an incidental pinch-like pose
// while holding a coffee, walking with arms swinging, gripping a phone,
// etc. Thumb-index naturally sit within 2.5cm during plenty of unrelated
// hand activity, and 350ms is well inside that envelope.
//
// 550ms is well past any incidental pose duration but still feels
// responsive when the user is *intentionally* holding a deliberate
// pinch ("I am doing a thing"). Combined with PINCH_TAP_MIN_HOLD_MS
// filtering ghost pinches at the bottom, the false-positive rate
// drops to near zero.
private const val PINCH_HOLD_DURATION_MS = 550L
// Minimum pinch hold time before a release counts as PINCH_SELECT (tap).
// Single-frame "ghost pinches" from tracking jitter clear in <50ms; a
// deliberate tap is at least ~80ms. This filters out the noise.
private const val PINCH_TAP_MIN_HOLD_MS = 80L
private const val SWIPE_DISTANCE_THRESHOLD = 0.08f
private const val SWIPE_VELOCITY_THRESHOLD = 0.15f
private const val DEDUP_WINDOW_MS = 250L
// Per-gesture dedup overrides for gestures whose natural re-fire interval
// (hold-duration + finger-recovery) is longer than DEDUP_WINDOW_MS but
// where firing twice in quick succession would be catastrophic.
// CLOSED_FIST_HOLD_COLLAPSE collapses one tier per fire — two fires in a
// row jump the user from FOCUS straight to AMBIENT_HUD, which the user
// reported as "the collapse is super glitchy". 1500 ms covers the
// hold-duration (600 ms) + the typical hand-recovery delay where a brief
// tracking blip would otherwise let the timer restart and re-fire.
private const val CLOSED_FIST_DEDUP_WINDOW_MS = 1_500L

// Closed-fist collapse — maximum distance from each fingertip to the
// palm CENTER joint (HAND_JOINT_TYPE_PALM). LOOSENED from the original
// 2.5cm: that threshold was effectively unsatisfiable on real hands
// because the palm joint on Galaxy XR is the geometric center of the
// metacarpal plate, not the surface of the palm. Even with a hard,
// deliberate fist the fingertips wrap to the FRONT of the palm and end
// up 3-5cm from the palm-center joint — measured directly on the
// device. The original 2.5cm meant the gesture detected approximately
// never, which is why "closed-fist hold doesn't collapse anything"
// was the user-reported behaviour. 4.5cm catches any genuinely-curled
// hand while staying tighter than a relaxed claw (relaxed-claw
// fingertips sit 7-8cm from palm center).
private const val CLOSED_FIST_FINGER_FOLD_MAX_M = 0.045f
// In a real fist the thumb wraps OVER the curled fingers, so the
// thumb-tip ends up close to the palm too (~3-4cm from the palm
// SURFACE, ~5-6cm from the palm-center joint). A relaxed open hand
// has the thumb sticking 8-10cm out from palm center. Bumped from
// 4.5cm to 6.5cm for the same palm-center-vs-surface reason as the
// finger threshold above — a thumb-tucked fist wraps around the front
// so the thumb tip is closer to the front of the palm than to the
// geometric center the joint reports.
private const val CLOSED_FIST_THUMB_FOLD_MAX_M = 0.065f
// Hold duration. Same target as the old open-palm-hold (600ms) — fast
// enough to feel snappy, slow enough that incidental momentary fist-like
// poses (gripping a coffee, holding a phone) don't sustain past it.
private const val CLOSED_FIST_HOLD_DURATION_MS = 600L
// Once CLOSED_FIST_HOLD fires we lock it out until the user opens the
// hand again — without this, holding a fist for 2s fires once then
// re-fires every dedup window as the timer keeps ticking. Lock releases
// the moment ANY non-thumb fingertip extends past this threshold.
// Bumped from 6cm to 8cm to match the loosened FOLD_MAX (the release
// has to sit comfortably above the fold-detection threshold or
// borderline finger positions oscillate the lockout flag every frame).
private const val CLOSED_FIST_RELEASE_FINGER_EXTEND_M = 0.08f
// Tracking-loss blips of <= this duration are treated as a continuation
// of the previous gesture (we don't reset closedFistEmitted on loss). On
// Galaxy XR a held gesture in front of the face often causes 1-3 frame
// tracking dropouts as the hand occludes the camera arrays — without
// this grace period, every dropout restarts the timer and we re-fire
// the collapse, which the user observed as "collapses past the tier I
// wanted to land on".
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
         * Closed-fist hold — all four non-thumb fingertips folded tight to
         * the palm AND the thumb tucked over them, held for ~600ms.
         * The intentional inverse of [PINCH_HOLD_EXPAND]: pinch-and-hold
         * pushes deeper into the tier hierarchy, fist-and-hold pulls one
         * tier back. Mapped per tier in
         * [com.xremail.app.tracking.GestureToActionMapper].
         *
         * Replaced the previous OPEN_PALM_HOLD_COLLAPSE because an open
         * palm is an extremely common incidental pose (waving, gesturing
         * while talking, picking something up) and fired false collapses
         * far more often than it fired intentional ones. A clenched fist
         * with the thumb tucked is essentially never produced by accident.
         */
        CLOSED_FIST_HOLD_COLLAPSE,
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

    /**
     * Live progress of the closed-fist "collapse" hold, 0f → 1f.
     * 0f when the user is not holding a fist, ramps up while held, snaps
     * to 1f at the moment CLOSED_FIST_HOLD_COLLAPSE fires, then drops
     * back to 0f. Drives the peripheral panel's visible collapse-affordance
     * ring so the user can SEE the gesture being recognized in real time.
     */
    private val _closedFistProgress = MutableStateFlow(0f)
    val closedFistProgress: StateFlow<Float> = _closedFistProgress.asStateFlow()

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
        _closedFistProgress.value = 0f
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
                // dropouts (1-3 frames, common when a clenched fist or
                // pinched fingers self-occlude the camera arrays) used to
                // clear `closedFistEmitted` and `pinchEmitted`, which let
                // a sustained gesture re-fire a hold the moment tracking
                // recovered. The actual reset happens below if the gap
                // exceeds TRACKING_LOSS_GRACE_MS.
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
        // Closed-fist detection runs in parallel with pinch. A held pinch
        // satisfies "thumb close to index" but NOT "all four fingers
        // tucked AND thumb tucked", so the two gestures can't collide.
        detectClosedFistHold(
            thumbTipDistFromPalm = Vector3.distance(thumbTip.translation, palm.translation),
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
            if (held < PINCH_TAP_MIN_HOLD_MS) {
                // Filter sub-80ms "ghost pinches" — these are tracking
                // jitter, not user intent. The user reported the HUD
                // "expanding without me doing a gesture" and these
                // single-frame pinch flickers were the cause.
                XrLog.v(TAG, "${s.label} pinch RELEASE held=${held}ms -> SUPPRESSED (below tap min)")
            } else {
                XrLog.d(TAG, "${s.label} pinch RELEASE held=${held}ms -> PINCH_SELECT")
                emit(Gesture.PINCH_SELECT, s)
            }
        }
    }

    /**
     * Closed-fist hold (collapse) detector. Fires
     * [Gesture.CLOSED_FIST_HOLD_COLLAPSE] when the user clenches a fist —
     * all four non-thumb fingertips folded tight to the palm AND the
     * thumb tucked over them — for at least [CLOSED_FIST_HOLD_DURATION_MS].
     *
     * Once it fires we lock out re-fires until the hand opens back up so
     * a sustained fist doesn't spam collapse events. The lock releases
     * the moment any non-thumb fingertip extends past
     * [CLOSED_FIST_RELEASE_FINGER_EXTEND_M].
     *
     * Replaced detectOpenPalmHold per user feedback: open palms are an
     * extremely common incidental pose, fists are essentially never made
     * accidentally.
     */
    private fun detectClosedFistHold(
        thumbTipDistFromPalm: Float,
        indexTipDistFromPalm: Float,
        middleTipDistFromPalm: Float,
        ringTipDistFromPalm: Float,
        littleTipDistFromPalm: Float,
        now: Long,
        s: HandState,
    ) {
        val allFingersFolded =
            indexTipDistFromPalm < CLOSED_FIST_FINGER_FOLD_MAX_M &&
                middleTipDistFromPalm < CLOSED_FIST_FINGER_FOLD_MAX_M &&
                ringTipDistFromPalm < CLOSED_FIST_FINGER_FOLD_MAX_M &&
                littleTipDistFromPalm < CLOSED_FIST_FINGER_FOLD_MAX_M
        // Thumb-tucked check separates a real fist (thumb wraps over the
        // curled fingers, ending up close to the palm) from a relaxed
        // hand (thumb extended off to the side). A relaxed hand can have
        // loosely-curled fingers but the thumb is almost always >5cm
        // from the palm, so this gate alone kills most false positives.
        val thumbTucked = thumbTipDistFromPalm < CLOSED_FIST_THUMB_FOLD_MAX_M
        val fistNow = allFingersFolded && thumbTucked

        // DIAGNOSTIC: when the hand is close to fist-shape but doesn't
        // quite satisfy both gates, log the joint distances every ~500ms
        // so we can SEE on the wire how close the user's gesture is to
        // crossing. Without this, "closed-fist hold doesn't collapse"
        // is impossible to debug because there's zero signal — the
        // detector silently doesn't fire. Throttled to once per ~500ms
        // per hand to stay out of the way of normal logs.
        val maxFingerDist = maxOf(
            indexTipDistFromPalm,
            middleTipDistFromPalm,
            ringTipDistFromPalm,
            littleTipDistFromPalm,
        )
        val nearFist = maxFingerDist < CLOSED_FIST_FINGER_FOLD_MAX_M * 1.5f &&
            thumbTipDistFromPalm < CLOSED_FIST_THUMB_FOLD_MAX_M * 1.5f
        if (!fistNow && nearFist && now - s.lastFistDiagLogMs > 500L) {
            s.lastFistDiagLogMs = now
            XrLog.v(
                TAG,
                "${s.label} near-fist (NOT firing) " +
                    "fingers=${"%.3f".format(maxFingerDist)}m " +
                    "(need <${CLOSED_FIST_FINGER_FOLD_MAX_M}m, " +
                    "fingersOk=$allFingersFolded) " +
                    "thumb=${"%.3f".format(thumbTipDistFromPalm)}m " +
                    "(need <${CLOSED_FIST_THUMB_FOLD_MAX_M}m, " +
                    "thumbOk=$thumbTucked)",
            )
        }

        // Release the lockout the moment the hand opens (any non-thumb
        // finger extends past the release threshold). Required so a
        // second deliberate fist is allowed to fire after the first has
        // been acknowledged.
        if (s.closedFistEmitted) {
            val anyFingerExtended =
                indexTipDistFromPalm > CLOSED_FIST_RELEASE_FINGER_EXTEND_M ||
                    middleTipDistFromPalm > CLOSED_FIST_RELEASE_FINGER_EXTEND_M ||
                    ringTipDistFromPalm > CLOSED_FIST_RELEASE_FINGER_EXTEND_M ||
                    littleTipDistFromPalm > CLOSED_FIST_RELEASE_FINGER_EXTEND_M
            if (anyFingerExtended) {
                s.closedFistEmitted = false
                s.closedFistStartTimeMs = 0L
                XrLog.v(TAG, "${s.label} closed-fist lockout released (hand opened)")
            }
        }

        if (fistNow && !s.wasClosedFist) {
            s.closedFistStartTimeMs = now
            XrLog.v(TAG, "${s.label} closed-fist START " +
                "(thm=${"%.3f".format(thumbTipDistFromPalm)}m " +
                "idx=${"%.3f".format(indexTipDistFromPalm)}m " +
                "mid=${"%.3f".format(middleTipDistFromPalm)}m " +
                "ring=${"%.3f".format(ringTipDistFromPalm)}m " +
                "lit=${"%.3f".format(littleTipDistFromPalm)}m)")
        }

        if (fistNow && !s.closedFistEmitted) {
            val held = now - s.closedFistStartTimeMs
            val progress = (held.toFloat() / CLOSED_FIST_HOLD_DURATION_MS).coerceIn(0f, 1f)
            _closedFistProgress.value = progress
            if (held >= CLOSED_FIST_HOLD_DURATION_MS) {
                XrLog.d(TAG, "${s.label} closed-fist HOLD ${held}ms -> CLOSED_FIST_HOLD_COLLAPSE")
                _closedFistProgress.value = 1f
                emit(Gesture.CLOSED_FIST_HOLD_COLLAPSE, s)
                s.closedFistEmitted = true
            }
        } else if (!fistNow && _closedFistProgress.value != 0f) {
            // User released the fist before the threshold (or after the
            // gesture fired) — reset the progress meter so the UI ring
            // empties out cleanly.
            _closedFistProgress.value = 0f
        }

        s.wasClosedFist = fistNow
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
            Gesture.CLOSED_FIST_HOLD_COLLAPSE -> CLOSED_FIST_DEDUP_WINDOW_MS
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
        var wasClosedFist = false
        var closedFistStartTimeMs = 0L
        var closedFistEmitted = false
        // Throttle for the "near-fist (NOT firing)" diagnostic — see
        // detectClosedFistHold. Plain wall-clock ms; 0 means never logged.
        var lastFistDiagLogMs = 0L
        val palmPositionHistory = mutableListOf<Pair<Long, Vector3>>()

        fun reset() {
            isPinching = false
            pinchStartTimeMs = 0L
            pinchEmitted = false
            wasClosedFist = false
            closedFistStartTimeMs = 0L
            closedFistEmitted = false
            lastFistDiagLogMs = 0L
            palmPositionHistory.clear()
        }
    }

    companion object {
        private const val POSITION_HISTORY_MAX = 10
    }
}

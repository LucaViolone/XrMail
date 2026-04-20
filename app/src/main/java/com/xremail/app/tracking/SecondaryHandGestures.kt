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

// Pinch distance threshold — how close thumb-tip and index-tip must be
// to count as "pinched". Tightened 2026-04-19 from 2.5cm → 2.1cm (15%
// tighter) per user feedback that gestures were "slightly sensitive".
// A tighter threshold means the user's thumb and index have to actually
// TOUCH (or be within ~2mm) rather than just "close" — which reads as
// more intentional.
private const val PINCH_DISTANCE_THRESHOLD = 0.021f
// Minimum pinch hold time before a release counts as PINCH_SELECT (tap).
// Bumped from 80ms → 92ms (15%) for more intent — a deliberate tap
// still clears 92ms easily, but ghost-pinch single-frame flickers
// (5-40ms) are now filtered further from the threshold.
private const val PINCH_TAP_MIN_HOLD_MS = 92L

// --- REVERSE-PINCH EXPAND ("open-fingered expansion") ------------------
// Replaces the old 550-ms sustained PINCH_HOLD_EXPAND. The user's direct
// feedback: "the open-fingered expansion gesture we had before" — a
// thumb-and-index spread, possible with "less than 5 fingers" so no
// full open-palm required. Gesture shape:
//
//   1. Thumb and index start within COMPRESSED_M of each other (i.e.
//      the hand is in a pinched/closed starting pose).
//   2. The user deliberately spreads them apart.
//   3. When thumb-index distance crosses SPREAD_M within WINDOW_MS of
//      the most recent compressed sample, REVERSE_PINCH_EXPAND fires.
//
// All three values tightened 15% on 2026-04-19: start more compressed
// (2.6cm vs 3.0cm), end more spread (11.5cm vs 10.0cm), and complete
// the motion faster (425ms vs 500ms). The combined effect is a gesture
// that requires a genuine, deliberate "snap open" rather than any
// gradual opening of the hand being interpreted as expand.
private const val REVERSE_PINCH_COMPRESSED_M = 0.026f
private const val REVERSE_PINCH_SPREAD_M = 0.115f
private const val REVERSE_PINCH_WINDOW_MS = 425L
// Air-swipe thresholds. RAISED from (0.08m, 0.15m/s) because the old
// values detected "swipe" on the natural hand motion of a user walking
// around — an arm swinging at a casual pace hits 15-30 cm/s with 8+ cm
// of palm travel every cycle. The user reported this as "things are
// getting randomly archived and snoozed without intended actions".
//
// 25 cm of travel at 60 cm/s means the user has to consciously whip
// their hand across their body — at rest arm swings hit ~30 cm/s peak
// and travel 8-12 cm per swing, both well under these thresholds.
// A deliberate swipe (hand at chest height, flung sideways with intent)
// easily clears both gates.
private const val SWIPE_DISTANCE_THRESHOLD = 0.25f
private const val SWIPE_VELOCITY_THRESHOLD = 0.60f
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
// palm CENTER joint (HAND_JOINT_TYPE_PALM). Tightened 2026-04-19 from
// 6.0cm → 5.1cm (15% tighter) per user feedback on sensitivity: the
// loose threshold was catching half-curled poses (e.g. hand relaxed
// at side while walking) that didn't read as intentional fists. 5.1cm
// still catches any genuinely deliberate fist while rejecting the
// borderline incidental curls.
private const val CLOSED_FIST_FINGER_FOLD_MAX_M = 0.051f
// Thumb-to-palm trigger. Tightened from 8.5cm → 7.2cm (15%). Users who
// don't tuck the thumb over the fingers now need to tuck it closer; a
// floating thumb (hand just curled without a conscious "make a fist"
// intent) falls outside the gate.
private const val CLOSED_FIST_THUMB_FOLD_MAX_M = 0.072f
// Hold duration. Bumped from 400ms → 460ms (15% longer) for more intent:
// fleeting fist-like poses (e.g. hand briefly closing around a door
// handle) are under 400ms end-to-end, so 385ms sits just under that
// floor while still feeling snappy — tuned by ear/feel, not by a
// fixed percentage off the original 460ms.
private const val CLOSED_FIST_HOLD_DURATION_MS = 385L
// Once CLOSED_FIST_HOLD fires we lock it out until the user opens the
// hand again — without this, holding a fist for 2s fires once then
// re-fires every dedup window as the timer keeps ticking. Lock releases
// the moment ANY non-thumb fingertip extends past this threshold.
// Held at 11cm (NOT tightened with the 15% sweep) because the release
// threshold sits above maintain — if we tightened this to ~9.4cm it
// would start oscillating against a loosely-relaxed hand.
private const val CLOSED_FIST_RELEASE_FINGER_EXTEND_M = 0.110f

// Hysteresis: once a closed-fist has STARTED (fingers folded past the
// trigger threshold), we keep the timer running as long as the hand
// stays past this LOOSER "maintenance" threshold. Tightened from
// 7.5cm/10cm → 6.4cm/8.5cm (15%) to stay proportional to the tightened
// trigger; the maintain band still sits between trigger and release
// so jitter doesn't reset the timer, but slipping to a non-fist pose
// drops it sooner.
private const val CLOSED_FIST_MAINTAIN_FINGER_MAX_M = 0.064f
private const val CLOSED_FIST_MAINTAIN_THUMB_MAX_M = 0.085f
// Tracking-loss blips of <= this duration are treated as a continuation
// of the previous gesture (we don't reset closedFistEmitted on loss). On
// Galaxy XR a held gesture in front of the face often causes 1-3 frame
// tracking dropouts as the hand occludes the camera arrays — without
// this grace period, every dropout restarts the timer and we re-fire
// the collapse, which the user observed as "collapses past the tier I
// wanted to land on".
private const val TRACKING_LOSS_GRACE_MS = 600L

/**
 * Custom-gesture detector. Historically attached to the user's secondary
 * (non-dominant) hand to avoid double-firing with OS gaze+pinch on the
 * primary hand. Per the user's direct feedback ("all of it should be
 * dominant hand") this now attaches to the DOMINANT / PRIMARY hand —
 * the same hand the OS uses for gaze+pinch clicks. This is safe because:
 *
 *   - PINCH_SELECT is a no-op in [GestureToActionMapper] everywhere. The
 *     OS gaze+pinch path already delivers a Compose click on whatever
 *     the user is looking at, which is the intended "select/open" path.
 *     The custom PINCH_SELECT signal is kept only for logging + feedback
 *     pill consistency.
 *   - REVERSE_PINCH_EXPAND and CLOSED_FIST_HOLD_COLLAPSE are never
 *     emitted by the OS, so they can't double-fire.
 *
 * Attachment logic:
 *   1. Read [Hand.getPrimaryHandSide] from the OS at startup.
 *   2. Attach custom-gesture state collector to THAT hand (dominant).
 *   3. If the side is UNKNOWN (emulator / headless test) attach to
 *      both hands so manual testing still works.
 *
 * The class name is retained for git-blame stability; it's really
 * "DominantHandGestures" in behavior now.
 */
class SecondaryHandGestures {

    enum class Gesture {
        PINCH_SELECT,
        /**
         * Reverse-pinch — thumb and index start close (the user is in a
         * pinched starting pose) and then spread apart. Fires when
         * thumb-index distance exceeds [REVERSE_PINCH_SPREAD_M] within
         * [REVERSE_PINCH_WINDOW_MS] of a compressed sample.
         *
         * Replaced the previous PINCH_HOLD_EXPAND (a 550-ms sustained
         * pinch) because "hold still" gestures are slow and ambiguous.
         * A spread / reverse-pinch is the natural visual inverse of the
         * closed-fist collapse — two fingers open outwards to open a
         * tier, a whole hand closes to a fist to back out.
         *
         * Only two fingers are required (thumb + index). The user's
         * feedback: "can be less than 5 fingers for expand, a reverse
         * pinch with thumb and index should expand".
         */
        REVERSE_PINCH_EXPAND,
        /**
         * Closed-fist hold — all four non-thumb fingertips folded tight to
         * the palm AND the thumb tucked over them, held for
         * [CLOSED_FIST_HOLD_DURATION_MS]. The intentional inverse of
         * [REVERSE_PINCH_EXPAND]: open two fingers to push deeper into
         * the tier hierarchy, close all fingers to pull one tier back.
         * Mapped per tier in [GestureToActionMapper].
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

    /**
     * Live progress of the reverse-pinch "expand" gesture, 0f → 1f.
     * Mirrors [closedFistProgress] but for the expansion gesture — 0f
     * when the user isn't currently opening from a compressed pose,
     * ramps up as thumb-index distance grows from
     * [REVERSE_PINCH_COMPRESSED_M] toward [REVERSE_PINCH_SPREAD_M], and
     * snaps to 1f the frame REVERSE_PINCH_EXPAND fires.
     *
     * A quick PINCH_SELECT tap is single-frame and does NOT drive this
     * ring — there's nothing to visualize for a tap.
     */
    private val _reversePinchProgress = MutableStateFlow(0f)
    val reversePinchProgress: StateFlow<Float> = _reversePinchProgress.asStateFlow()

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

        // Attach to the DOMINANT (primary) hand per user feedback. This is
        // the same hand the OS uses for gaze+pinch, but PINCH_SELECT is
        // a no-op in the mapper so there's no double-fire: the custom
        // detector only meaningfully emits REVERSE_PINCH_EXPAND and
        // CLOSED_FIST_HOLD_COLLAPSE, neither of which the OS ever fires.
        // On UNKNOWN we attach to both so emulator testing still works.
        val attachLeft: Boolean
        val attachRight: Boolean
        when (primarySide) {
            Hand.HandSide.LEFT -> { attachLeft = true; attachRight = false }
            Hand.HandSide.RIGHT -> { attachLeft = false; attachRight = true }
            Hand.HandSide.UNKNOWN -> { attachLeft = true; attachRight = true }
        }

        val jobs = mutableListOf<Job>()

        if (attachLeft) {
            if (left == null) {
                XrLog.w(TAG, "wanted to attach to LEFT but Hand.left() returned null")
            } else {
                handStates["L"] = HandState("L")
                XrLog.i(TAG, "attaching custom-gesture collector -> LEFT hand (dominant)")
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
                XrLog.i(TAG, "attaching custom-gesture collector -> RIGHT hand (dominant)")
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
        _reversePinchProgress.value = 0f
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
        val thumbTipDistFromPalm = Vector3.distance(thumbTip.translation, palm.translation)
        val indexTipDistFromPalm = Vector3.distance(indexTip.translation, palm.translation)
        val middleTipDistFromPalm = Vector3.distance(middleTip.translation, palm.translation)
        val ringTipDistFromPalm = Vector3.distance(ringTip.translation, palm.translation)
        val littleTipDistFromPalm = Vector3.distance(littleTip.translation, palm.translation)

        // Compute the fist gate ONCE and share it between pinch + fist
        // detectors. The gate is intentionally strict (all four non-
        // thumb fingertips folded AND the thumb tucked), with hysteresis
        // once the user has committed — see the dedicated constants
        // above. A deliberate pinch does NOT satisfy this gate (middle/
        // ring/little stay clearly extended past the 4.5 cm fold
        // threshold during any real pinch pose), so this is a precise
        // "the hand is currently a fist" signal rather than the fuzzy
        // "trending fist-ward" heuristic that killed pinch responsiveness.
        val fistTrigger = indexTipDistFromPalm < CLOSED_FIST_FINGER_FOLD_MAX_M &&
            middleTipDistFromPalm < CLOSED_FIST_FINGER_FOLD_MAX_M &&
            ringTipDistFromPalm < CLOSED_FIST_FINGER_FOLD_MAX_M &&
            littleTipDistFromPalm < CLOSED_FIST_FINGER_FOLD_MAX_M &&
            thumbTipDistFromPalm < CLOSED_FIST_THUMB_FOLD_MAX_M
        val fistMaintain = indexTipDistFromPalm < CLOSED_FIST_MAINTAIN_FINGER_MAX_M &&
            middleTipDistFromPalm < CLOSED_FIST_MAINTAIN_FINGER_MAX_M &&
            ringTipDistFromPalm < CLOSED_FIST_MAINTAIN_FINGER_MAX_M &&
            littleTipDistFromPalm < CLOSED_FIST_MAINTAIN_FINGER_MAX_M &&
            thumbTipDistFromPalm < CLOSED_FIST_MAINTAIN_THUMB_MAX_M
        val fistNow = if (s.wasClosedFist) fistMaintain else fistTrigger

        // Pinch is only suppressed when the hand is CURRENTLY in a fist
        // pose. This is the gestural inverse of the pinch — if the user
        // committed to a fist, any pinch state mid-curl was transitional
        // not intentional, and we cancel it so the fist-then-release
        // doesn't fire a stale PINCH_SELECT that would expand the tier
        // right after CLOSED_FIST_HOLD_COLLAPSE collapses it.
        detectPinch(pinchDistance, suppressPinch = fistNow, now = now, s = s)
        // Reverse-pinch runs on the same thumb-index distance signal as
        // pinch but looks for the OPENING transition (compressed sample
        // in the recent past + current distance over SPREAD threshold).
        // Fires REVERSE_PINCH_EXPAND. Suppressed when the hand is in a
        // fist (we can't be opening FROM a pinch if we're curling INTO
        // a fist).
        detectReversePinch(
            distance = pinchDistance,
            suppressReverse = fistNow,
            now = now,
            s = s,
        )
        // Closed-fist detection runs in parallel with pinch using the
        // same fistNow signal. The two gestures can't collide because a
        // held pinch satisfies "thumb close to index" but NOT "all four
        // fingers tucked AND thumb tucked".
        detectClosedFistHold(
            fistTrigger = fistTrigger,
            fistNow = fistNow,
            thumbTipDistFromPalm = thumbTipDistFromPalm,
            indexTipDistFromPalm = indexTipDistFromPalm,
            middleTipDistFromPalm = middleTipDistFromPalm,
            ringTipDistFromPalm = ringTipDistFromPalm,
            littleTipDistFromPalm = littleTipDistFromPalm,
            now = now,
            s = s,
        )
        trackPalmForSwipe(palm.translation, now, s)
    }

    private fun detectPinch(distance: Float, suppressPinch: Boolean, now: Long, s: HandState) {
        val wasPinching = s.isPinching
        // Suppress pinch only when the hand is ACTUALLY a fist right now
        // (precise gate computed in processHandState, not the old fuzzy
        // "any finger close to palm" heuristic that false-fired on
        // normal pinches). Cancelling any in-flight pinch state here
        // stops the fist-then-release sequence from firing a stale
        // PINCH_SELECT on opening the hand after CLOSED_FIST_HOLD_COLLAPSE.
        if (suppressPinch) {
            if (wasPinching) {
                XrLog.v(TAG, "${s.label} pinch CANCELLED (hand became fist)")
            }
            s.isPinching = false
            s.pinchStartTimeMs = 0L
            s.pinchEmitted = false
            return
        }
        s.isPinching = distance < PINCH_DISTANCE_THRESHOLD

        if (s.isPinching && !wasPinching) {
            s.pinchStartTimeMs = now
            s.pinchEmitted = false
            XrLog.v(TAG, "${s.label} pinch START distance=${"%.3f".format(distance)}m")
        }

        if (!s.isPinching && wasPinching) {
            val held = now - s.pinchStartTimeMs
            if (!s.pinchEmitted) {
                if (held < PINCH_TAP_MIN_HOLD_MS) {
                    // Filter sub-80ms "ghost pinches" — tracking jitter,
                    // not user intent.
                    XrLog.v(TAG, "${s.label} pinch RELEASE held=${held}ms -> SUPPRESSED (below tap min)")
                } else {
                    XrLog.d(TAG, "${s.label} pinch RELEASE held=${held}ms -> PINCH_SELECT")
                    emit(Gesture.PINCH_SELECT, s)
                }
            }
        }
    }

    /**
     * Reverse-pinch (open-fingered expand) detector. Fires
     * [Gesture.REVERSE_PINCH_EXPAND] when the user's thumb-index distance
     * transitions from a compressed pose
     * (< [REVERSE_PINCH_COMPRESSED_M]) to a spread pose
     * (> [REVERSE_PINCH_SPREAD_M]) within [REVERSE_PINCH_WINDOW_MS].
     *
     * The compressed prerequisite is what makes this distinct from "the
     * hand is just relaxed with fingers naturally apart". Implemented by
     * keeping a short rolling history of thumb-index distances and
     * checking the min in the window — if the hand was ever compressed
     * in the last [REVERSE_PINCH_WINDOW_MS] and is currently spread,
     * fire.
     *
     * We set [HandState.pinchEmitted] after firing so the normal pinch-
     * release path in [detectPinch] doesn't also fire a stale
     * PINCH_SELECT on the way out of the compressed pose.
     */
    private fun detectReversePinch(
        distance: Float,
        suppressReverse: Boolean,
        now: Long,
        s: HandState,
    ) {
        if (suppressReverse) {
            if (_reversePinchProgress.value != 0f) _reversePinchProgress.value = 0f
            s.thumbIndexHistory.clear()
            return
        }

        // Push current sample; prune anything outside the window.
        s.thumbIndexHistory.add(now to distance)
        while (s.thumbIndexHistory.isNotEmpty() &&
            s.thumbIndexHistory.first().first < now - REVERSE_PINCH_WINDOW_MS
        ) {
            s.thumbIndexHistory.removeAt(0)
        }

        // Release lockout once the hand re-enters the compressed pose so
        // a second deliberate reverse-pinch is allowed.
        if (s.reversePinchEmitted && distance < REVERSE_PINCH_COMPRESSED_M) {
            s.reversePinchEmitted = false
            XrLog.v(TAG, "${s.label} reverse-pinch lockout released (hand re-compressed)")
        }

        if (s.reversePinchEmitted) {
            if (_reversePinchProgress.value != 0f) _reversePinchProgress.value = 0f
            return
        }

        val minInWindow = s.thumbIndexHistory.minOfOrNull { it.second } ?: distance
        if (minInWindow < REVERSE_PINCH_COMPRESSED_M) {
            val progress = ((distance - REVERSE_PINCH_COMPRESSED_M) /
                (REVERSE_PINCH_SPREAD_M - REVERSE_PINCH_COMPRESSED_M))
                .coerceIn(0f, 1f)
            _reversePinchProgress.value = progress

            if (distance > REVERSE_PINCH_SPREAD_M) {
                XrLog.d(
                    TAG,
                    "${s.label} reverse-pinch FIRED spread=${"%.3f".format(distance)}m " +
                        "minInWindow=${"%.3f".format(minInWindow)}m -> REVERSE_PINCH_EXPAND",
                )
                _reversePinchProgress.value = 1f
                emit(Gesture.REVERSE_PINCH_EXPAND, s)
                s.reversePinchEmitted = true
                // Suppress the stale PINCH_SELECT that would otherwise
                // fire when the thumb-index distance crosses back past
                // PINCH_DISTANCE_THRESHOLD on the way out.
                s.pinchEmitted = true
                s.thumbIndexHistory.clear()
            } else if (distance > REVERSE_PINCH_COMPRESSED_M &&
                now - s.lastReverseDiagLogMs > 500L
            ) {
                // Diagnostic for "near-spread but didn't fire" — helps
                // users calibrate the gesture without silently no-op'ing.
                s.lastReverseDiagLogMs = now
                XrLog.d(
                    TAG,
                    "${s.label} near-spread (NOT firing) " +
                        "distance=${"%.3f".format(distance)}m " +
                        "(need >${REVERSE_PINCH_SPREAD_M}m) " +
                        "minInWindow=${"%.3f".format(minInWindow)}m",
                )
            }
        } else {
            if (_reversePinchProgress.value != 0f) _reversePinchProgress.value = 0f
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
        fistTrigger: Boolean,
        fistNow: Boolean,
        thumbTipDistFromPalm: Float,
        indexTipDistFromPalm: Float,
        middleTipDistFromPalm: Float,
        ringTipDistFromPalm: Float,
        littleTipDistFromPalm: Float,
        now: Long,
        s: HandState,
    ) {
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
        if (!fistTrigger && nearFist && now - s.lastFistDiagLogMs > 500L) {
            s.lastFistDiagLogMs = now
            // d-level (was v-level) so it shows up on default logcat
            // filters. The fist gate is the most commonly-complained-
            // about detector and has to be easy to triage.
            XrLog.d(
                TAG,
                "${s.label} near-fist (NOT firing) " +
                    "fingers=${"%.3f".format(maxFingerDist)}m " +
                    "(need <${CLOSED_FIST_FINGER_FOLD_MAX_M}m) " +
                    "thumb=${"%.3f".format(thumbTipDistFromPalm)}m " +
                    "(need <${CLOSED_FIST_THUMB_FOLD_MAX_M}m)",
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
        // Throttle for the "near-fist (NOT firing)" diagnostic.
        var lastFistDiagLogMs = 0L
        // Throttle for the "near-spread (NOT firing)" diagnostic.
        var lastReverseDiagLogMs = 0L
        // Once REVERSE_PINCH_EXPAND fires, lock out until the hand
        // re-enters the compressed pose so a sustained open hand
        // doesn't re-fire every dedup window.
        var reversePinchEmitted = false
        // Rolling history of thumb-index distance samples within
        // REVERSE_PINCH_WINDOW_MS. Used by detectReversePinch to check
        // "was the hand compressed in the recent past".
        val thumbIndexHistory = mutableListOf<Pair<Long, Float>>()
        val palmPositionHistory = mutableListOf<Pair<Long, Vector3>>()

        fun reset() {
            isPinching = false
            pinchStartTimeMs = 0L
            pinchEmitted = false
            wasClosedFist = false
            closedFistStartTimeMs = 0L
            closedFistEmitted = false
            lastFistDiagLogMs = 0L
            lastReverseDiagLogMs = 0L
            reversePinchEmitted = false
            thumbIndexHistory.clear()
            palmPositionHistory.clear()
        }
    }

    companion object {
        private const val POSITION_HISTORY_MAX = 10
    }
}

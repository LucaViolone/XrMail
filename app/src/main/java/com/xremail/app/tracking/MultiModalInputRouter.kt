package com.xremail.app.tracking

import android.content.ContentResolver
import androidx.xr.runtime.Session
import kotlinx.coroutines.CoroutineScope

/**
 * Fuses system gaze (automatic hover), secondary-hand gestures, voice
 * commands (Gemini Live), and tilt input into a unified action stream.
 *
 * Gaze hover highlighting is handled entirely by the platform —
 * this router does NOT do custom gaze hit-testing.
 *
 * Call [startXrTracking] with a valid Session to activate all hardware
 * input subsystems. On emulator/2D, everything stays in simulated mode.
 */
class MultiModalInputRouter(
    val faceAttention: FaceAttentionTracker = FaceAttentionTracker(),
    val handGestures: SecondaryHandGestures = SecondaryHandGestures(),
    val tiltScroll: TiltScrollController = TiltScrollController(),
) {
    val sessionManager = XrSessionManager(faceAttention, handGestures, tiltScroll)

    fun startXrTracking(
        session: Session?,
        contentResolver: ContentResolver,
        scope: CoroutineScope,
    ) {
        sessionManager.startAll(session, contentResolver, scope)
    }

    fun stopXrTracking() {
        sessionManager.stopAll()
    }
}

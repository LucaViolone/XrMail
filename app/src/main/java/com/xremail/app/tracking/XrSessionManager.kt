package com.xremail.app.tracking

import android.content.ContentResolver
import android.util.Log
import androidx.xr.runtime.Session
import kotlinx.coroutines.CoroutineScope

private const val TAG = "XrSessionManager"

/**
 * Orchestrates XR session configuration and starts all tracking subsystems.
 * Call [startAll] once you have a valid XR Session (from LocalSession.current
 * or Session.create()). On emulator or non-XR devices where Session is null,
 * all trackers remain in their default/simulated state.
 */
class XrSessionManager(
    val faceTracker: FaceAttentionTracker,
    val handGestures: SecondaryHandGestures,
    val tiltScroll: TiltScrollController,
) {

    private var started = false

    fun startAll(
        session: Session?,
        contentResolver: ContentResolver,
        scope: CoroutineScope,
    ) {
        if (session == null) {
            Log.i(TAG, "No XR session available — running in 2D/emulator mode with simulated input")
            return
        }
        if (started) return
        started = true

        Log.i(TAG, "Starting XR tracking subsystems")

        try {
            handGestures.startTracking(session, contentResolver, scope)
        } catch (e: Exception) {
            Log.w(TAG, "Hand tracking unavailable: ${e.message}")
        }
        try {
            faceTracker.startTracking(session, scope)
        } catch (e: Exception) {
            Log.w(TAG, "Face tracking unavailable: ${e.message}")
        }
        try {
            tiltScroll.startTracking(session, scope)
        } catch (e: Exception) {
            Log.w(TAG, "Tilt tracking unavailable: ${e.message}")
        }
    }

    fun stopAll() {
        if (!started) return
        started = false

        handGestures.stopTracking()
        faceTracker.stopTracking()
        tiltScroll.stopTracking()
    }
}

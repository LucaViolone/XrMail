package com.xremail.app.tracking

import android.content.ContentResolver
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionConfigureSuccess
import com.xremail.app.util.XrLog
import kotlinx.coroutines.CoroutineScope

private const val TAG = "Session"

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
            XrLog.i(TAG, "No XR session available — running in 2D/emulator mode with simulated input")
            return
        }
        if (started) return
        started = true

        XrLog.i(TAG, "Starting XR tracking subsystems")

        // Be explicit that we need device pose. `FollowingSubspace` /
        // `FollowTarget.ArDevice` and the head-locked SpatialPanel both
        // depend on the device pose stream — if the OEM ever ships a
        // template config with `DeviceTrackingMode.DISABLED`, our lazy-follow
        // ambient HUD silently stops following. Setting LAST_KNOWN here
        // makes that contract explicit instead of relying on the default.
        try {
            val cfg = session.config.copy(deviceTracking = DeviceTrackingMode.LAST_KNOWN)
            val result = session.configure(cfg)
            if (result is SessionConfigureSuccess) {
                XrLog.i(TAG, "Device tracking explicitly set to LAST_KNOWN " +
                    "(required by FollowingSubspace + head-locked panels)")
            } else {
                XrLog.w(TAG, "Device tracking configure result: $result")
            }
        } catch (t: Throwable) {
            XrLog.w(TAG, "Could not enable DeviceTrackingMode.LAST_KNOWN", t)
        }

        try {
            handGestures.startTracking(session, contentResolver, scope)
        } catch (e: Exception) {
            XrLog.w(TAG, "Hand tracking unavailable: ${e.message}")
        }
        try {
            faceTracker.startTracking(session, scope)
        } catch (e: Exception) {
            XrLog.w(TAG, "Face tracking unavailable: ${e.message}")
        }
        try {
            tiltScroll.startTracking(session, scope)
        } catch (e: Exception) {
            XrLog.w(TAG, "Tilt tracking unavailable: ${e.message}")
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

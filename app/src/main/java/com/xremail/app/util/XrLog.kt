package com.xremail.app.util

import android.util.Log

/**
 * Centralized debug logging for the XR interaction stack. All interaction-tier,
 * gaze-dwell, head-anchor, gesture-routing, and voice-tier-transition logs go
 * through this so a single `adb logcat -s XrMail` session shows the full
 * timeline of multimodal input on a Galaxy XR device.
 *
 * Levels follow standard Android conventions but every tag is prefixed with
 * `XrMail/` so logcat filters work uniformly. Pass a `subsystem` to make the
 * source obvious in the stream:
 *
 *     XrLog.d("HandGestures", "primary=$primary, attachingTo=$secondary")
 *
 * To temporarily silence in release builds, flip [ENABLE_VERBOSE] to false.
 */
object XrLog {

    private const val ROOT = "XrMail"

    /**
     * When false, [v] becomes a no-op. [d]/[i]/[w]/[e] always log so production
     * crashes still leave a useful trail.
     */
    const val ENABLE_VERBOSE = true

    fun v(subsystem: String, msg: String) {
        if (ENABLE_VERBOSE) Log.v("$ROOT/$subsystem", msg)
    }

    fun d(subsystem: String, msg: String) {
        Log.d("$ROOT/$subsystem", msg)
    }

    fun i(subsystem: String, msg: String) {
        Log.i("$ROOT/$subsystem", msg)
    }

    fun w(subsystem: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.w("$ROOT/$subsystem", msg, t) else Log.w("$ROOT/$subsystem", msg)
    }

    fun e(subsystem: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e("$ROOT/$subsystem", msg, t) else Log.e("$ROOT/$subsystem", msg)
    }

    /**
     * Convenience for tier transitions — produces a uniform line like
     * `XrMail/Tier  AMBIENT_HUD -> NOTIFICATION_CARDS via gaze.dwell(420ms)`
     * so the chronological flow is trivial to scan.
     */
    fun tier(from: String, to: String, via: String) {
        Log.i("$ROOT/Tier", "$from -> $to via $via")
    }
}

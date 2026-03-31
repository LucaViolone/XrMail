package com.xremail.app.push

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * FCM push handler for Gmail notifications.
 * Replaces IMAP IDLE — eliminates battery drain entirely.
 *
 * Server-side setup: POST gmail.users.watch → Pub/Sub → FCM.
 * When a new email arrives, Google sends a push with the historyId.
 * This service enqueues a WorkManager job to fetch the delta.
 *
 * Production extends FirebaseMessagingService:
 * ```
 * class EmailXRMessagingService : FirebaseMessagingService() {
 *     override fun onMessageReceived(message: RemoteMessage) {
 *         val historyId = message.data["historyId"]?.toLongOrNull() ?: return
 *         WorkManager.getInstance(this).enqueueUniqueWork(
 *             "sync_delta",
 *             ExistingWorkPolicy.APPEND_OR_REPLACE,
 *             OneTimeWorkRequestBuilder<EmailSyncWorker>()
 *                 .setInputData(workDataOf("historyId" to historyId))
 *                 .build()
 *         )
 *     }
 * }
 * ```
 *
 * Phase 1 stub: placeholder service that compiles without Firebase dependency.
 */
class EmailXRMessagingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Phase 1 stub — no-op until Firebase is configured
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

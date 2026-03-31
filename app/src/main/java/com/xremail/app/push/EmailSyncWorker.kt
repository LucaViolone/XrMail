package com.xremail.app.push

import android.content.Context

/**
 * WorkManager worker for delta email sync triggered by FCM push.
 *
 * Production extends CoroutineWorker:
 * ```
 * class EmailSyncWorker(context: Context, params: WorkerParameters)
 *     : CoroutineWorker(context, params) {
 *
 *     override suspend fun doWork(): Result {
 *         val historyId = inputData.getLong("historyId", 0)
 *         val newEmails = gmailRepository.fetchDelta(historyId)
 *         val classified = emailClassifier.batchClassify(newEmails)
 *
 *         if (isInFullSpace()) {
 *             spatialNotifier.showPills(classified)
 *         } else {
 *             homeSpaceNotifier.notify(classified)
 *         }
 *         return Result.success()
 *     }
 * }
 * ```
 *
 * Phase 1 stub: structure only, no WorkManager dependency yet.
 */
class EmailSyncWorker(private val context: Context) {

    fun syncDelta(historyId: Long) {
        // Phase 1 stub — will be implemented with Gmail REST API
    }
}

package com.xremail.app.ui.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xremail.app.data.Email
import com.xremail.app.data.Priority

/**
 * Standard NotificationCompat fallback for Home Space.
 *
 * SpatialPanel and Orbiter-based notifications only work in Full Space.
 * When the user is in Home Space, we must use the standard Android
 * notification system — this is an entirely separate path from the
 * spatial notification pills.
 */
class HomeSpaceNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_HIGH = "email_high_priority"
        const val CHANNEL_NORMAL = "email_normal"
        const val CHANNEL_LOW = "email_low_priority"
    }

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_HIGH,
                    "High Priority Emails",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Urgent emails that need immediate attention"
                }
            )

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_NORMAL,
                    "Emails",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Standard email notifications"
                }
            )

            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_LOW,
                    "Low Priority",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Newsletters, promotions, and auto-archived emails"
                }
            )
        }
    }

    fun notify(email: Email) {
        val channel = when (email.priority) {
            Priority.HIGH -> CHANNEL_HIGH
            Priority.MEDIUM -> CHANNEL_NORMAL
            Priority.LOW, Priority.IGNORE -> CHANNEL_LOW
        }

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(email.sender)
            .setContentText(email.aiSummary)
            .setSubText(email.subject)
            .setAutoCancel(true)
            .setGroup("email_group")
            .build()

        try {
            NotificationManagerCompat.from(context).notify(email.id.hashCode(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS permission not granted — silently skip
        }
    }

    fun notifyBatch(emails: List<Email>) {
        emails.forEach { notify(it) }

        if (emails.size > 1) {
            val summary = NotificationCompat.Builder(context, CHANNEL_NORMAL)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("${emails.size} new emails")
                .setStyle(
                    NotificationCompat.InboxStyle()
                        .also { style ->
                            emails.take(5).forEach { email ->
                                style.addLine("${email.sender}: ${email.subject}")
                            }
                        }
                        .setSummaryText("${emails.size} new emails")
                )
                .setGroup("email_group")
                .setGroupSummary(true)
                .setAutoCancel(true)
                .build()

            try {
                NotificationManagerCompat.from(context).notify(0, summary)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS permission not granted
            }
        }
    }
}

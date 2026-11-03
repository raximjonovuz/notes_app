package com.example.notes.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.notes.MainActivity
import com.example.notes.R
import com.example.notes.util.Logger
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Configuration for the daily reminder notification.
 * To disable tester reminders when releasing to production, simply set [isReminderEnabled] to false.
 */
object ReminderConfig {
    var isReminderEnabled: Boolean = true
}

/**
 * Worker that sends a daily reminder notification at 20:00 (8:00 PM)
 * to remind testers/users to open the app, test features, and record thoughts.
 */
class DailyReminderWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!ReminderConfig.isReminderEnabled) {
            Logger.d(TAG, "Daily reminder is disabled via ReminderConfig.")
            return Result.success()
        }

        try {
            sendNotification(appContext)
            Logger.d(TAG, "Daily reminder notification sent successfully.")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to send daily reminder notification: ${e.message}")
        }

        return Result.success()
    }

    private fun sendNotification(context: Context) {
        createNotificationChannel(context)

        // Alternating reminder messages so it stays fresh and engaging
        val dayIndex = (System.currentTimeMillis() / (1000 * 60 * 60 * 24)).toInt()
        val messages = listOf(
            Pair(
                "Smart Notes 📝",
                "Bugun biror eslatma yozdingizmi? Ilovaga kirib sinab ko'ring va yangi fikrlaringizni saqlang!"
            ),
            Pair(
                "Kechki qaydlar ✍️",
                "Bugungi muhim ishlaringiz va ertangi rejalaringizni Smart Notes'da belgilab qo'ying."
            ),
            Pair(
                "Fikrlaringizni saqlang 💡",
                "Kun davomidagi ajoyib g'oyalaringiz yodingizdan ko'tarilmasin — hoziroq qayd eting!"
            )
        )
        val selected = messages[Math.abs(dayIndex) % messages.size]

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(selected.first)
            .setContentText(selected.second)
            .setStyle(NotificationCompat.BigTextStyle().bigText(selected.second))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = NotificationManagerCompat.from(context)
        if (notificationManager.areNotificationsEnabled()) {
            try {
                notificationManager.notify(NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                Logger.w(TAG, "Missing notification permission: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Kunlik eslatmalar"
            val descriptionText = "Testerlar va foydalanuvchilar uchun kunlik faollik eslatmalari"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "DailyReminderWorker"
        const val WORK_NAME = "daily_tester_reminder_work"
        const val CHANNEL_ID = "notes_daily_reminder_channel"
        const val NOTIFICATION_ID = 2001

        /**
         * Schedules the daily reminder to fire at 20:00 (8:00 PM) every day.
         */
        fun schedule(context: Context) {
            if (!ReminderConfig.isReminderEnabled) return

            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 20)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val initialDelay = target.timeInMillis - now.timeInMillis

            val workRequest = PeriodicWorkRequestBuilder<DailyReminderWorker>(
                24, TimeUnit.HOURS
            )
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
            Logger.d(TAG, "Scheduled daily reminder for 20:00 with initial delay: ${initialDelay / 1000}s")
        }
    }
}

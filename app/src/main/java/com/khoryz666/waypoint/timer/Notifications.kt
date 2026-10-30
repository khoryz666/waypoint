package com.khoryz666.waypoint.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.khoryz666.waypoint.MainActivity
import com.khoryz666.waypoint.R
import com.khoryz666.waypoint.data.Session
import com.khoryz666.waypoint.domain.Lang

/** Where a notification tap lands in the app. */
enum class Destination { TODAY, INPUT, DAILY_CLOSE, REVIEW, SLEEP_INPUT, SETTINGS }

/**
 * Every notification the app posts: the persistent timer, the "做了什么?" prompt, the idle nudge
 * and the reminders (daily close, sleep screenshot, weekly review, backup). Texts follow the
 * profile language; [lang] is read when each one is posted.
 */
class Notifications(private val context: Context, private val lang: () -> Lang) : TimerPrompts {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        val l = lang()
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(TIMER, l.t("Timer", "计时"), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
                NotificationChannel(PROMPTS, l.t("What did you do?", "做了什么?"), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(NUDGES, l.t("Nudges", "提醒"), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(REMINDERS, l.t("Reminders", "定时提醒"), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    fun timer(content: NotificationContent): Notification {
        val builder = NotificationCompat.Builder(context, TIMER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subText)
            .setContentIntent(open(Destination.TODAY))
            // Android 14+ lets users swipe this away; the service puts it straight back.
            .setDeleteIntent(TimerService.showIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (content.runningSince != null) {
            builder.setCategory(NotificationCompat.CATEGORY_STOPWATCH).setWhen(content.runningSince).setShowWhen(true).setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
        }
        // Standard actions stop at three, so the expanded view draws its own row of buttons.
        if (content.actions.isNotEmpty()) builder.setStyle(NotificationCompat.DecoratedCustomViewStyle()).setCustomBigContentView(expanded(content))
        return builder.build()
    }

    private fun expanded(content: NotificationContent): RemoteViews {
        val view = RemoteViews(context.packageName, R.layout.notification_expanded)
        view.setTextViewText(R.id.lines, content.text.orEmpty())
        content.actions.chunked(BUTTONS_PER_ROW).forEach { actions ->
            val row = RemoteViews(context.packageName, R.layout.notification_button_row)
            repeat(BUTTONS_PER_ROW) { index ->
                val button = RemoteViews(context.packageName, R.layout.notification_button)
                val action = actions.getOrNull(index)
                if (action == null) {
                    button.setViewVisibility(R.id.button, View.INVISIBLE)
                } else {
                    button.setTextViewText(R.id.button, action.label)
                    button.setOnClickPendingIntent(R.id.button, TimerService.actionIntent(context, action))
                }
                row.addView(R.id.row, button)
            }
            view.addView(R.id.buttons, row)
        }
        return view
    }

    fun postTimer(content: NotificationContent) = manager.notify(TIMER_ID, timer(content))

    override fun askWhatWasDone(session: Session, trackTitle: String) {
        val l = lang()
        val intent = open(Destination.INPUT, session.trackId, session.id, requestCode = 100)
        manager.notify(
            ASK_ID,
            NotificationCompat.Builder(context, PROMPTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(l.t("What did you do?", "做了什么?"))
                .setContentText(trackTitle)
                .setContentIntent(intent)
                .addAction(0, l.t("Log it", "记录"), intent)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun nudge() = post(NUDGE_ID, NUDGES, lang().t("Nothing logged for 3 hours", "3 小时没有记录"), lang().t("A 25-minute session still counts.", "25 分钟也算数。"), Destination.TODAY)

    fun dailyClose(summary: String) = post(DAILY_ID, REMINDERS, lang().t("Today", "今天"), summary, Destination.DAILY_CLOSE)

    fun sleepReminder() = post(SLEEP_ID, REMINDERS, lang().t("Share last night's sleep screenshot", "分享昨晚的睡眠截图"), null, Destination.SLEEP_INPUT)

    fun weeklyReview(late: Boolean) = post(
        REVIEW_ID, REMINDERS,
        if (late) lang().t("Last week isn't reviewed yet", "上周未复盘") else lang().t("Weekly review", "每周复盘"),
        lang().t("The numbers are filled in; it needs your judgment.", "数字已经填好，只差你的判断。"), Destination.REVIEW,
    )

    fun periodReview(title: String) = post(REVIEW_ID + 1, REMINDERS, title, null, Destination.REVIEW)

    fun backupFailed(message: String) = post(BACKUP_ID, REMINDERS, lang().t("Backup failed", "备份失败"), message, Destination.SETTINGS)

    fun cancelAsk() = manager.cancel(ASK_ID)

    private fun post(id: Int, channel: String, title: String, text: String?, destination: Destination) {
        manager.notify(
            id,
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(text?.let { NotificationCompat.BigTextStyle().bigText(it) })
                .setContentIntent(open(destination, requestCode = id))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun open(destination: Destination, trackId: Long? = null, sessionId: Long? = null, requestCode: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_DESTINATION, destination.name)
        trackId?.let { intent.putExtra(EXTRA_TRACK, it) }
        sessionId?.let { intent.putExtra(EXTRA_SESSION, it) }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val TIMER_ID = 1
        private const val ASK_ID = 2
        private const val NUDGE_ID = 3
        private const val DAILY_ID = 4
        private const val SLEEP_ID = 5
        private const val REVIEW_ID = 6
        private const val BACKUP_ID = 8
        private const val BUTTONS_PER_ROW = 3
        private const val TIMER = "timer"
        private const val PROMPTS = "prompts"
        private const val NUDGES = "nudges"
        private const val REMINDERS = "reminders"
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_TRACK = "trackId"
        const val EXTRA_SESSION = "sessionId"
    }
}

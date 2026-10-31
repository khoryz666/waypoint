package com.khoryz666.waypoint.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.khoryz666.waypoint.AppContainer
import com.khoryz666.waypoint.appContainer
import com.khoryz666.waypoint.data.Settings
import com.khoryz666.waypoint.domain.AppClock
import com.khoryz666.waypoint.domain.formatDuration
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** The app's fixed-time notifications (spec 6.2, 7.1-7.3). None of them involve the LLM. */
enum class Reminder {
    DAILY_CLOSE, SLEEP, WEEKLY_REVIEW, LATE_REVIEW, MONTHLY_REVIEW, YEARLY_REVIEW;

    /** The next time this reminder fires after [now]. */
    fun next(now: Long, s: Settings, clock: AppClock): Long {
        val today = clock.today()
        fun firstAfter(dates: Sequence<LocalDate>, time: LocalTime) = dates.map { clock.at(it, time) }.first { it > now }
        val days = generateSequence(today) { it.plusDays(1) }
        return when (this) {
            DAILY_CLOSE -> firstAfter(days, s.dailyCloseTime)
            SLEEP -> firstAfter(days, LocalTime.NOON)
            WEEKLY_REVIEW -> firstAfter(days.filter { it.dayOfWeek == s.weeklyReviewDay }, s.weeklyReviewTime)
            LATE_REVIEW -> firstAfter(days.filter { it.dayOfWeek == s.weeklyReviewDay.plus(1) }, LocalTime.NOON)
            MONTHLY_REVIEW -> firstAfter(days.filter { it.dayOfMonth == 1 }, LocalTime.of(20, 0))
            YEARLY_REVIEW -> firstAfter(days.filter { it.dayOfYear == 1 }, LocalTime.of(20, 0))
        }
    }
}

/** Fires one [Reminder] and schedules its next occurrence. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.appContainer
        val reminder = inputData.getString(KEY)?.let { runCatching { Reminder.valueOf(it) }.getOrNull() } ?: return Result.success()
        if (c.settings.current().onboarded) runCatching { fire(c, reminder) }
        schedule(applicationContext, c, reminder)
        return Result.success()
    }

    private suspend fun fire(c: AppContainer, reminder: Reminder) {
        val lang = c.settings.current().profile.language
        when (reminder) {
            Reminder.DAILY_CLOSE -> {
                c.notifications.dailyClose(dailySummary(c))
                runCatching { c.commits.sync() }
                c.commits.logDay(c.clock.today())
            }
            // Last night's sleep has today as its wake date; a weekly screenshot covering it suppresses this.
            Reminder.SLEEP -> if (c.db.sleep().onDates(listOf(c.clock.today())).isEmpty()) c.notifications.sleepReminder()
            Reminder.WEEKLY_REVIEW -> {
                c.reviews.prepare(com.khoryz666.waypoint.domain.ReviewPeriod.WEEK, c.reviews.currentWeekStart())
                c.notifications.weeklyReview(late = false)
            }
            Reminder.LATE_REVIEW -> if (c.reviews.lastWeekUnreviewed()) c.notifications.weeklyReview(late = true)
            Reminder.MONTHLY_REVIEW -> c.notifications.periodReview(lang.t("Monthly review", "月度复盘"))
            Reminder.YEARLY_REVIEW -> c.notifications.periodReview(lang.t("Yearly review", "年度复盘"))
        }
    }

    companion object {
        private const val KEY = "reminder"

        suspend fun scheduleAll(context: Context, c: AppContainer) {
            Reminder.entries.forEach { schedule(context, c, it) }
        }

        private suspend fun schedule(context: Context, c: AppContainer, reminder: Reminder) {
            val s = c.settings.current()
            val delay = (reminder.next(c.clock.now(), s, c.clock) - c.clock.now()).coerceAtLeast(0)
            WorkManager.getInstance(context).enqueueUniqueWork(
                "reminder-${reminder.name}", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(workDataOf(KEY to reminder.name)).build(),
            )
        }

        /** "今天: focus 2h 03m · minimum ✓ · 1 章 · 睡眠已记录" (spec 7.1). */
        suspend fun dailySummary(c: AppContainer): String {
            val lang = c.settings.current().profile.language
            val day = c.stats.dayOn(c.clock.today())
            val units = day.progress.unitsDone.values.sum()
            val slept = c.db.sleep().onDates(listOf(c.clock.today())).isNotEmpty()
            return listOfNotNull(
                lang.t("focus ", "专注 ") + formatDuration(day.time.totalFocusMs),
                day.minimum.takeIf { it.lines.isNotEmpty() }?.let { lang.t("at least: ", "至少: ") + if (it.met) "✓" else "✗" },
                lang.t("$units steps", "$units 步").takeIf { units > 0 },
                if (slept) lang.t("sleep logged", "睡眠已记录") else lang.t("no sleep record", "睡眠未记录"),
            ).joinToString(" · ")
        }
    }
}

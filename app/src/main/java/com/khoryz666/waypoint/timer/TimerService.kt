package com.khoryz666.waypoint.timer

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.khoryz666.waypoint.appContainer
import com.khoryz666.waypoint.domain.shouldNudge
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the timer notification up all day and handles its quick actions (ported from v2). It
 * holds no state of its own; a restart (START_STICKY, boot, update) just re-reads the database,
 * so being killed never loses time. It also checks the idle nudge every few minutes.
 */
class TimerService : Service() {
    private val scope = MainScope()
    private val container by lazy { appContainer }
    private var content = NotificationContent.Loading

    override fun onCreate() {
        super.onCreate()
        container.notifications.createChannels()
        scope.launch {
            combine(
                container.stats.today(TICK_MS),
                container.db.sessions().observeRecentFocusTracks(3),
                container.db.tracks().observeAll(),
                container.settings.settings,
            ) { status, recent, tracks, s -> NotificationContent.of(status, recent, tracks, s.profile.language) }
                .distinctUntilChanged()
                .collect {
                    content = it
                    container.notifications.postTimer(it)
                }
        }
        scope.launch { container.clock.ticks(NUDGE_CHECK_MS).collect { checkNudge() } }
    }

    private suspend fun checkNudge() {
        val c = container
        val s = c.settings.current()
        val now = c.clock.now()
        val status = c.stats.todayOnce()
        val lastActivity = listOfNotNull(c.db.sessions().lastActivity(now), c.db.logs().lastCreatedAt()).maxOrNull()
        if (shouldNudge(now, lastActivity, status.running != null, status.dayType?.nudges == true, s.waking, s.lastNudgeAt, c.clock)) {
            c.notifications.nudge()
            c.settings.update { it.copy(lastNudgeAt = now) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_FOCUS -> intent.getLongExtra(EXTRA_TRACK, -1).takeIf { it >= 0 }?.let { id -> scope.launch { container.tracker.switchTo(id) } }
            ACTION_STOP -> scope.launch { container.tracker.stop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    @SuppressLint("InlinedApi") // ServiceCompat ignores the service type below API 34.
    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(this, Notifications.TIMER_ID, container.notifications.timer(content), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        true
    } catch (e: IllegalStateException) {
        // Background-start restrictions (Android 12+); the app or the next boot starts us again.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
            stopSelf()
            false
        } else {
            throw e
        }
    }

    companion object {
        private const val TICK_MS = 30_000L
        private const val NUDGE_CHECK_MS = 5 * 60_000L
        private const val ACTION_SHOW = "show"
        private const val ACTION_FOCUS = "focus"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_TRACK = "trackId"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TimerService::class.java))
        }

        internal fun showIntent(context: Context): PendingIntent = pending(context, 0, Intent(ACTION_SHOW))

        // Distinct request codes, so each intent keeps its own extras.
        internal fun actionIntent(context: Context, action: QuickAction): PendingIntent = when (action) {
            is QuickAction.Stop -> pending(context, 1, Intent(ACTION_STOP))
            is QuickAction.Switch -> pending(context, 1000 + action.trackId.toInt(), Intent(ACTION_FOCUS).putExtra(EXTRA_TRACK, action.trackId))
        }

        private fun pending(context: Context, requestCode: Int, intent: Intent): PendingIntent =
            PendingIntent.getService(context, requestCode, intent.setClass(context, TimerService::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

package com.khoryz666.waypoint

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.InvalidationTracker
import com.khoryz666.waypoint.backup.BackupManager
import com.khoryz666.waypoint.backup.DriveClient
import com.khoryz666.waypoint.backup.GoogleAuth
import com.khoryz666.waypoint.data.AppDatabase
import com.khoryz666.waypoint.data.ReviewRepository
import com.khoryz666.waypoint.github.CommitSync
import com.khoryz666.waypoint.github.GitHubClient
import com.khoryz666.waypoint.work.BackupWorker
import com.khoryz666.waypoint.data.SecretStore
import com.khoryz666.waypoint.data.SettingsStore
import com.khoryz666.waypoint.data.StatsRepository
import com.khoryz666.waypoint.data.TrackRepository
import com.khoryz666.waypoint.domain.AppClock
import com.khoryz666.waypoint.domain.Lang
import com.khoryz666.waypoint.ingest.FeedbackEngine
import com.khoryz666.waypoint.ingest.ImageStore
import com.khoryz666.waypoint.ingest.InputRepository
import com.khoryz666.waypoint.ingest.Parser
import com.khoryz666.waypoint.ingest.ReceiptBuilder
import com.khoryz666.waypoint.llm.ContextBuilder
import com.khoryz666.waypoint.llm.DeepSeekClient
import com.khoryz666.waypoint.llm.LlmService
import com.khoryz666.waypoint.llm.PromptBuilder
import com.khoryz666.waypoint.timer.Notifications
import com.khoryz666.waypoint.timer.Tracker
import com.khoryz666.waypoint.work.ParseWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.ZoneId

private val Context.settingsDataStore by preferencesDataStore("settings")

/**
 * The device clock. Day boundaries use the profile's time zone override when set, otherwise the
 * device's current zone (spec 10.1: timestamps are UTC instants; boundaries follow the zone).
 */
class DeviceClock : AppClock {
    @Volatile var zoneOverride: ZoneId? = null
    override fun now(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = zoneOverride ?: ZoneId.systemDefault()
}

/** The app's long-lived objects, created on first use. The only place dependencies are wired (spec 2). */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    /** Outlives screens: follow-up LLM calls and background work started from the UI. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val clock = DeviceClock()
    val db by lazy { AppDatabase.build(app) }
    val settings by lazy { SettingsStore(app.settingsDataStore, SecretStore()) }

    /** The profile language, read synchronously by notifications. */
    @Volatile var lang: Lang = Lang.EN
        private set

    val prompts by lazy { PromptBuilder { name -> app.assets.open("prompts/$name.md").bufferedReader().use { it.readText() } } }
    val contextBuilder by lazy { ContextBuilder(prompts, clock) }
    val deepSeek by lazy { DeepSeekClient({ settings.apiKey() }) }
    val llm by lazy { LlmService(deepSeek, prompts, settings, db.usage(), clock) }

    val tracks by lazy { TrackRepository(db, clock) }
    val stats by lazy { StatsRepository(db, settings, clock) }
    val images by lazy { ImageStore(app) }
    val parseScheduler by lazy { ParseWorker.scheduler(app) }
    val input by lazy { InputRepository(db, images, settings, parseScheduler, clock) }
    val parser by lazy { Parser(db, llm, contextBuilder, settings, images, clock) }
    val receipts by lazy { ReceiptBuilder(db, stats, settings, clock) }
    val feedback by lazy { FeedbackEngine(db, llm, contextBuilder, receipts, settings, clock, scope) }

    val reviews by lazy { ReviewRepository(db, settings, llm, clock) }
    val github by lazy { GitHubClient({ settings.githubToken() }) }
    val commits by lazy { CommitSync(db, github, settings, llm, clock) }
    val googleAuth by lazy { GoogleAuth(app) }
    val backup by lazy { BackupManager(db, DriveClient(), settings, clock, BuildConfig.VERSION_NAME) }

    val notifications by lazy { Notifications(app) { lang } }
    val tracker by lazy { Tracker(db, clock, notifications) }

    init {
        // Seed the zone and language before anything reads them, then follow changes.
        runBlocking { settings.settings.first() }.let { s -> clock.zoneOverride = s.profile.timezoneOverride; lang = s.profile.language }
        // Every write pushes the debounced backup 10 minutes out (spec 9.3), once a passphrase exists.
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(arrayOf("tracks", "units", "logs", "sessions", "sleep_records", "balance_events", "reviews", "day_types", "pillars", "checklist_questions")) {
            override fun onInvalidated(tables: Set<String>) {
                scope.launch { if (settings.current().backup.hasKey) BackupWorker.afterWrite(app) }
            }
        })
        scope.launch {
            settings.settings.collect { s ->
                clock.zoneOverride = s.profile.timezoneOverride
                lang = s.profile.language
            }
        }
    }

    /** (Re)arms every periodic and scheduled job; safe to call repeatedly. */
    fun scheduleWork() {
        parseScheduler.schedule()
        BackupWorker.scheduleDaily(app)
        com.khoryz666.waypoint.work.CommitSyncWorker.schedule(app)
        scope.launch { com.khoryz666.waypoint.work.ReminderWorker.scheduleAll(app, this@AppContainer) }
    }
}

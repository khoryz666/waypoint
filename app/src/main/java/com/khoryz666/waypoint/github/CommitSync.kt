package com.khoryz666.waypoint.github

import com.khoryz666.waypoint.data.AppDatabase
import com.khoryz666.waypoint.data.Feedback
import com.khoryz666.waypoint.data.GitCommit
import com.khoryz666.waypoint.data.LogEntry
import com.khoryz666.waypoint.data.SettingsStore
import com.khoryz666.waypoint.domain.AppClock
import com.khoryz666.waypoint.domain.DAY_MS
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.domain.LogKind
import com.khoryz666.waypoint.domain.ParseStatus
import com.khoryz666.waypoint.domain.RecallSource
import com.khoryz666.waypoint.domain.Structured
import com.khoryz666.waypoint.domain.TrackStatus
import com.khoryz666.waypoint.llm.LlmService
import com.khoryz666.waypoint.llm.Part
import com.khoryz666.waypoint.llm.QuestionsOutput
import java.time.Instant
import java.time.LocalDate

/**
 * Pulls the user's commits for every project track (spec 6.4), folds a day's commits into one
 * COMMITS log at the daily close, and offers at most one ownership check per day.
 */
class CommitSync(
    private val db: AppDatabase,
    private val github: GitHubClient,
    private val settings: SettingsStore,
    private val llm: LlmService,
    private val clock: AppClock,
) {
    /** Syncs every project track with repos. A failing track records its status; others carry on. */
    suspend fun sync() {
        val s = settings.current()
        if (!s.hasGithubToken) return
        val login = s.githubLogin ?: runCatching { github.login() }.getOrNull()?.also { l -> settings.update { it.copy(githubLogin = l) } } ?: return
        db.tracks().all().filter { it.domain == Domain.PROJECT && it.githubRepos.isNotEmpty() && (it.status == TrackStatus.ACTIVE || it.status == TrackStatus.PAUSED) }.forEach { track ->
            val since = Instant.ofEpochMilli((track.lastSyncAt ?: track.createdAt) - DAY_MS)
            val started = clock.now()
            try {
                track.githubRepos.forEach { repo ->
                    val commits = github.commits(repo, login, since)
                    db.commits().insertAll(commits.map { GitCommit(it.sha, track.id, repo, it.committedAt, it.message.lineSequence().first().take(300)) })
                }
                db.tracks().get(track.id)?.let { db.tracks().update(it.copy(lastSyncAt = started, syncError = null)) }
            } catch (e: GitHubException) {
                db.tracks().get(track.id)?.let { db.tracks().update(it.copy(syncError = if (e.code > 0) "HTTP ${e.code}" else e.message)) }
            }
        }
    }

    /** At the daily close: one COMMITS log per project track for [date], with a Flash summary. */
    suspend fun logDay(date: LocalDate) {
        val day = clock.dayBounds(date)
        db.tracks().all().filter { it.domain == Domain.PROJECT }.forEach { track ->
            val commits = db.commits().forTrackBetween(track.id, day.start, day.end).filter { it.logId == null }
            if (commits.isEmpty()) return@forEach
            val summary = runCatching {
                llm.text("commit_summary", listOf(Part.Text("Project: ${track.title}\nCommits on $date:\n" + commits.joinToString("\n") { "- ${it.message}" })), 200).trim()
            }.getOrDefault("")
            val structured = Structured.Commits(date.toString(), commits.map { it.sha }, commits.map { it.message }, summary)
            val now = clock.now()
            val id = db.logs().insert(
                LogEntry(trackId = track.id, kind = LogKind.COMMITS, occurredAt = day.end - 1, createdAt = now, rawText = summary.ifEmpty { null }, structured = structured, parseStatus = ParseStatus.CONFIRMED),
            )
            db.commits().markLogged(commits.map { it.sha }, id)
            if (summary.isNotEmpty()) db.feedback().insert(Feedback(logId = id, trackId = track.id, text = summary, model = settings.current().fastModel, promptName = "commit_summary", createdAt = now))
        }
    }

    /**
     * "Can I explain it without AI?" (spec 6.4): once per day at most, when a project track has
     * commits today, a question from one random commit message waits on Today.
     */
    suspend fun ensureOwnershipCheck() {
        val today = clock.dayBounds(clock.today())
        val already = db.logs().recallsSince(today.start).any { (it.structured as? Structured.Recall)?.source == RecallSource.OWNERSHIP }
        if (already) return
        val commit = db.commits().between(today.start, today.end).randomOrNull() ?: return
        val track = db.tracks().get(commit.trackId) ?: return
        val question = runCatching {
            llm.json("recall_question", listOf(Part.Text("Ownership check\nProject: ${track.title}\nCommit message: ${commit.message}")), 300, QuestionsOutput.serializer()).questions.firstOrNull()
        }.getOrNull() ?: return
        val now = clock.now()
        db.logs().insert(
            LogEntry(trackId = track.id, kind = LogKind.RECALL, occurredAt = now, createdAt = now, structured = Structured.Recall(RecallSource.OWNERSHIP, question, commitSha = commit.sha), parseStatus = ParseStatus.CONFIRMED),
        )
    }
}

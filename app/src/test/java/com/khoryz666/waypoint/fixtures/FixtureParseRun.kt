package com.khoryz666.waypoint.fixtures

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.khoryz666.waypoint.data.AppDatabase
import com.khoryz666.waypoint.data.LogEntry
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.domain.FixedClock
import com.khoryz666.waypoint.domain.Lang
import com.khoryz666.waypoint.domain.Structured
import com.khoryz666.waypoint.domain.TrackStatus
import com.khoryz666.waypoint.domain.WaypointJson
import com.khoryz666.waypoint.fakes.FakeUsageDao
import com.khoryz666.waypoint.fakes.testSettings
import com.khoryz666.waypoint.ingest.ImageStore
import com.khoryz666.waypoint.ingest.ParseOutcome
import com.khoryz666.waypoint.ingest.Parser
import com.khoryz666.waypoint.llm.ContextBuilder
import com.khoryz666.waypoint.llm.DeepSeekClient
import com.khoryz666.waypoint.llm.LlmService
import com.khoryz666.waypoint.llm.PromptBuilder
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.minutes

/**
 * Manual check of parsing quality against the real DeepSeek API (spec 10.3). Skipped unless
 * run with -Dfixtures=true and DEEPSEEK_API_KEY set; costs tokens, so never part of CI.
 * Inputs and the report live in the repo-root `fixtures/` folder (see fixtures/README.md).
 */
// Native graphics so ImageStore really decodes and downscales the screenshots, as on the phone.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class FixtureParseRun {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val root = File("../fixtures")

    @After fun tearDown() = db.close()

    @Test
    fun `parse every fixture with the real API`() = runTest(timeout = 30.minutes) {
        val key = System.getenv("DEEPSEEK_API_KEY").orEmpty()
        assumeTrue("run with -Dfixtures=true and DEEPSEEK_API_KEY", System.getProperty("fixtures") == "true" && key.isNotBlank())
        val clock = FixedClock(LocalDateTime.now(), java.time.ZoneId.systemDefault())
        val settings = testSettings(backgroundScope, tmp.root)
        settings.setApiKey(key)
        settings.update { s ->
            s.copy(
                fastModel = System.getenv("DEEPSEEK_FAST_MODEL") ?: s.fastModel,
                profile = s.profile.copy(
                    language = Lang.of(System.getenv("FIXTURE_LANG") ?: "zh-CN"),
                    currency = System.getenv("FIXTURE_CURRENCY") ?: s.profile.currency,
                    sleepAppHint = File(root, "sleep-app.txt").takeIf { it.exists() }?.readText()?.trim(),
                ),
            )
        }
        val prompts = PromptBuilder { File("src/main/assets/prompts/$it.md").readText() }
        val llm = LlmService(DeepSeekClient({ settings.apiKey() }), prompts, settings, FakeUsageDao(), clock)
        val images = ImageStore(context)
        val parser = Parser(db, llm, ContextBuilder(prompts, clock), settings, images, clock)
        val tracks = loadTracks()

        val report = StringBuilder("# Fixture parse run · ${LocalDateTime.now().withNano(0)}\n\n")
        report.append("Tracks: ").append(tracks.joinToString { "${it.title} (${it.domain})" }.ifEmpty { "none" }).append("\n")

        val sleep = File(root, "sleep").listFiles { f -> f.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") }.orEmpty().sortedBy { it.name }
        report.append("\n## Sleep screenshots (${sleep.size})\n")
        sleep.forEach { file -> report.append(run(parser, file.name, LogEntry(occurredAt = clock.now(), createdAt = clock.now(), rawImageUris = listOfNotNull(images.import(Uri.fromFile(file.absoluteFile)))))) }

        val texts = File(root, "logs.txt").takeIf { it.exists() }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") }.orEmpty()
        report.append("\n## Text logs (${texts.size})\n")
        texts.forEach { text -> report.append(run(parser, text, LogEntry(occurredAt = clock.now(), createdAt = clock.now(), rawText = text))) }

        File(root, "out").mkdirs()
        File(root, "out/results.md").writeText(report.toString())
        println(report)
    }

    private suspend fun run(parser: Parser, label: String, entry: LogEntry): String {
        val id = db.logs().insert(entry)
        val started = System.currentTimeMillis()
        val outcome = parser.parse(db.logs().get(id)!!)
        val log = db.logs().get(id)!!
        val track = log.trackId?.let { db.tracks().get(it)?.title }
        val body = log.structured?.let { WaypointJson.encodeToString(Structured.serializer(), it) } ?: "—"
        return buildString {
            append("\n### $label\n")
            append("- outcome: $outcome · ${System.currentTimeMillis() - started} ms\n")
            if (entry.rawImageUris.isNotEmpty()) append("- image sent: ${entry.rawImageUris.joinToString { "${java.io.File(it).length() / 1024} KB" }}\n")
            append("- track: ${track ?: "—"}").append(log.confidence?.let { " (confidence $it)" } ?: "").append("\n")
            log.parseError?.let { append("- error: $it\n") }
            if (outcome == ParseOutcome.PAUSED) append("- the API refused the key or the balance is empty; check DEEPSEEK_API_KEY\n")
            append("```json\n$body\n```\n")
        }
    }

    /** `fixtures/tracks.txt`: one `DOMAIN | Title | Goal` per line, so text logs have tracks to route to. */
    private suspend fun loadTracks(): List<Track> =
        File(root, "tracks.txt").takeIf { it.exists() }?.readLines().orEmpty()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split('|').map { it.trim() }
                val domain = runCatching { Domain.valueOf(parts[0].uppercase()) }.getOrNull() ?: return@mapNotNull null
                val track = Track(domain = domain, title = parts.getOrElse(1) { domain.name }, goal = parts.getOrElse(2) { "" }, status = TrackStatus.ACTIVE, createdAt = 0, updatedAt = 0)
                track.copy(id = db.tracks().insert(track))
            }
}

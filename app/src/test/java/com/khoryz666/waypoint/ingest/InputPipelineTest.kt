package com.khoryz666.waypoint.ingest

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.khoryz666.waypoint.data.AppDatabase
import com.khoryz666.waypoint.data.SettingsStore
import com.khoryz666.waypoint.data.SleepRecord
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.data.TrackUnit
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.domain.FixedClock
import com.khoryz666.waypoint.domain.LogKind
import com.khoryz666.waypoint.domain.Night
import com.khoryz666.waypoint.domain.ParseStatus
import com.khoryz666.waypoint.domain.SleepSource
import com.khoryz666.waypoint.domain.Structured
import com.khoryz666.waypoint.domain.TrackStatus
import com.khoryz666.waypoint.domain.UnitStatus
import com.khoryz666.waypoint.domain.balanceAt
import com.khoryz666.waypoint.fakes.testSettings
import com.khoryz666.waypoint.llm.ContextBuilder
import com.khoryz666.waypoint.llm.DeepSeekClient
import com.khoryz666.waypoint.llm.LlmService
import com.khoryz666.waypoint.llm.PromptBuilder
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class InputPipelineTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    private val clock = FixedClock(LocalDateTime.of(2026, 10, 30, 21, 0))
    private val server = MockWebServer().apply { start() }
    private var scheduled = 0

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    private class Env(val settings: SettingsStore, val input: InputRepository, val parser: Parser)

    private suspend fun TestScope.env(apiKey: Boolean = true): Env {
        val settings = testSettings(backgroundScope, tmp.root)
        settings.update { it.copy(profile = it.profile.copy(currency = "MYR")) }
        if (apiKey) settings.setApiKey("sk-test")
        val prompts = PromptBuilder { File("src/main/assets/prompts/$it.md").readText() }
        val llm = LlmService(DeepSeekClient({ settings.apiKey() }, server.url("").toString().trimEnd('/'), backoffMs = 1), prompts, settings, com.khoryz666.waypoint.fakes.FakeUsageDao(), clock)
        val images = ImageStore(context)
        return Env(settings, InputRepository(db, images, settings, { scheduled++ }, clock), Parser(db, llm, ContextBuilder(prompts, clock), settings, images, clock))
    }

    private fun reply(json: String) = MockResponse().setBody(
        """{"choices":[{"message":{"content":${kotlinx.serialization.json.JsonPrimitive(json)}}}],"usage":{"prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":10,"completion_tokens":10}}""",
    )

    private suspend fun rustTrack(): Pair<Long, List<Long>> {
        val id = db.tracks().insert(Track(domain = Domain.FUNDAMENTAL, title = "Rust book", status = TrackStatus.ACTIVE, goal = "Finish", createdAt = 0, updatedAt = 0))
        val units = db.units().insertAll((1..3).map { TrackUnit(trackId = id, order = it, title = "Ch $it") })
        return id to units
    }

    @Test
    fun `money shortcuts save at once without the parse queue`() = runTest {
        val e = env()
        e.input.submit("=4210", emptyList(), null)
        e.input.submit("-42.50 groceries", emptyList(), null)
        assertEquals(4_167_50, balanceAt(db.balance().live()))
        assertEquals(0, scheduled)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `text is saved before parsing and routed by the parser`() = runTest {
        val e = env()
        val (trackId, units) = rustTrack()
        val submitted = e.input.submit("Rust book ch2 读完", emptyList(), null) as InputRepository.Submitted.Queued
        assertEquals(1, scheduled)
        assertEquals(ParseStatus.PENDING, db.logs().get(submitted.logId)!!.parseStatus)

        server.enqueue(reply("""{"trackId": $trackId, "confidence": 0.95, "log": {"type": "fundamental", "unitsDone": [${units[1]}, 999], "notes": "ch2"}}"""))
        assertEquals(ParseOutcome.PARSED, e.parser.parse(db.logs().get(submitted.logId)!!))
        val parsed = db.logs().get(submitted.logId)!!
        assertEquals(trackId, parsed.trackId)
        assertEquals(listOf(units[1]), (parsed.structured as Structured.Fundamental).unitsDone)
        assertTrue(server.takeRequest().body.readUtf8().contains("${units[1]}: Ch 2 [TODO]"))

        e.input.confirm(parsed, parsed.structured!!, trackId)
        assertEquals(UnitStatus.DONE, db.units().get(units[1])!!.status)
        assertEquals(ParseStatus.CONFIRMED, db.logs().get(submitted.logId)!!.parseStatus)
        assertEquals(1, db.tracks().get(trackId)!!.logsSinceSummary)
    }

    @Test
    fun `without a key the queue pauses and nothing is lost`() = runTest {
        val e = env(apiKey = false)
        val submitted = e.input.submit("did stuff", emptyList(), null) as InputRepository.Submitted.Queued
        assertEquals(ParseOutcome.PAUSED, e.parser.parse(db.logs().get(submitted.logId)!!))
        assertEquals(ParseStatus.PENDING, db.logs().get(submitted.logId)!!.parseStatus)
    }

    @Test
    fun `malformed json twice fails to the manual form`() = runTest {
        val e = env()
        val submitted = e.input.submit("x", emptyList(), null) as InputRepository.Submitted.Queued
        server.enqueue(reply("nope"))
        server.enqueue(reply("still nope"))
        assertEquals(ParseOutcome.FAILED, e.parser.parse(db.logs().get(submitted.logId)!!))
        assertEquals(ParseStatus.FAILED, db.logs().get(submitted.logId)!!.parseStatus)
    }

    @Test
    fun `network errors retry later`() = runTest {
        val e = env()
        val submitted = e.input.submit("x", emptyList(), null) as InputRepository.Submitted.Queued
        server.shutdown()
        assertEquals(ParseOutcome.RETRY_LATER, e.parser.parse(db.logs().get(submitted.logId)!!))
    }

    @Test
    fun `sleep nights keep the old record unless the user picks the new one`() = runTest {
        val e = env()
        db.sleep().put(SleepRecord(date = LocalDate.of(2026, 10, 29), bedAt = null, wakeAt = null, totalMin = 300, source = SleepSource.MANUAL))
        val logId = db.logs().insert(com.khoryz666.waypoint.data.LogEntry(occurredAt = clock.now(), createdAt = clock.now(), parseStatus = ParseStatus.PARSED))
        val nights = Structured.Sleep(listOf(Night("2026-10-29", "23:40", "07:00", 420), Night("2026-10-30", "00:10", "07:30", 430)), "weekly")
        e.input.confirm(db.logs().get(logId)!!, nights, null, mapOf("2026-10-29" to SleepConflict.KEEP_OLD))
        val records = db.sleep().all().associateBy { it.date.toString() }
        assertEquals(300, records.getValue("2026-10-29").totalMin)
        assertEquals(430, records.getValue("2026-10-30").totalMin)
        // 00:10 is after midnight, so the bed time is on the wake date itself.
        assertEquals(clock.at(LocalDateTime.of(2026, 10, 30, 0, 10)), records.getValue("2026-10-30").bedAt)
    }

    @Test
    fun `a retro-logged session becomes a timer session on the track`() = runTest {
        val e = env()
        val (trackId, _) = rustTrack()
        val logId = db.logs().insert(com.khoryz666.waypoint.data.LogEntry(occurredAt = clock.now(), createdAt = clock.now(), parseStatus = ParseStatus.PARSED))
        val start = clock.at(LocalDateTime.of(2026, 10, 30, 15, 0))
        e.input.confirm(db.logs().get(logId)!!, Structured.Session(start, start + 40 * 60_000), trackId)
        val session = db.sessions().all().single()
        assertEquals(trackId, session.trackId)
        assertEquals(40 * 60_000L, session.endAt!! - session.startAt)
        assertEquals(LogKind.REPORT, db.logs().get(logId)!!.kind)
    }
}

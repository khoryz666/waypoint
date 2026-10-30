package com.khoryz666.waypoint.llm

import com.khoryz666.waypoint.data.ApiProblem
import com.khoryz666.waypoint.data.UsageRecord
import com.khoryz666.waypoint.domain.FixedClock
import com.khoryz666.waypoint.fakes.FakeUsageDao
import com.khoryz666.waypoint.fakes.testSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime

class LlmServiceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer().apply { start() }
    private val clock = FixedClock(LocalDateTime.of(2026, 11, 10, 12, 0))
    private val usage = FakeUsageDao()
    private val prompts = PromptBuilder { name -> "prompt:$name" }

    @After fun tearDown() = server.shutdown()

    private fun reply(content: String) = MockResponse().setBody(
        """{"model":"deepseek-flash","choices":[{"message":{"content":${kotlinx.serialization.json.JsonPrimitive(content)}}}],
           "usage":{"prompt_cache_hit_tokens":1000000,"prompt_cache_miss_tokens":0,"completion_tokens":1000000}}""",
    )

    private fun kotlinx.coroutines.test.TestScope.service(): LlmService {
        val settings = testSettings(backgroundScope, tmp.root)
        val client = DeepSeekClient({ "sk" }, server.url("").toString().trimEnd('/'), backoffMs = 1)
        return LlmService(client, prompts, settings, usage, clock)
    }

    @Test
    fun `usage is recorded with cost from the price table`() = runTest {
        server.enqueue(reply("hello"))
        assertEquals("hello", service().text("commit_summary", listOf(Part.Text("x")), 200))
        val record = usage.records.value.single()
        assertEquals("commit_summary", record.promptName)
        assertEquals(0.04 + 8.0, record.costCny, 1e-9)
        assertTrue(server.takeRequest().body.readUtf8().contains("prompt:system_core\\n\\nprompt:commit_summary"))
    }

    @Test
    fun `bad json retries once with the decode error`() = runTest {
        server.enqueue(reply("not json"))
        server.enqueue(reply("""{"questions":["Why?"]}"""))
        val out = service().json("recall_question", listOf(Part.Text("u")), 300, QuestionsOutput.serializer())
        assertEquals(listOf("Why?"), out.questions)
        server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains("could not be decoded"))
    }

    @Test
    fun `at the cap only essential prompts call the API`() = runTest {
        usage.insert(UsageRecord(at = clock.now(), model = "m", promptName = "x", cacheHitTokens = 0, cacheMissTokens = 0, outputTokens = 0, costCny = 50.0))
        val s = service()
        assertTrue(runCatching { s.text("comment_log", listOf(Part.Text("x")), 300) }.exceptionOrNull() is BudgetExceeded)
        server.enqueue(reply("""{"trackId":null,"confidence":0.1,"log":{"type":"note","text":"x"}}"""))
        s.json("parse_input", listOf(Part.Text("x")), 800, ParseOutput.serializer())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `402 flags the settings so the queue pauses, success clears it`() = runTest {
        val settings = testSettings(backgroundScope, tmp.root)
        val s = LlmService(DeepSeekClient({ "sk" }, server.url("").toString().trimEnd('/'), backoffMs = 1), prompts, settings, usage, clock)
        server.enqueue(MockResponse().setResponseCode(402))
        runCatching { s.text("commit_summary", listOf(Part.Text("x")), 200) }
        assertEquals(ApiProblem.NO_BALANCE, settings.settings.first().apiProblem)
        server.enqueue(reply("ok"))
        s.text("commit_summary", listOf(Part.Text("x")), 200)
        assertEquals(null, settings.settings.first().apiProblem)
    }
}

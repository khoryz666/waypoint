package com.khoryz666.waypoint.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekClientTest {
    private val server = MockWebServer().apply { start() }
    private val client = DeepSeekClient({ "sk-test" }, server.url("").toString().trimEnd('/'), backoffMs = 1)
    private val request = ChatRequest("deepseek-flash", "system", listOf(Part.Text("hi")), 300, json = true)

    @After fun tearDown() = server.shutdown()

    private fun ok(content: String) = MockResponse().setBody(
        """{"model":"deepseek-flash","choices":[{"message":{"content":${kotlinx.serialization.json.JsonPrimitive(content)}}}],
           "usage":{"prompt_tokens":120,"prompt_cache_hit_tokens":100,"prompt_cache_miss_tokens":20,"completion_tokens":15}}""",
    )

    @Test
    fun `json mode request disables thinking and returns usage`() = runTest {
        server.enqueue(ok("{\"a\":1}"))
        val result = client.complete(request)
        assertEquals("{\"a\":1}", result.content)
        assertEquals(Usage(100, 20, 15), result.usage)
        val sent = server.takeRequest()
        assertEquals("Bearer sk-test", sent.getHeader("Authorization"))
        val body = sent.body.readUtf8()
        assertTrue(body.contains("\"response_format\":{\"type\":\"json_object\"}"))
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertFalse(body.contains("\"stream\""))
    }

    @Test
    fun `images go in the user message as data urls with original detail`() = runTest {
        server.enqueue(ok("{}"))
        client.complete(request.copy(user = listOf(Part.Text("read"), Part.Image("data:image/jpeg;base64,AAAA"))))
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"image_url\":{\"url\":\"data:image/jpeg;base64,AAAA\",\"detail\":\"original\"}"))
        assertTrue(body.contains("{\"role\":\"system\",\"content\":\"system\"}"))
    }

    @Test
    fun `429 and 5xx retry up to three times`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(ok("done"))
        assertEquals("done", client.complete(request).content)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `a fourth failure gives up`() = runTest {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500)) }
        val e = runCatching { client.complete(request) }.exceptionOrNull()
        assertTrue(e is ApiException.Http && e.code == 500)
    }

    @Test
    fun `401, 402 and a retired model map to specific errors`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(runCatching { client.complete(request) }.exceptionOrNull() is ApiException.KeyInvalid)
        server.enqueue(MockResponse().setResponseCode(402))
        assertTrue(runCatching { client.complete(request) }.exceptionOrNull() is ApiException.NoBalance)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Model Not Exist"}}"""))
        val e = runCatching { client.complete(request) }.exceptionOrNull()
        assertTrue(e is ApiException.ModelUnavailable && e.message!!.contains("deepseek-flash"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `streaming emits deltas and the final usage`() = runTest {
        val sse = listOf(
            """{"choices":[{"delta":{"content":"本周"}}]}""",
            """{"choices":[{"delta":{"content":" 3 章"}}]}""",
            """{"choices":[],"usage":{"prompt_cache_hit_tokens":5,"prompt_cache_miss_tokens":6,"completion_tokens":7}}""",
        ).joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        val events = client.stream(request.copy(json = false)).toList()
        assertEquals(listOf("本周", " 3 章"), events.filterIsInstance<StreamEvent.Delta>().map { it.text })
        val done = events.last() as StreamEvent.Done
        assertEquals("本周 3 章", done.content)
        assertEquals(Usage(5, 6, 7), done.usage)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"stream_options\":{\"include_usage\":true}"))
    }

    @Test
    fun `models lists ids`() = runTest {
        server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"deepseek-flash"},{"id":"deepseek-v4-pro"}]}"""))
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), client.models("sk-other"))
        assertEquals("Bearer sk-other", server.takeRequest().getHeader("Authorization"))
    }
}

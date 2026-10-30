package com.khoryz666.waypoint.github

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GitHubClientTest {
    private val server = MockWebServer().apply { start() }
    private val client = GitHubClient({ "ghp_test" }, server.url("").toString().trimEnd('/'))

    @After fun tearDown() = server.shutdown()

    private fun commit(sha: String, msg: String) = """{"sha":"$sha","commit":{"message":"$msg","author":{"date":"2026-10-30T07:00:00Z"}}}"""

    @Test
    fun `commits follow pagination and filter by author and since`() = runTest {
        val next = server.url("/repos/me/app/commits?page=2").toString()
        server.enqueue(MockResponse().setBody("[${commit("a1", "feat: bm25 scorer\\n\\nbody")}]").setHeader("Link", "<$next>; rel=\"next\", <$next>; rel=\"last\""))
        server.enqueue(MockResponse().setBody("[${commit("b2", "fix: idf")}]"))
        val commits = client.commits("me/app", "me", Instant.parse("2026-10-29T00:00:00Z"))
        assertEquals(listOf("a1", "b2"), commits.map { it.sha })
        val first = server.takeRequest()
        assertTrue(first.path!!.contains("author=me") && first.path!!.contains("since=2026-10-29T00:00:00Z"))
        assertEquals("Bearer ghp_test", first.getHeader("Authorization"))
        assertEquals(Instant.parse("2026-10-30T07:00:00Z").toEpochMilli(), commits[0].committedAt)
    }

    @Test
    fun `an expired token or renamed repo surfaces the status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val e = runCatching { client.commits("me/old", "me", Instant.EPOCH) }.exceptionOrNull()
        assertTrue(e is GitHubException && e.code == 404)
    }

    @Test
    fun `login reads the user`() = runTest {
        server.enqueue(MockResponse().setBody("""{"login":"octo"}"""))
        assertEquals("octo", client.login())
    }
}

package com.khoryz666.waypoint.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/** A GitHub call that failed with an HTTP status, shown on the track as "同步失败" (spec 10.1). */
class GitHubException(val code: Int, message: String) : Exception(message)

data class RemoteCommit(val sha: String, val committedAt: Long, val message: String)

/** Read-only GitHub REST over OkHttp with a fine-grained token (spec 6.4). Messages only, no diffs. */
class GitHubClient(
    private val token: suspend () -> String?,
    private val baseUrl: String = "https://api.github.com",
    http: OkHttpClient = OkHttpClient(),
) {
    private val client = http.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun login(explicitToken: String? = null): String =
        json.decodeFromString(User.serializer(), get("$baseUrl/user", explicitToken).first).login

    /** Every commit by [author] since [since], following `Link: rel="next"` pages. */
    suspend fun commits(repo: String, author: String, since: Instant): List<RemoteCommit> {
        val result = mutableListOf<RemoteCommit>()
        var url: String? = "$baseUrl/repos/$repo/commits?since=$since&author=$author&per_page=100"
        while (url != null) {
            val (body, next) = get(url)
            json.decodeFromString(commitList, body).forEach { c ->
                result += RemoteCommit(c.sha, Instant.parse(c.commit.author?.date ?: c.commit.committer?.date ?: "1970-01-01T00:00:00Z").toEpochMilli(), c.commit.message)
            }
            url = next
        }
        return result
    }

    private suspend fun get(url: String, explicitToken: String? = null): Pair<String, String?> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${explicitToken ?: token() ?: throw GitHubException(401, "no token")}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw GitHubException(response.code, "HTTP ${response.code}")
                response.body!!.string() to nextLink(response.header("Link"))
            }
        } catch (e: IOException) {
            throw GitHubException(0, "network: ${e.message}")
        }
    }

    private fun nextLink(header: String?): String? =
        header?.split(',')?.map { it.trim() }?.firstOrNull { it.endsWith("rel=\"next\"") }?.substringAfter('<')?.substringBefore('>')

    @Serializable private data class User(val login: String)
    @Serializable private data class CommitItem(val sha: String, val commit: CommitBody)
    @Serializable private data class CommitBody(val message: String, val author: Person? = null, val committer: Person? = null)
    @Serializable private data class Person(val date: String? = null)

    private val commitList: kotlinx.serialization.KSerializer<List<CommitItem>> = kotlinx.serialization.builtins.ListSerializer(CommitItem.serializer())
}

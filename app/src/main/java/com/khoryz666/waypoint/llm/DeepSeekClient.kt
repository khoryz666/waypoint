package com.khoryz666.waypoint.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One part of a user message: text or a base64 image data URL (spec 8.1: images in user messages only). */
sealed interface Part {
    data class Text(val text: String) : Part
    data class Image(val dataUrl: String) : Part
}

data class ChatRequest(
    val model: String,
    val system: String,
    val user: List<Part>,
    val maxTokens: Int,
    val json: Boolean = false,
    /** DeepSeek turns thinking on by default; only reviews and "think harder" leave it on. */
    val thinking: Boolean = false,
    val slow: Boolean = false,
)

@Serializable
data class Usage(
    @SerialName("prompt_cache_hit_tokens") val cacheHit: Int = 0,
    @SerialName("prompt_cache_miss_tokens") val cacheMiss: Int = 0,
    @SerialName("completion_tokens") val output: Int = 0,
)

data class ChatResult(val content: String, val usage: Usage, val model: String)

sealed interface StreamEvent {
    data class Delta(val text: String) : StreamEvent
    data class Done(val content: String, val usage: Usage, val model: String) : StreamEvent
}

/** Every way a call can fail, so callers can react per spec 8.1 and 10.1. */
sealed class ApiException(message: String) : Exception(message) {
    class KeyInvalid : ApiException("API key invalid (401)")
    class NoBalance : ApiException("DeepSeek balance insufficient (402)")
    class ModelUnavailable(val model: String, detail: String) : ApiException("Model $model unavailable: $detail")
    class Http(val code: Int, detail: String) : ApiException("HTTP $code: $detail")
    class Network(cause: IOException) : ApiException("Network: ${cause.message}")
}

/**
 * DeepSeek's OpenAI-compatible Chat Completions over OkHttp (spec 8.1). Retries 429 and 5xx up to
 * 3 times with exponential backoff; everything else surfaces as an [ApiException].
 */
class DeepSeekClient(
    private val apiKey: suspend () -> String?,
    private val baseUrl: String = "https://api.deepseek.com",
    private val backoffMs: Long = 1_000,
    http: OkHttpClient = OkHttpClient(),
) {
    private val fast = http.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val slow = fast.newBuilder().readTimeout(180, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** Model ids from `GET /models`, for the settings picker. */
    suspend fun models(key: String? = null): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/models").header("Authorization", "Bearer ${key ?: requireKey()}").get().build()
        execute(fast, request, model = "").use { response ->
            json.decodeFromString(ModelList.serializer(), response.body!!.string()).data.map { it.id }
        }
    }

    suspend fun complete(request: ChatRequest): ChatResult = withContext(Dispatchers.IO) {
        execute(client(request), httpRequest(request, stream = false), request.model).use { response ->
            val body = json.decodeFromString(Completion.serializer(), response.body!!.string())
            ChatResult(body.choices.firstOrNull()?.message?.content.orEmpty(), body.usage ?: Usage(), body.model ?: request.model)
        }
    }

    /** Server-sent events; text appears as generated, usage arrives on the final chunk. */
    fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val built = httpRequest(request, stream = true)
        execute(client(request), built, request.model).use { response ->
            val source = response.body!!.source()
            val content = StringBuilder()
            var usage = Usage()
            var model = request.model
            while (true) {
                val line = try {
                    source.readUtf8Line()
                } catch (e: IOException) {
                    throw ApiException.Network(e)
                } ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val chunk = json.decodeFromString(Chunk.serializer(), data)
                chunk.usage?.let { usage = it }
                chunk.model?.let { model = it }
                val text = chunk.choices.firstOrNull()?.delta?.content
                if (!text.isNullOrEmpty()) {
                    content.append(text)
                    emit(StreamEvent.Delta(text))
                }
            }
            emit(StreamEvent.Done(content.toString(), usage, model))
        }
    }.flowOn(Dispatchers.IO)

    private fun client(request: ChatRequest) = if (request.slow) slow else fast

    private suspend fun requireKey(): String = apiKey() ?: throw ApiException.KeyInvalid()

    private suspend fun httpRequest(request: ChatRequest, stream: Boolean): Request {
        val body = buildJsonObject {
            put("model", request.model)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", request.system) })
                    add(buildJsonObject { put("role", "user"); put("content", userContent(request.user)) })
                },
            )
            put("max_tokens", request.maxTokens)
            putJsonObject("thinking") { put("type", if (request.thinking) "enabled" else "disabled") }
            if (request.json) putJsonObject("response_format") { put("type", "json_object") }
            if (stream) {
                put("stream", true)
                putJsonObject("stream_options") { put("include_usage", true) }
            }
        }
        return Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer ${requireKey()}")
            .post(body.toString().toRequestBody(JSON))
            .build()
    }

    private fun userContent(parts: List<Part>): JsonElement =
        if (parts.size == 1 && parts[0] is Part.Text) {
            JsonPrimitive((parts[0] as Part.Text).text)
        } else {
            JsonArray(
                parts.map { part ->
                    when (part) {
                        is Part.Text -> buildJsonObject { put("type", "text"); put("text", part.text) }
                        is Part.Image -> buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", part.dataUrl); put("detail", "original") }
                        }
                    }
                },
            )
        }

    private suspend fun execute(client: OkHttpClient, request: Request, model: String): Response {
        var attempt = 0
        while (true) {
            val response = try {
                withContext(Dispatchers.IO) { client.newCall(request).execute() }
            } catch (e: IOException) {
                throw ApiException.Network(e)
            }
            if (response.isSuccessful) return response
            val code = response.code
            val detail = response.body?.string().orEmpty().take(300)
            response.close()
            val retryable = code == 429 || code >= 500
            if (retryable && attempt < MAX_RETRIES) {
                delay(backoffMs shl attempt)
                attempt++
                continue
            }
            throw when {
                code == 401 -> ApiException.KeyInvalid()
                code == 402 -> ApiException.NoBalance()
                (code == 400 || code == 404 || code == 422) && model.isNotEmpty() && detail.contains("model", ignoreCase = true) ->
                    ApiException.ModelUnavailable(model, detail)
                else -> ApiException.Http(code, detail)
            }
        }
    }

    @Serializable private data class ModelList(val data: List<ModelId> = emptyList())
    @Serializable private data class ModelId(val id: String)
    @Serializable private data class Completion(val choices: List<Choice> = emptyList(), val usage: Usage? = null, val model: String? = null)
    @Serializable private data class Choice(val message: Message? = null, val delta: Message? = null)
    @Serializable private data class Message(val content: String? = null)
    @Serializable private data class Chunk(val choices: List<Choice> = emptyList(), val usage: Usage? = null, val model: String? = null)

    private companion object {
        const val MAX_RETRIES = 3
        val JSON = "application/json".toMediaType()
    }
}

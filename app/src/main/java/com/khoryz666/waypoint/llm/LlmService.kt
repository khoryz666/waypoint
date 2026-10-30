package com.khoryz666.waypoint.llm

import com.khoryz666.waypoint.data.ApiProblem
import com.khoryz666.waypoint.data.SettingsStore
import com.khoryz666.waypoint.data.UsageDao
import com.khoryz666.waypoint.data.UsageRecord
import com.khoryz666.waypoint.domain.AppClock
import com.khoryz666.waypoint.domain.BudgetState
import com.khoryz666.waypoint.domain.budgetState
import com.khoryz666.waypoint.domain.costCny
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.time.YearMonth

/** A call skipped because the monthly budget is used up (spec 8.5). */
class BudgetExceeded(promptName: String) : Exception("Budget reached: $promptName skipped")

/** Which model setting a call uses: Flash for per-log work, Pro for reviews and "think harder". */
enum class Tier { FAST, REVIEW }

/**
 * The one way the app talks to DeepSeek. It assembles the system prompt, enforces the budget,
 * records usage and cost for every response, decodes JSON against a schema with one retry, and
 * flags 401/402 so the queue pauses.
 */
class LlmService(
    private val client: DeepSeekClient,
    private val prompts: PromptBuilder,
    private val settings: SettingsStore,
    private val usage: UsageDao,
    private val clock: AppClock,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    suspend fun budget(): BudgetState {
        val s = settings.current()
        val today = clock.today()
        val month = YearMonth.from(today)
        val spent = usage.costSince(clock.startOf(month.atDay(1)))
        val fraction = (clock.now() - clock.startOf(today)).toDouble() / (24 * 3600_000)
        return budgetState(spent, s.monthlyBudgetCny, month, today.dayOfMonth, fraction)
    }

    suspend fun text(prompt: String, user: List<Part>, maxTokens: Int, tier: Tier = Tier.FAST, thinking: Boolean = false): String =
        call(prompt, user, maxTokens, tier, json = false, thinking = thinking).content

    /** A JSON prompt decoded into [serializer]; one retry quoting the decode error, then the error propagates. */
    suspend fun <T> json(prompt: String, user: List<Part>, maxTokens: Int, serializer: KSerializer<T>, tier: Tier = Tier.FAST, thinking: Boolean = false): T {
        val first = call(prompt, user, maxTokens, tier, json = !thinking, thinking = thinking).content
        val error = try {
            return decode(first, serializer)
        } catch (e: Exception) {
            e.message ?: "invalid json"
        }
        val retry = user + Part.Text("\n\nYour previous reply could not be decoded (${error.take(200)}). Reply again with valid json only, in the exact shape shown.")
        return decode(call(prompt, retry, maxTokens, tier, json = !thinking, thinking = thinking).content, serializer)
    }

    /** Streams a text reply; usage is recorded when the stream ends. */
    fun stream(prompt: String, user: List<Part>, maxTokens: Int): Flow<StreamEvent> = flow {
        gate(prompt)
        val request = request(prompt, user, maxTokens, Tier.FAST, json = false, thinking = false)
        client.stream(request).catch { e -> flag(e); throw e }.collect { event ->
            if (event is StreamEvent.Done) record(prompt, event.model, event.usage)
            emit(event)
        }
        clearProblem()
    }

    suspend fun models(key: String): List<String> = client.models(key)

    private suspend fun call(prompt: String, user: List<Part>, maxTokens: Int, tier: Tier, json: Boolean, thinking: Boolean): ChatResult {
        gate(prompt)
        val result = try {
            client.complete(request(prompt, user, maxTokens, tier, json, thinking))
        } catch (e: Exception) {
            flag(e)
            throw e
        }
        record(prompt, result.model, result.usage)
        clearProblem()
        return result
    }

    private suspend fun request(prompt: String, user: List<Part>, maxTokens: Int, tier: Tier, json: Boolean, thinking: Boolean): ChatRequest {
        val s = settings.current()
        return ChatRequest(
            model = if (tier == Tier.REVIEW) s.reviewModel else s.fastModel,
            system = prompts.template("system_core") + "\n\n" + prompts.template(prompt),
            user = user,
            maxTokens = if (thinking) maxTokens * 8 else maxTokens,
            json = json,
            thinking = thinking,
            slow = tier == Tier.REVIEW || thinking,
        )
    }

    private suspend fun gate(prompt: String) {
        if (!budget().allows(prompt)) throw BudgetExceeded(prompt)
    }

    private suspend fun record(prompt: String, model: String, u: Usage) {
        val prices = settings.current().prices
        val price = prices[model] ?: prices.entries.firstOrNull { model.startsWith(it.key) || it.key.startsWith(model) }?.value
        usage.insert(UsageRecord(at = clock.now(), model = model, promptName = prompt, cacheHitTokens = u.cacheHit, cacheMissTokens = u.cacheMiss, outputTokens = u.output, costCny = costCny(price, u.cacheHit, u.cacheMiss, u.output)))
    }

    private suspend fun flag(e: Throwable) {
        val problem = when (e) {
            is ApiException.KeyInvalid -> ApiProblem.KEY_INVALID
            is ApiException.NoBalance -> ApiProblem.NO_BALANCE
            else -> return
        }
        settings.update { it.copy(apiProblem = problem) }
    }

    private suspend fun clearProblem() {
        if (settings.current().apiProblem != null) settings.update { it.copy(apiProblem = null) }
    }

    private fun <T> decode(text: String, serializer: KSerializer<T>): T {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        require(start >= 0 && end > start) { "no json object in reply" }
        return json.decodeFromString(serializer, text.substring(start, end + 1))
    }
}

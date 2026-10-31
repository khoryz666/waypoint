package com.khoryz666.waypoint.llm

import com.khoryz666.waypoint.domain.AppClock
import com.khoryz666.waypoint.domain.BalanceKind
import com.khoryz666.waypoint.domain.Grade
import com.khoryz666.waypoint.domain.Night
import com.khoryz666.waypoint.domain.Structured
import com.khoryz666.waypoint.domain.WaypointJson
import com.khoryz666.waypoint.domain.parseMinor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime

// Response shapes for every JSON prompt (spec 8.2). Decoding failures trigger one retry that
// includes the error, then FAILED + manual form.

@Serializable
data class ParseOutput(val trackId: Long? = null, val confidence: Double = 0.0, val log: JsonObject = JsonObject(emptyMap()))

@Serializable
data class SleepOutput(val nights: List<Night> = emptyList(), val screenType: String = "unknown")

@Serializable
data class ExerciseOutput(val text: String, val dueHint: String? = null, val rule: String? = null, val comment: String? = null)

@Serializable
data class QuestionsOutput(val questions: List<String> = emptyList())

@Serializable
data class GradeOutput(
    val grade: Grade,
    val whatWasRight: String? = null,
    val whatWasMissing: String? = null,
    val followUpQuestion: String? = null,
)

@Serializable
data class ReviewOutput(
    val narrative: String,
    val oneThingToFix: String = "",
    val top3: List<String> = emptyList(),
    val suggestions: List<Suggestion> = emptyList(),
)

@Serializable
data class Suggestion(val topic: String, val book: String? = null)

/**
 * Turns parse_input's "log" object into a [Structured] value. Most types decode as-is; balance
 * amounts become minor units and retro-logged session times become instants.
 */
fun ParseOutput.toStructured(currency: String, clock: AppClock): Structured {
    val type = log["type"]?.jsonPrimitive?.contentOrNull
    fun str(key: String) = (log[key] as? JsonPrimitive)?.contentOrNull
    return runCatching {
        when (type) {
            "balance" -> {
                val raw = str("amount") ?: str("amountMinor")?.let { (it.toLong() / 100.0).toString() } ?: "0"
                val minor = parseMinor(raw.trim().removePrefix("+"), currency) ?: 0
                Structured.Balance(if (str("kind") == "SET") BalanceKind.SET else BalanceKind.DELTA, minor, str("note"))
            }
            "session" -> {
                fun instant(key: String) = LocalDateTime.parse(str(key)!!.take(16)).atZone(clock.zone()).toInstant().toEpochMilli()
                Structured.Session(instant("startAt"), instant("endAt"), str("notes").orEmpty())
            }
            null -> Structured.Note("")
            else -> WaypointJson.decodeFromJsonElement(Structured.serializer(), log)
        }
    }.getOrElse { Structured.Note(log.toString()) }
}

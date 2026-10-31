package com.khoryz666.waypoint.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON settings shared by Room columns, the LLM layer and backups. */
val WaypointJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
    classDiscriminator = "type"
}

/** One measurable check of a track's goal (spec 3). */
@Serializable
data class GoalCriterion(val label: String, val metric: String? = null, val target: String? = null, val met: Boolean = false)

/**
 * The kind-specific body of a LogEntry (spec 3 `structured`), discriminated by "type". One class
 * per domain behaviour in spec 6; the LLM fills these from free text, the confirm card edits them.
 */
@Serializable
sealed interface Structured {
    val minutes: Int? get() = null

    /** Fundamentals: books, courses, problem sets (6.5). Unit ids refer to the routed track. */
    @Serializable
    @SerialName("fundamental")
    data class Fundamental(
        val unitsDone: List<Long> = emptyList(),
        val unitsStarted: List<Long> = emptyList(),
        /** Units the log mentions that aren't in the outline yet (e.g. a new NeetCode problem). */
        val newUnits: List<String> = emptyList(),
        val usedHint: Boolean? = null,
        override val minutes: Int? = null,
        val notes: String = "",
    ) : Structured

    /** Math (6.6). Problems are labels as the user wrote them ("9", "3.2-12"). */
    @Serializable
    @SerialName("math")
    data class Math(
        val book: String = "",
        val section: String = "",
        val problemsAttempted: String = "",
        val attemptedCount: Int = 0,
        val solved: List<String> = emptyList(),
        val stuck: List<String> = emptyList(),
        val unitsDone: List<Long> = emptyList(),
        val unitsStarted: List<Long> = emptyList(),
        /** Problems the user wants checked against the attached photos (opt-in). */
        val checkProblems: List<String> = emptyList(),
        override val minutes: Int? = null,
        val notes: String = "",
    ) : Structured

    /** Reading (6.7). */
    @Serializable
    @SerialName("reading")
    data class Reading(
        val pagesOrChapter: String = "",
        val keyIdeas: List<String> = emptyList(),
        val ownWords: Boolean = false,
        val interestSignals: List<String> = emptyList(),
        val unitsDone: List<Long> = emptyList(),
        override val minutes: Int? = null,
        val notes: String = "",
    ) : Structured

    /** Manual project work (6.4): writing, meetings, client calls. */
    @Serializable
    @SerialName("project")
    data class Project(
        /** Milestones the log suggests are done; the user confirms each on the card. */
        val milestonesDone: List<Long> = emptyList(),
        override val minutes: Int? = null,
        val notes: String = "",
    ) : Structured

    /** Exercise (6.1). */
    @Serializable
    @SerialName("exercise")
    data class Exercise(
        val exercises: List<ExerciseItem> = emptyList(),
        val swimMin: Int? = null,
        val strokes: String? = null,
        val rpe: Int? = null,
        val pain: String? = null,
        override val minutes: Int? = null,
        val notes: String = "",
    ) : Structured

    /** Sleep screenshots or manual sleep text (6.2). Confirming writes SleepRecords; no track. */
    @Serializable
    @SerialName("sleep")
    data class Sleep(val nights: List<Night> = emptyList(), val screenType: String = "unknown") : Structured

    /** Natural-language balance entries (6.3); the +/-/= shortcuts never reach the LLM. */
    @Serializable
    @SerialName("balance")
    data class Balance(val kind: BalanceKind = BalanceKind.DELTA, val amountMinor: Long = 0, val note: String? = null) : Structured

    /** A retro-logged timer session ("40m of X around 3pm", 5.5). Times are epoch ms. */
    @Serializable
    @SerialName("session")
    data class Session(val startAt: Long, val endAt: Long, val notes: String = "") : Structured {
        override val minutes: Int get() = ((endAt - startAt) / MINUTE_MS).toInt()
    }

    /** One day's commits on a project track (6.4), summarised by Flash. */
    @Serializable
    @SerialName("commits")
    data class Commits(val date: String, val shas: List<String>, val messages: List<String>, val summary: String = "") : Structured

    /**
     * A recall check (6.5) or ownership check (6.4): the question, the user's answer, the grade.
     * [answer] null and [skipped] false means it is still waiting on Today.
     */
    @Serializable
    @SerialName("recall")
    data class Recall(
        val source: RecallSource,
        val question: String,
        val unitId: Long? = null,
        val commitSha: String? = null,
        val answer: String? = null,
        val skipped: Boolean = false,
        val grade: Grade? = null,
        val whatWasRight: String? = null,
        val whatWasMissing: String? = null,
        val followUpQuestion: String? = null,
    ) : Structured {
        val pending: Boolean get() = answer == null && !skipped
    }

    /** Anything the parser couldn't place; kept so nothing typed is lost. */
    @Serializable
    @SerialName("note")
    data class Note(val text: String = "", override val minutes: Int? = null) : Structured

    companion object {
        fun decode(json: String): Structured? = runCatching { WaypointJson.decodeFromString(serializer(), json) }.getOrNull()
        fun encode(value: Structured): String = WaypointJson.encodeToString(serializer(), value)
    }
}

@Serializable
enum class RecallSource { @SerialName("unit") UNIT, @SerialName("review") REVIEW, @SerialName("ownership") OWNERSHIP }

@Serializable
enum class Grade { @SerialName("solid") SOLID, @SerialName("partial") PARTIAL, @SerialName("weak") WEAK }

@Serializable
data class ExerciseItem(val name: String, val sets: List<ExerciseSet> = emptyList())

@Serializable
data class ExerciseSet(val reps: Int? = null, val weightKg: Double? = null, val seconds: Int? = null, val assisted: String? = null)

/** One night from a sleep screenshot. [date] is the wake-up date (ISO); times are "HH:mm" local. */
@Serializable
data class Night(
    val date: String,
    val bedAt: String? = null,
    val wakeAt: String? = null,
    val totalMin: Int = 0,
    val deepMin: Int? = null,
    val lightMin: Int? = null,
    val remMin: Int? = null,
    val awakeMin: Int? = null,
    val napMin: Int? = null,
    val score: Int? = null,
)

/**
 * A day type's minimum or plan (spec 3, 5.6). Per-track maps are keyed by track id; the "any"
 * fields count every active track of [anyDomains] (all domains when empty). All zero = no target.
 */
@Serializable
data class DayTarget(
    val focusMinutes: Map<Long, Int> = emptyMap(),
    val unitsDone: Map<Long, Int> = emptyMap(),
    val anyFocusMinutes: Int = 0,
    val anyUnits: Int = 0,
    val anyDomains: List<Domain> = emptyList(),
    val exercise: Boolean = false,
) {
    val isEmpty: Boolean
        get() = focusMinutes.values.none { it > 0 } && unitsDone.values.none { it > 0 } &&
            anyFocusMinutes == 0 && anyUnits == 0 && !exercise
}

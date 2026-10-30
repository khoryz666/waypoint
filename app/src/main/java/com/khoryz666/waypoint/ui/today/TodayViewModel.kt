package com.khoryz666.waypoint.ui.today

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khoryz666.waypoint.AppContainer
import com.khoryz666.waypoint.data.DayStatus
import com.khoryz666.waypoint.data.DayType
import com.khoryz666.waypoint.data.LogEntry
import com.khoryz666.waypoint.data.NextStep
import com.khoryz666.waypoint.data.Settings
import com.khoryz666.waypoint.data.SleepRecord
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.data.TrackUnit
import com.khoryz666.waypoint.domain.BudgetLevel
import com.khoryz666.waypoint.domain.Grade
import com.khoryz666.waypoint.domain.HOUR_MS
import com.khoryz666.waypoint.domain.Structured
import com.khoryz666.waypoint.ingest.InputRepository
import com.khoryz666.waypoint.ingest.SkipReason
import com.khoryz666.waypoint.ingest.SleepConflict
import com.khoryz666.waypoint.timer.TimerTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StepItem(val step: NextStep, val track: Track)

data class TodayState(
    val day: DayStatus? = null,
    val settings: Settings = Settings(),
    val steps: List<StepItem> = emptyList(),
    val due: List<Pair<TrackUnit, Track>> = emptyList(),
    val recalls: List<LogEntry> = emptyList(),
    val cards: List<LogEntry> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val top3: List<String> = emptyList(),
    val budget: BudgetLevel = BudgetLevel.OK,
    val unitsByTrack: Map<Long, List<TrackUnit>> = emptyMap(),
    val existingSleep: Map<String, SleepRecord> = emptyMap(),
)

class TodayViewModel(private val c: AppContainer) : ViewModel() {
    private val budget = MutableStateFlow(BudgetLevel.OK)
    val feedback = c.feedback.current
    val inbox = c.inbox
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    private val cards = c.db.logs().observeUnconfirmed()
    private val allTracks = c.db.tracks().observeAll()

    val state: StateFlow<TodayState> = combine(
        c.stats.today(1_000),
        c.settings.settings,
        combine(c.db.nextSteps().observeOpen(), allTracks, ::Pair),
        combine(c.db.units().observeDue(c.clock.now() + 12 * HOUR_MS), c.db.logs().observeRecalls(), cards, c.db.units().observeAll(), ::Quad),
        combine(c.reviews.observePinnedTop3(), budget, c.db.sleep().observeBetween(c.clock.today().minusDays(60), c.clock.today().plusDays(1)), ::Triple),
    ) { day, s, (steps, tracks), (due, recalls, cards, units), (review, budget, sleep) ->
        val byId = tracks.associateBy { it.id }
        val pinned = review?.takeIf { c.clock.today() <= it.end.plusDays(7) }?.top3.orEmpty()
        TodayState(
            day = day,
            settings = s,
            steps = steps.mapNotNull { st -> byId[st.trackId]?.takeIf { it.status.name == "ACTIVE" }?.let { StepItem(st, it) } },
            due = due.mapNotNull { u -> byId[u.trackId]?.let { u to it } }.filter { (u, _) -> recalls.none { r -> (r.structured as? Structured.Recall)?.let { it.pending && it.unitId == u.id } == true } },
            recalls = recalls.filter { (it.structured as? Structured.Recall)?.pending == true },
            cards = cards,
            tracks = tracks,
            top3 = pinned,
            budget = budget,
            unitsByTrack = units.groupBy { it.trackId },
            existingSleep = sleep.associateBy { it.date.toString() },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    init {
        refreshBudget()
    }

    fun refreshBudget() = viewModelScope.launch { runCatching { budget.value = c.llm.budget().level } }

    fun submit(text: String, images: List<Uri>, trackId: Long?, sessionId: Long?) = viewModelScope.launch {
        when (val r = c.input.submit(text, images, trackId, sessionId)) {
            is InputRepository.Submitted.Balance -> c.feedback.onBalance(r.logId)
            is InputRepository.Submitted.Queued -> Unit
        }
        if (sessionId != null) c.notifications.cancelAsk()
        c.inbox.value = null
    }

    fun confirm(log: LogEntry, structured: Structured, trackId: Long?, sleep: Map<String, SleepConflict>) = viewModelScope.launch {
        c.feedback.onConfirmed(c.input.confirm(log, structured, trackId, sleep))
        refreshBudget()
    }

    fun discard(log: LogEntry) = viewModelScope.launch { c.input.discard(log) }
    fun undo(logId: Long) = viewModelScope.launch {
        c.db.logs().get(logId)?.let { c.input.delete(it) }
        c.feedback.dismiss()
    }

    fun reparse(log: LogEntry, text: String, trackId: Long?) = viewModelScope.launch { c.input.reparse(log, text, trackId) }
    fun dismissFeedback() = c.feedback.dismiss()

    fun setDayType(type: DayType) = viewModelScope.launch { c.stats.setDayType(c.clock.today(), type) }
    fun start(target: TimerTarget) = viewModelScope.launch { c.tracker.switchTo(target) }
    fun stop() = viewModelScope.launch { c.tracker.stop() }
    fun stepDone(step: NextStep) = viewModelScope.launch { c.db.nextSteps().markDone(step.id) }

    fun answer(log: LogEntry, answer: String, selfGrade: Grade? = null) = launchBusy {
        runCatching { c.feedback.answerRecall(log, answer, selfGrade) }.onFailure { message.value = it.message }
    }

    fun skip(log: LogEntry) = viewModelScope.launch { c.feedback.skipRecall(log) }
    fun openRevision(unit: TrackUnit) = launchBusy { c.feedback.openRevision(unit) }

    fun cantDoToday(track: Track, reason: SkipReason, note: String?) = launchBusy {
        runCatching { c.feedback.exerciseNext(track, reason, note) }.onFailure { message.value = it.message }
    }

    private fun launchBusy(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        try {
            block()
        } finally {
            busy.value = false
        }
    }
}

data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

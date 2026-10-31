package com.khoryz666.waypoint.ui.tracks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khoryz666.waypoint.AppContainer
import com.khoryz666.waypoint.data.Activation
import com.khoryz666.waypoint.data.LogEntry
import com.khoryz666.waypoint.data.Session
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.data.TrackUnit
import com.khoryz666.waypoint.domain.UnitStatus
import com.khoryz666.waypoint.llm.BudgetExceeded
import com.khoryz666.waypoint.work.CommitSyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrackDetailViewModel(private val c: AppContainer, val id: Long) : ViewModel() {
    val track = c.tracks.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val units = c.tracks.observeUnits(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val logs = c.db.logs().observeFor(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sessions = c.db.sessions().observeFor(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val answers = c.db.feedback().observeAnswers(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val nextStep = MutableStateFlow<String?>(null)
    val asking = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val tooMany = MutableStateFlow<List<Track>>(emptyList())
    val successor = MutableStateFlow<Long?>(null)
    val clock = c.clock

    init {
        viewModelScope.launch {
            c.db.nextSteps().observeOpen().collect { steps -> nextStep.value = steps.find { it.trackId == id }?.text }
        }
    }

    fun activate() = viewModelScope.launch {
        when (val a = c.tracks.activate(id)) {
            is Activation.TooMany -> tooMany.value = a.active
            Activation.Done -> Unit
        }
    }

    fun pauseAndActivate(other: Track?) = viewModelScope.launch {
        if (other != null) {
            c.tracks.pause(other.id, null)
            c.tracks.activate(id)
        }
        tooMany.value = emptyList()
    }

    fun pause(reason: String) = viewModelScope.launch { c.tracks.pause(id, reason) }
    fun complete(finalState: String) = viewModelScope.launch { c.tracks.complete(id, finalState) }
    fun abort(reason: String) = viewModelScope.launch { c.tracks.abort(id, reason) }

    /** A revision track from the WEAK units (spec 6.5). */
    fun revise() = viewModelScope.launch {
        val t = track.value ?: return@launch
        successor.value = c.tracks.createRevision(t, units.value.filter { it.status == UnitStatus.WEAK }.map { it.id })
    }

    fun setUnit(unit: TrackUnit, status: UnitStatus) = viewModelScope.launch { c.tracks.setUnitStatus(unit, status) }
    fun addUnit(title: String) = viewModelScope.launch { if (title.isNotBlank()) c.tracks.addUnit(id, title) }
    fun renameUnit(unit: TrackUnit, title: String) = viewModelScope.launch { c.tracks.renameUnit(unit, title) }
    fun deleteUnit(unit: TrackUnit) = viewModelScope.launch { c.tracks.deleteUnit(unit) }
    fun toggleCriterion(index: Int, met: Boolean) = viewModelScope.launch { track.value?.let { c.tracks.setCriterionMet(it, index, met) } }
    fun deleteLog(log: LogEntry) = viewModelScope.launch { c.input.delete(log) }
    fun update(change: (Track) -> Track) = viewModelScope.launch { track.value?.let { c.tracks.update(change(it)) } }

    fun setRepos(text: String) = viewModelScope.launch {
        track.value?.let { c.tracks.update(it.copy(githubRepos = text.split(',', ' ', '\n').map(String::trim).filter { r -> r.contains('/') }, syncError = null)) }
        CommitSyncWorker.now(c.appContext)
    }

    /** Manual session add/edit (spec 5.5). */
    fun saveSession(existing: Session?, startAt: Long, minutes: Int) = viewModelScope.launch {
        val s = (existing ?: Session(trackId = id, startAt = startAt)).copy(startAt = startAt, endAt = startAt + minutes * 60_000L)
        if (existing == null) c.db.sessions().insert(s) else c.db.sessions().update(s)
    }

    fun deleteSession(session: Session) = viewModelScope.launch { c.db.sessions().deleteFinished(session.id) }

    fun ask(question: String, thinkHarder: Boolean) = viewModelScope.launch {
        val t = track.value ?: return@launch
        asking.value = true
        try {
            c.feedback.ask(t, question, thinkHarder)
        } catch (e: BudgetExceeded) {
            message.value = e.message
        } catch (e: Exception) {
            message.value = e.message
        } finally {
            asking.value = false
        }
    }
}

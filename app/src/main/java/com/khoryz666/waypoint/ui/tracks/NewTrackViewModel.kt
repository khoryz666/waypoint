package com.khoryz666.waypoint.ui.tracks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khoryz666.waypoint.AppContainer
import com.khoryz666.waypoint.data.Activation
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.domain.DraftCheckpoint
import com.khoryz666.waypoint.domain.DraftUnit
import com.khoryz666.waypoint.domain.HandoffResult
import com.khoryz666.waypoint.domain.Lang
import com.khoryz666.waypoint.domain.TrackDraft
import com.khoryz666.waypoint.domain.ValidDraft
import com.khoryz666.waypoint.domain.parseHandoff
import com.khoryz666.waypoint.domain.validateDraft
import com.khoryz666.waypoint.llm.Part
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Editable fields of the preview, as text (spec 4 step 4): goal, where you are, breakdown, deadline. */
data class DraftForm(
    val title: String = "",
    val initialState: String = "",
    val goal: String = "",
    /** One unit per line. */
    val units: String = "",
    val targetDate: String = "",
    /** One checkpoint per line: "what | by when". */
    val checkpoints: String = "",
    val repos: String = "",
) {
    fun toDraft(domain: Domain) = TrackDraft(
        domain = domain.name, title = title, initialState = initialState, goal = goal,
        checkpoints = checkpoints.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            line.split('|').map(String::trim).let { DraftCheckpoint(it[0], it.getOrNull(1)) }
        },
        targetDate = targetDate.ifBlank { null },
        units = units.lines().map { DraftUnit(it) },
    )

    companion object {
        fun of(d: ValidDraft) = DraftForm(
            d.title, d.initialState, d.goal, d.units.joinToString("\n"), d.targetDate?.toString().orEmpty(),
            d.goalCriteria.joinToString("\n") { c -> listOfNotNull(c.label, c.target).joinToString(" | ") },
        )
    }
}

enum class Stage { FORM, HANDOFF, PREVIEW }

class NewTrackViewModel(private val c: AppContainer, private val predecessorId: Long?) : ViewModel() {
    val stage = MutableStateFlow(Stage.FORM)
    val domain = MutableStateFlow(Domain.FUNDAMENTAL)
    val title = MutableStateFlow("")
    val initialState = MutableStateFlow("")
    val notes = MutableStateFlow("")
    val form = MutableStateFlow(DraftForm())
    val error = MutableStateFlow<String?>(null)
    val warnings = MutableStateFlow(emptyList<String>())
    val busy = MutableStateFlow(false)
    val tooMany = MutableStateFlow<List<Track>>(emptyList())
    val created = MutableStateFlow<Long?>(null)
    val hasApiKey = MutableStateFlow(false)
    private var predecessor: Track? = null
    private var lang = Lang.EN

    init {
        viewModelScope.launch {
            lang = c.settings.current().profile.language
            hasApiKey.value = c.settings.current().hasApiKey
            predecessorId?.let { id ->
                c.db.tracks().get(id)?.let { p ->
                    predecessor = p
                    domain.value = p.domain
                    title.value = p.title
                    initialState.value = p.finalState.orEmpty()
                }
            }
        }
    }

    /** The handoff prompt for the DeepSeek app (spec 4 step 2), in the UI language. */
    suspend fun prompt(): String {
        val p = predecessor
        val summary = p?.let { t ->
            val logs = c.db.logs().recentConfirmed(t.id, 10).joinToString("; ") { it.rawText ?: (it.structured?.let(com.khoryz666.waypoint.domain.Structured::encode) ?: "") }.take(1500)
            listOf(t.rollingSummary, logs).filter { it.isNotBlank() }.joinToString("\n")
        }
        return c.prompts.render(
            if (lang == Lang.ZH) "handoff_track.zh" else "handoff_track.en",
            mapOf(
                "domain" to domain.value.name, "title" to title.value, "initialState" to initialState.value, "notes" to notes.value,
                "predecessor" to (p != null), "prevGoal" to p?.goal, "prevFinalState" to p?.finalState, "prevSummary" to summary,
            ),
        )
    }

    fun toHandoff() {
        stage.value = Stage.HANDOFF
    }

    /** Drafts the whole track in-app from the short description, then opens the preview. */
    fun generate() = viewModelScope.launch {
        busy.value = true
        try {
            val draft = c.llm.json("draft_track", listOf(Part.Text(draftRequest())), 3000, TrackDraft.serializer())
            when (val r = validateDraft(draft, domain.value, lang)) {
                is HandoffResult.Ok -> show(r.draft)
                is HandoffResult.Failed -> { error.value = r.reason; stage.value = Stage.HANDOFF }
            }
        } catch (e: Exception) {
            error.value = e.message
            stage.value = Stage.HANDOFF
        } finally {
            busy.value = false
        }
    }

    private fun draftRequest(): String = buildString {
        append("Domain: ").append(domain.value.name)
        append("\nTitle: ").append(title.value)
        append("\nCurrent status: ").append(initialState.value)
        if (notes.value.isNotBlank()) append("\nAdditional context: ").append(notes.value)
        predecessor?.let {
            append("\nPrevious stage goal: ").append(it.goal)
            append("\nPrevious stage result: ").append(it.finalState.orEmpty())
        }
    }

    fun paste(text: String) {
        when (val r = parseHandoff(text, domain.value, lang)) {
            is HandoffResult.Ok -> show(r.draft)
            is HandoffResult.Failed -> error.value = r.reason
        }
    }

    /** One Flash call with the pasted text and the schema, JSON mode (spec 4, parse failure). */
    fun repair(text: String) = viewModelScope.launch {
        busy.value = true
        try {
            val draft = c.llm.json("repair_track_json", listOf(Part.Text("Domain: ${domain.value}\n\n$text")), 3000, TrackDraft.serializer())
            when (val r = validateDraft(draft, domain.value, lang)) {
                is HandoffResult.Ok -> show(r.draft)
                is HandoffResult.Failed -> { error.value = r.reason; manual() }
            }
        } catch (e: Exception) {
            error.value = e.message
            manual()
        } finally {
            busy.value = false
        }
    }

    /** The manual form with the same fields. */
    fun manual() {
        form.value = DraftForm(title = title.value, initialState = initialState.value)
        stage.value = Stage.PREVIEW
    }

    private fun show(d: ValidDraft) {
        error.value = null
        warnings.value = d.warnings
        form.value = DraftForm.of(d)
        stage.value = Stage.PREVIEW
    }

    fun save(activate: Boolean) = viewModelScope.launch {
        val r = validateDraft(form.value.toDraft(domain.value), domain.value, lang)
        val draft = (r as? HandoffResult.Ok)?.draft ?: run { error.value = (r as HandoffResult.Failed).reason; return@launch }
        val repos = form.value.repos.split(',', ' ', '\n').map(String::trim).filter { it.contains('/') }
        val id = c.tracks.createFromDraft(draft, predecessorId, repos)
        if (activate) {
            when (val a = c.tracks.activate(id)) {
                is Activation.TooMany -> { tooMany.value = a.active; created.value = null; pendingId = id; return@launch }
                Activation.Done -> Unit
            }
        }
        created.value = id
    }

    private var pendingId: Long? = null

    /** Resolves the 9th activation by pausing [pause] (spec 3). */
    fun pauseAndActivate(pause: Track?) = viewModelScope.launch {
        val id = pendingId ?: return@launch
        if (pause != null) {
            c.tracks.pause(pause.id, null)
            c.tracks.activate(id)
        }
        tooMany.value = emptyList()
        created.value = id
    }
}

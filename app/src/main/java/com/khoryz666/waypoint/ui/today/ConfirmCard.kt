package com.khoryz666.waypoint.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.khoryz666.waypoint.R
import com.khoryz666.waypoint.data.LogEntry
import com.khoryz666.waypoint.data.SleepRecord
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.data.TrackUnit
import com.khoryz666.waypoint.domain.BalanceKind
import com.khoryz666.waypoint.domain.ExerciseItem
import com.khoryz666.waypoint.domain.ExerciseSet
import com.khoryz666.waypoint.domain.Night
import com.khoryz666.waypoint.domain.ParseStatus
import com.khoryz666.waypoint.domain.Structured
import com.khoryz666.waypoint.domain.formatMinutes
import com.khoryz666.waypoint.domain.formatMoney
import com.khoryz666.waypoint.domain.parseMinor
import com.khoryz666.waypoint.ingest.Parser
import com.khoryz666.waypoint.ingest.ROUTE_CONFIDENCE
import com.khoryz666.waypoint.ingest.SleepConflict
import com.khoryz666.waypoint.ui.ChoiceChip
import com.khoryz666.waypoint.ui.Muted
import com.khoryz666.waypoint.ui.SectionCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The editable confirm card (spec 5.3): parsed fields per kind, the routed track with a change
 * button, then ✓ Save, Edit raw or Discard. Unconfirmed cards stay on Today until handled.
 */
@Composable
fun ConfirmCard(
    log: LogEntry,
    tracks: List<Track>,
    unitsByTrack: Map<Long, List<TrackUnit>>,
    existingSleep: Map<String, SleepRecord>,
    currency: String,
    onSave: (Structured, Long?, Map<String, SleepConflict>) -> Unit,
    onReparse: (String, Long?) -> Unit,
    onDiscard: () -> Unit,
) {
    var trackId by remember(log.id, log.trackId) { mutableStateOf(log.trackId) }
    var editing by remember(log.id, log.parseStatus) { mutableStateOf(log.structured ?: Parser.blankFor(tracks.find { it.id == log.trackId }?.domain)) }
    var editRaw by remember(log.id) { mutableStateOf(false) }
    var raw by remember(log.id) { mutableStateOf(log.rawText.orEmpty()) }
    val sleepChoices = remember(log.id) { mutableStateMapOf<String, SleepConflict>() }
    val track = tracks.find { it.id == trackId }
    val needsTrack = log.parseStatus == ParseStatus.PARSED && (log.confidence ?: 1.0) < ROUTE_CONFIDENCE && trackId != null && log.trackId == trackId
    val trackless = editing is Structured.Sleep || editing is Structured.Balance

    SectionCard(title = stringResource(R.string.confirm_title)) {
        log.rawText?.takeIf { !editRaw }?.let { Muted("“$it”") }
        when (log.parseStatus) {
            ParseStatus.PENDING -> Text(stringResource(R.string.pending_parse), color = MaterialTheme.colorScheme.secondary)
            ParseStatus.FAILED -> Text(stringResource(R.string.parse_failed, log.parseError.orEmpty()), color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
        if (editRaw) {
            OutlinedTextField(raw, { raw = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.edit_raw)) })
            Row {
                TextButton(onClick = { onReparse(raw, trackId); editRaw = false }) { Text(stringResource(R.string.parse_again)) }
                TextButton(onClick = { editRaw = false }) { Text(stringResource(R.string.cancel)) }
            }
            return@SectionCard
        }
        if (log.parseStatus != ParseStatus.PENDING) {
            if (!trackless) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.track_label), Modifier.width(72.dp))
                    ChoiceChip(track?.title ?: stringResource(R.string.pick_track), listOf<Track?>(null) + tracks.filter { it.status.name == "ACTIVE" || it.id == trackId }, { it?.title ?: "—" }, { trackId = it?.id; if (log.parseStatus == ParseStatus.FAILED) editing = Parser.blankFor(it?.domain) })
                }
                if (needsTrack) Text(stringResource(R.string.low_confidence), color = MaterialTheme.colorScheme.secondary)
                if (log.parseStatus == ParseStatus.FAILED) KindPicker(editing) { editing = it }
            }
            StructuredEditor(editing, track, unitsByTrack[trackId].orEmpty(), existingSleep, sleepChoices, currency) { editing = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (log.parseStatus != ParseStatus.PENDING) {
                val unreadable = (editing as? Structured.Sleep)?.let { it.nights.isEmpty() } == true
                Button(
                    onClick = { onSave(editing, trackId.takeIf { !trackless }, sleepChoices.toMap()) },
                    enabled = !unreadable && (trackless || trackId != null || editing is Structured.Note || editing is Structured.Session),
                ) { Text(stringResource(R.string.save)) }
            }
            if (log.rawText != null || log.parseStatus == ParseStatus.FAILED) TextButton(onClick = { editRaw = true }) { Text(stringResource(R.string.edit_raw)) }
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.discard)) }
        }
    }
}

/** For the manual form: which kind of log this is. */
@Composable
private fun KindPicker(current: Structured, onPick: (Structured) -> Unit) {
    val kinds = listOf(
        stringResource(R.string.kind_fundamental) to Structured.Fundamental(),
        stringResource(R.string.kind_math) to Structured.Math(),
        stringResource(R.string.kind_reading) to Structured.Reading(),
        stringResource(R.string.kind_project) to Structured.Project(),
        stringResource(R.string.kind_exercise) to Structured.Exercise(),
        stringResource(R.string.kind_note) to Structured.Note(),
    )
    ChoiceChip(kinds.firstOrNull { it.second::class == current::class }?.first ?: "—", kinds, { it.first }, { onPick(it.second) })
}

@Composable
private fun StructuredEditor(
    s: Structured,
    track: Track?,
    units: List<TrackUnit>,
    existingSleep: Map<String, SleepRecord>,
    sleepChoices: MutableMap<String, SleepConflict>,
    currency: String,
    onChange: (Structured) -> Unit,
) {
    when (s) {
        is Structured.Fundamental -> {
            UnitChecklist(units, s.unitsDone) { onChange(s.copy(unitsDone = it)) }
            if (s.newUnits.isNotEmpty()) Muted(stringResource(R.string.new_units, s.newUnits.joinToString(", ")))
            NotesField(s.notes) { onChange(s.copy(notes = it)) }
        }
        is Structured.Math -> {
            OutlinedTextField(s.section, { onChange(s.copy(section = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.math_section)) }, singleLine = true)
            OutlinedTextField(s.problemsAttempted, { onChange(s.copy(problemsAttempted = it, attemptedCount = com.khoryz666.waypoint.domain.countProblems(it))) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.math_attempted)) }, singleLine = true)
            ListField(stringResource(R.string.math_stuck), s.stuck) { onChange(s.copy(stuck = it)) }
            ListField(stringResource(R.string.math_check), s.checkProblems) { onChange(s.copy(checkProblems = it)) }
            UnitChecklist(units, s.unitsDone) { onChange(s.copy(unitsDone = it)) }
        }
        is Structured.Reading -> {
            OutlinedTextField(s.pagesOrChapter, { onChange(s.copy(pagesOrChapter = it)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.reading_where)) }, singleLine = true)
            ListField(stringResource(R.string.reading_ideas), s.keyIdeas) { onChange(s.copy(keyIdeas = it.take(5))) }
            UnitChecklist(units, s.unitsDone) { onChange(s.copy(unitsDone = it)) }
        }
        is Structured.Project -> {
            if (units.isNotEmpty()) Muted(stringResource(R.string.milestones_confirm))
            UnitChecklist(units, s.milestonesDone) { onChange(s.copy(milestonesDone = it)) }
            NotesField(s.notes) { onChange(s.copy(notes = it)) }
        }
        is Structured.Exercise -> ExerciseEditor(s, onChange)
        is Structured.Sleep -> {
            if (s.nights.isEmpty()) Text(stringResource(R.string.sleep_unreadable), color = MaterialTheme.colorScheme.error)
            s.nights.forEachIndexed { i, night ->
                NightRow(night, existingSleep[night.date], sleepChoices[night.date]) { changed, choice ->
                    onChange(s.copy(nights = s.nights.toMutableList().also { it[i] = changed }))
                    if (choice != null) sleepChoices[night.date] = choice
                }
            }
        }
        is Structured.Balance -> {
            var amount by remember { mutableStateOf(formatMoney(kotlin.math.abs(s.amountMinor), currency).substringAfter(' ').replace(",", "")) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(BalanceKind.DELTA to "±", BalanceKind.SET to "=").forEach { (k, l) -> FilterChip(selected = s.kind == k, onClick = { onChange(s.copy(kind = k)) }, label = { Text(l) }) }
                OutlinedTextField(
                    amount, { v -> amount = v; parseMinor(v.removePrefix("-"), currency)?.let { m -> onChange(s.copy(amountMinor = if (v.startsWith("-") || (s.amountMinor < 0 && s.kind == BalanceKind.DELTA && !v.startsWith("+"))) -m else m)) } },
                    Modifier.weight(1f), label = { Text(currency) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
            Muted(formatMoney(s.amountMinor, currency))
            NotesField(s.note.orEmpty()) { onChange(s.copy(note = it)) }
        }
        is Structured.Session -> {
            val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
            Text("${fmt.format(Instant.ofEpochMilli(s.startAt))} · ${formatMinutes(s.minutes.toLong())} · ${s.category}")
            MinutesField(s.minutes) { m -> onChange(s.copy(endAt = s.startAt + m * 60_000L)) }
        }
        is Structured.Note -> NotesField(s.text) { onChange(s.copy(text = it)) }
        is Structured.Commits -> Text(s.summary.ifEmpty { s.messages.joinToString("\n") })
        is Structured.Recall -> Text(s.question)
    }
    track?.let { Muted(com.khoryz666.waypoint.ui.domainLabel(it.domain)) }
}

@Composable
private fun UnitChecklist(units: List<TrackUnit>, checked: List<Long>, onChange: (List<Long>) -> Unit) {
    if (units.isEmpty()) return
    // Checked units first, then the next open ones, so long outlines stay short on the card.
    val shown = (units.filter { it.id in checked } + units.filter { it.id !in checked && it.status.name != "DONE" }.take(5)).distinct()
    Column {
        shown.forEach { u ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = u.id in checked, onCheckedChange = { on -> onChange(if (on) checked + u.id else checked - u.id) })
                Text(u.title, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun NotesField(value: String, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.notes)) }, maxLines = 4)

@Composable
private fun ListField(label: String, values: List<String>, onChange: (List<String>) -> Unit) {
    var text by remember { mutableStateOf(values.joinToString(", ")) }
    OutlinedTextField(text, { text = it; onChange(it.split(',', '，', '、').map(String::trim).filter(String::isNotEmpty)) }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}

@Composable
private fun MinutesField(minutes: Int, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(minutes.toString()) }
    OutlinedTextField(text, { text = it; it.toIntOrNull()?.let(onChange) }, label = { Text(stringResource(R.string.minutes)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
private fun ExerciseEditor(s: Structured.Exercise, onChange: (Structured) -> Unit) {
    s.exercises.forEachIndexed { i, item ->
        var text by remember(item) { mutableStateOf(setsText(item)) }
        OutlinedTextField(
            text, { v -> text = v; onChange(s.copy(exercises = s.exercises.toMutableList().also { it[i] = parseSets(item.name, v) })) },
            Modifier.fillMaxWidth(), label = { Text(item.name) }, singleLine = true,
        )
    }
    Muted(stringResource(R.string.sets_hint))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var swim by remember { mutableStateOf(s.swimMin?.toString().orEmpty()) }
        var rpe by remember { mutableStateOf(s.rpe?.toString().orEmpty()) }
        OutlinedTextField(swim, { swim = it; onChange(s.copy(swimMin = it.toIntOrNull())) }, Modifier.weight(1f), label = { Text(stringResource(R.string.swim_min)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(rpe, { rpe = it; onChange(s.copy(rpe = it.toIntOrNull()?.coerceIn(1, 10))) }, Modifier.weight(1f), label = { Text("RPE") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
    OutlinedTextField(s.pain.orEmpty(), { onChange(s.copy(pain = it.ifBlank { null })) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.pain)) }, singleLine = true)
}

/** "10×12, 10×12, 8×12" - reps × kg per set; "30s" for holds. */
internal fun setsText(item: ExerciseItem): String = item.sets.joinToString(", ") { set ->
    when {
        set.seconds != null -> "${set.seconds}s"
        set.weightKg != null -> "${set.reps ?: 0}×${set.weightKg.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }}"
        else -> "${set.reps ?: 0}"
    } + (set.assisted?.takeIf { it != "none" }?.let { " $it" } ?: "")
}

internal fun parseSets(name: String, text: String): ExerciseItem = ExerciseItem(
    name,
    text.split(',', '，').map(String::trim).filter(String::isNotEmpty).map { part ->
        val assisted = listOf("band", "negative").firstOrNull { part.contains(it) }
        val core = part.replace("band", "").replace("negative", "").trim()
        when {
            core.endsWith("s") -> ExerciseSet(seconds = core.dropLast(1).trim().toIntOrNull(), assisted = assisted)
            core.contains('×') || core.contains('x') -> core.split('×', 'x').let { ExerciseSet(reps = it[0].trim().toIntOrNull(), weightKg = it.getOrNull(1)?.trim()?.removeSuffix("kg")?.toDoubleOrNull(), assisted = assisted) }
            else -> ExerciseSet(reps = core.toIntOrNull(), assisted = assisted)
        }
    },
)

@Composable
private fun NightRow(night: Night, existing: SleepRecord?, choice: SleepConflict?, onChange: (Night, SleepConflict?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${night.date} · ${night.bedAt ?: "?"} → ${night.wakeAt ?: "?"}", style = MaterialTheme.typography.titleSmall)
        var total by remember(night.date) { mutableStateOf(night.totalMin.toString()) }
        OutlinedTextField(total, { total = it; it.toIntOrNull()?.let { m -> onChange(night.copy(totalMin = m), null) } }, label = { Text(stringResource(R.string.sleep_total_min)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Muted(listOfNotNull(night.deepMin?.let { "deep $it" }, night.lightMin?.let { "light $it" }, night.remMin?.let { "REM $it" }, night.awakeMin?.let { "awake $it" }, night.napMin?.let { "nap $it" }, night.score?.let { "score $it" }).joinToString(" · "))
        if (existing != null) {
            Text(stringResource(R.string.sleep_exists, formatMinutes(existing.totalMin.toLong())), color = MaterialTheme.colorScheme.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = choice != SleepConflict.USE_NEW, onClick = { onChange(night, SleepConflict.KEEP_OLD) }, label = { Text(stringResource(R.string.keep_old)) })
                FilterChip(selected = choice == SleepConflict.USE_NEW, onClick = { onChange(night, SleepConflict.USE_NEW) }, label = { Text(stringResource(R.string.use_new)) })
            }
        }
    }
}

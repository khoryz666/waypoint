package com.khoryz666.waypoint.ui.tracks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.khoryz666.waypoint.R
import com.khoryz666.waypoint.data.LogEntry
import com.khoryz666.waypoint.data.Session
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.data.TrackUnit
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.domain.Structured
import com.khoryz666.waypoint.domain.TrackStatus
import com.khoryz666.waypoint.domain.UnitStatus
import com.khoryz666.waypoint.domain.formatDuration
import com.khoryz666.waypoint.ui.ConfirmDialog
import com.khoryz666.waypoint.ui.Muted
import com.khoryz666.waypoint.ui.SectionCard
import com.khoryz666.waypoint.ui.TextDialog
import com.khoryz666.waypoint.ui.domainLabel
import com.khoryz666.waypoint.ui.statusLabel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Dialog { PAUSE, COMPLETE, ABORT, ADD_UNIT, REPOS, SESSION, NEXT_LEVEL }

@Composable
fun TrackDetailScreen(vm: TrackDetailViewModel, onBack: () -> Unit, onNextLevel: (Long) -> Unit, onOpenTrack: (Long) -> Unit) {
    val track by vm.track.collectAsState()
    val units by vm.units.collectAsState()
    val logs by vm.logs.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val nextStep by vm.nextStep.collectAsState()
    val tooMany by vm.tooMany.collectAsState()
    val successor by vm.successor.collectAsState()
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var editingSession by remember { mutableStateOf<Session?>(null) }
    LaunchedEffect(successor) { successor?.let(onOpenTrack) }
    val t = track ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t.title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(title = "${domainLabel(t.domain)} · ${statusLabel(t.status)}") {
                    Text(t.goal, style = MaterialTheme.typography.titleMedium)
                    if (t.initialState.isNotBlank()) Muted(stringResource(R.string.started_from, t.initialState))
                    t.targetDate?.let { Muted(stringResource(R.string.target_date_x, it.toString())) }
                    t.finalState?.let { Text(stringResource(R.string.final_state_x, it)) }
                    t.endReason?.let { Text(stringResource(R.string.end_reason_x, it)) }
                    t.pauseReason?.let { Muted(stringResource(R.string.pause_reason_x, it)) }
                    if (t.status != TrackStatus.DRAFT && units.isNotEmpty()) Progress(units)
                    t.syncError?.let { Text(stringResource(R.string.sync_failed, it), color = MaterialTheme.colorScheme.error) }
                    LifecycleActions(t, units, vm) { dialog = it }
                }
            }
            if (t.goalCriteria.isNotEmpty()) item {
                SectionCard(title = stringResource(R.string.checkpoints)) {
                    t.goalCriteria.forEachIndexed { i, c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = c.met, onCheckedChange = { vm.toggleCriterion(i, it) })
                            Column {
                                Text(c.label)
                                listOfNotNull(c.metric, c.target).takeIf { it.isNotEmpty() }?.let { Muted(it.joinToString(" · ")) }
                            }
                        }
                    }
                }
            }
            nextStep?.let { item { SectionCard(title = stringResource(R.string.next_step)) { Text(it) } } }
            if (t.planNotes.isNotBlank()) item { SectionCard(title = stringResource(R.string.plan_notes)) { Text(t.planNotes) } }
            if (t.domain == Domain.PROJECT) item {
                SectionCard(title = stringResource(R.string.github_repos)) {
                    Text(t.githubRepos.joinToString("\n").ifEmpty { "—" })
                    TextButton(onClick = { dialog = Dialog.REPOS }) { Text(stringResource(R.string.edit)) }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.outline) + " · ${units.count { it.status == UnitStatus.DONE }}/${units.size}") {
                    units.forEach { UnitRow(it, vm) }
                    TextButton(onClick = { dialog = Dialog.ADD_UNIT }) { Text(stringResource(R.string.add_unit)) }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.sessions)) {
                    sessions.take(10).forEach { s ->
                        Row(Modifier.fillMaxWidth().clickable { editingSession = s; dialog = Dialog.SESSION }, verticalAlignment = Alignment.CenterVertically) {
                            Text(fmt(s.startAt), Modifier.weight(1f))
                            Text(formatDuration(s.durationMs(vm.clock.now())))
                        }
                    }
                    TextButton(onClick = { editingSession = null; dialog = Dialog.SESSION }) { Text(stringResource(R.string.add_session)) }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.logs)) {
                    if (logs.isEmpty()) Muted(stringResource(R.string.no_logs))
                    logs.take(50).forEach { LogRow(it, units, vm::deleteLog) }
                }
            }
        }
    }

    when (dialog) {
        Dialog.PAUSE -> TextDialog(stringResource(R.string.pause), label = stringResource(R.string.optional_reason), onDismiss = { dialog = null }) { vm.pause(it); dialog = null }
        Dialog.COMPLETE -> TextDialog(stringResource(R.string.complete), label = stringResource(R.string.final_state), required = true, onDismiss = { dialog = null }) {
            vm.complete(it)
            dialog = if (t.domain.incremental) Dialog.NEXT_LEVEL else null
        }
        Dialog.ABORT -> TextDialog(stringResource(R.string.abort), label = stringResource(R.string.end_reason), required = true, onDismiss = { dialog = null }) { vm.abort(it); dialog = null }
        Dialog.ADD_UNIT -> TextDialog(stringResource(R.string.add_unit), singleLine = true, onDismiss = { dialog = null }) { vm.addUnit(it); dialog = null }
        Dialog.REPOS -> TextDialog(stringResource(R.string.github_repos), initial = t.githubRepos.joinToString(", "), label = "owner/repo, owner/repo", onDismiss = { dialog = null }) { vm.setRepos(it); dialog = null }
        Dialog.NEXT_LEVEL -> ConfirmDialog(stringResource(R.string.start_next_level), stringResource(R.string.start_next_level_body), onDismiss = { dialog = null }) { dialog = null; onNextLevel(t.id) }
        Dialog.SESSION -> SessionDialog(editingSession, onDismiss = { dialog = null }, onDelete = { editingSession?.let(vm::deleteSession); dialog = null }) { start, minutes -> vm.saveSession(editingSession, start, minutes); dialog = null }
        null -> Unit
    }
    if (tooMany.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { vm.pauseAndActivate(null) },
            title = { Text(stringResource(R.string.too_many_active)) },
            text = { Column { Text(stringResource(R.string.too_many_active_body)); tooMany.forEach { o -> TextButton(onClick = { vm.pauseAndActivate(o) }) { Text(stringResource(R.string.pause_x, o.title)) } } } },
            confirmButton = { TextButton(onClick = { vm.pauseAndActivate(null) }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun LifecycleActions(t: Track, units: List<TrackUnit>, vm: TrackDetailViewModel, open: (Dialog) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (t.status) {
            TrackStatus.DRAFT, TrackStatus.PAUSED -> Button(onClick = vm::activate) { Text(stringResource(R.string.activate)) }
            TrackStatus.ACTIVE -> OutlinedButton(onClick = { open(Dialog.PAUSE) }) { Text(stringResource(R.string.pause)) }
            else -> Unit
        }
        if (t.status == TrackStatus.ACTIVE || t.status == TrackStatus.PAUSED) {
            OutlinedButton(onClick = { open(Dialog.COMPLETE) }) { Text(stringResource(R.string.complete)) }
            TextButton(onClick = { open(Dialog.ABORT) }) { Text(stringResource(R.string.abort)) }
        }
        if (t.status == TrackStatus.COMPLETED && t.domain.incremental) OutlinedButton(onClick = { open(Dialog.NEXT_LEVEL) }) { Text(stringResource(R.string.start_next_level)) }
        if (t.status == TrackStatus.COMPLETED && (t.domain == Domain.FUNDAMENTAL || t.domain == Domain.MATH) && units.any { it.status == UnitStatus.WEAK }) {
            OutlinedButton(onClick = vm::revise) { Text(stringResource(R.string.revise_weak)) }
        }
    }
}

@Composable
private fun UnitRow(unit: TrackUnit, vm: TrackDetailViewModel) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val mark = unitMark(unit.status)
    Row(Modifier.fillMaxWidth().clickable { menu = true }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(mark, Modifier.padding(end = 8.dp), color = if (unit.status == UnitStatus.WEAK) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Text(unit.title, Modifier.weight(1f))
        unit.nextReviewAt?.let { Muted(DateTimeFormatter.ofPattern("MM-dd").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it))) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            UnitStatus.entries.forEach { s -> DropdownMenuItem(text = { Text(unitMark(s) + "  " + unitStatusLabel(s)) }, onClick = { menu = false; vm.setUnit(unit, s) }) }
            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; rename = true })
            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; vm.deleteUnit(unit) })
        }
    }
    if (rename) TextDialog(stringResource(R.string.rename), initial = unit.title, singleLine = true, onDismiss = { rename = false }) { vm.renameUnit(unit, it); rename = false }
}

private fun unitMark(status: UnitStatus) = when (status) {
    UnitStatus.TODO -> "○"
    UnitStatus.DOING -> "◐"
    UnitStatus.DONE -> "●"
    UnitStatus.WEAK -> "◍"
}

@Composable
private fun unitStatusLabel(status: UnitStatus): String = stringResource(
    when (status) {
        UnitStatus.TODO -> R.string.unit_todo
        UnitStatus.DOING -> R.string.unit_doing
        UnitStatus.DONE -> R.string.unit_done
        UnitStatus.WEAK -> R.string.unit_weak
    },
)

/** How far through the outline the track is, shown once it's activated. */
@Composable
private fun Progress(units: List<TrackUnit>) {
    val done = units.count { it.status == UnitStatus.DONE }
    LinearProgressIndicator(progress = { done.toFloat() / units.size }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    Muted(stringResource(R.string.progress_x, done, units.size, done * 100 / units.size))
}

@Composable
private fun LogRow(log: LogEntry, units: List<TrackUnit>, onDelete: (LogEntry) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { confirm = true }.padding(vertical = 4.dp)) {
        Muted(fmt(log.occurredAt) + " · " + log.kind.name.lowercase() + if (log.parseStatus.name != "CONFIRMED") " · ${log.parseStatus.name.lowercase()}" else "")
        Text(describe(log, units), maxLines = 3)
    }
    if (confirm) ConfirmDialog(stringResource(R.string.delete_log), describe(log, units), stringResource(R.string.delete), onDismiss = { confirm = false }) { onDelete(log); confirm = false }
}

private fun describe(log: LogEntry, units: List<TrackUnit>): String {
    val names = units.associate { it.id to it.title }
    return when (val s = log.structured) {
        is Structured.Recall -> s.question + (s.grade?.let { " → ${it.name.lowercase()}" } ?: if (s.skipped) " → skipped" else "")
        is Structured.Commits -> s.summary.ifEmpty { s.messages.joinToString("; ") }
        else -> log.rawText ?: log.unitIds.mapNotNull(names::get).joinToString(", ")
    }
}

private fun fmt(ms: Long) = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

@Composable
private fun SessionDialog(existing: Session?, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (Long, Int) -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = existing?.let { Instant.ofEpochMilli(it.startAt).atZone(zone) }
    var date by remember { mutableStateOf((start?.toLocalDate() ?: LocalDate.now()).toString()) }
    var time by remember { mutableStateOf((start?.toLocalTime() ?: LocalTime.now().withSecond(0).withNano(0)).toString().take(5)) }
    var minutes by remember { mutableStateOf(existing?.endAt?.let { ((it - existing.startAt) / 60_000).toString() } ?: "30") }
    val parsed = runCatching { LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.add_session else R.string.edit_session)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(date, { date = it }, label = { Text("YYYY-MM-DD") }, singleLine = true)
                OutlinedTextField(time, { time = it }, label = { Text("HH:mm") }, singleLine = true)
                OutlinedTextField(minutes, { minutes = it }, label = { Text(stringResource(R.string.minutes)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(parsed!!, minutes.toInt()) }, enabled = parsed != null && minutes.toIntOrNull() != null) { Text(stringResource(R.string.ok)) } },
        dismissButton = {
            Row {
                if (existing?.endAt != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

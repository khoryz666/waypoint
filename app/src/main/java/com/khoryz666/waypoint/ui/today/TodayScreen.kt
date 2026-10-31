package com.khoryz666.waypoint.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import com.khoryz666.waypoint.data.ApiProblem
import com.khoryz666.waypoint.data.LogEntry
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.data.TrackUnit
import com.khoryz666.waypoint.domain.BudgetLevel
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.domain.Grade
import com.khoryz666.waypoint.domain.HOUR_MS
import com.khoryz666.waypoint.domain.RecallSource
import com.khoryz666.waypoint.domain.Structured
import com.khoryz666.waypoint.domain.formatDuration
import com.khoryz666.waypoint.domain.formatElapsed
import com.khoryz666.waypoint.ingest.CommentState
import com.khoryz666.waypoint.ingest.FeedbackCard
import com.khoryz666.waypoint.ingest.SkipReason
import com.khoryz666.waypoint.ui.Banner
import com.khoryz666.waypoint.ui.ChoiceChip
import com.khoryz666.waypoint.ui.Muted
import com.khoryz666.waypoint.ui.SectionCard
import com.khoryz666.waypoint.ui.TargetRow
import com.khoryz666.waypoint.ui.theme.Numeric
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(vm: TodayViewModel, onSettings: () -> Unit, onNewTrack: () -> Unit, onDailyClose: () -> Unit) {
    val state by vm.state.collectAsState()
    val feedback by vm.feedback.collectAsState()
    val inbox by vm.inbox.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val day = state.day
    val s = state.settings
    val active = state.tracks.filter { it.status.name == "ACTIVE" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(day?.date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(androidx.compose.ui.platform.LocalConfiguration.current.locales[0])).orEmpty())
                        day?.let { Muted(stringResource(R.string.streak_days, it.streak)) }
                    }
                },
                actions = {
                    day?.let { d ->
                        if (d.types.isNotEmpty()) ChoiceChip(d.dayType?.name ?: stringResource(R.string.day_type_none), d.types, { it.name }, vm::setDayType, selected = d.dayTypeAssigned)
                    }
                    IconButton(onClick = onDailyClose) { Text("☾") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
                },
            )
        },
        bottomBar = { InputBar(active, inbox, vm::submit) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            message?.let { item { Banner(it, stringResource(R.string.ok)) { vm.message.value = null } } }
            banners(state, onSettings)
            if (s.tomorrowTop != null && s.tomorrowTopDate == day?.date) item { SectionCard(title = stringResource(R.string.todays_one_thing)) { Text(s.tomorrowTop, style = MaterialTheme.typography.titleMedium) } }
            if (state.top3.isNotEmpty()) item { SectionCard(title = stringResource(R.string.top3)) { state.top3.forEachIndexed { i, t -> Text("${i + 1}. $t") } } }
            feedback?.let { item { FeedbackView(it, onUndo = { vm.undo(it.logId) }, onDismiss = vm::dismissFeedback) } }
            items(state.cards, key = { "card-${it.id}" }) { log ->
                ConfirmCard(
                    log, state.tracks, state.unitsByTrack, state.existingSleep, s.profile.currency,
                    onSave = { st, track, sleep -> vm.confirm(log, st, track, sleep) },
                    onReparse = { text, track -> vm.reparse(log, text, track) },
                    onDiscard = { vm.discard(log) },
                )
            }
            items(state.recalls, key = { "recall-${it.id}" }) { log -> RecallCard(log, state.tracks, s.hasApiKey && s.apiProblem == null, vm) }
            if (state.due.isNotEmpty()) item { DueCard(state.due, vm::openRevision) }
            if (state.tracks.isEmpty()) item {
                SectionCard {
                    Text(stringResource(R.string.create_first_track), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onNewTrack) { Text(stringResource(R.string.new_track)) }
                }
            }
            day?.let { d ->
                if (d.minimum.lines.isNotEmpty() || d.plan.lines.isNotEmpty()) item {
                    SectionCard(title = stringResource(R.string.targets)) {
                        if (d.minimum.lines.isNotEmpty()) {
                            Text(stringResource(R.string.minimum) + if (d.minimum.met) " ✓" else "", style = MaterialTheme.typography.titleSmall, color = if (d.minimum.met) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
                            d.minimum.lines.forEach { TargetRow(it) }
                        }
                        if (d.plan.lines.isNotEmpty()) {
                            Text(stringResource(R.string.plan) + if (d.plan.met) " ✓" else "", style = MaterialTheme.typography.titleSmall)
                            d.plan.lines.forEach { TargetRow(it) }
                        }
                    }
                }
                item { TimerCard(d.running, d.now, d.time.totalFocusMs, active, state.tracks, vm) }
            }
            items(state.steps, key = { "step-${it.step.id}" }) { item -> StepCard(item, vm) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.banners(state: TodayState, onSettings: () -> Unit) {
    val s = state.settings
    when (s.apiProblem) {
        ApiProblem.KEY_INVALID -> item { Banner(stringResource(R.string.api_key_invalid), stringResource(R.string.settings), onSettings) }
        ApiProblem.NO_BALANCE -> item { Banner(stringResource(R.string.api_no_balance), stringResource(R.string.settings), onSettings) }
        null -> if (!s.hasApiKey) item { Banner(stringResource(R.string.api_key_missing), stringResource(R.string.settings), onSettings) }
    }
    when (state.budget) {
        BudgetLevel.WARNING -> item { Banner(stringResource(R.string.budget_warning)) }
        BudgetLevel.CAPPED -> item { Banner(stringResource(R.string.budget_capped), stringResource(R.string.settings), onSettings) }
        BudgetLevel.OK -> Unit
    }
    val b = s.backup
    val now = System.currentTimeMillis()
    if (b.hasKey && (b.needsAuthorization || (b.lastAttemptAt != null && b.lastError != null && now - (b.lastSuccessAt ?: 0) > 48 * HOUR_MS))) {
        item { Banner(stringResource(if (b.needsAuthorization) R.string.backup_reauthorize else R.string.backup_failing), stringResource(R.string.settings), onSettings) }
    }
}

@Composable
private fun FeedbackView(card: FeedbackCard, onUndo: () -> Unit, onDismiss: () -> Unit) {
    SectionCard(color = MaterialTheme.colorScheme.primaryContainer) {
        Text(card.receipt, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        when (card.state) {
            CommentState.STREAMING -> if (card.comment.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth()) else Text(card.comment)
            CommentState.DONE, CommentState.FAILED -> if (card.comment.isNotEmpty()) Text(card.comment)
            CommentState.SKIPPED_BUDGET -> Muted(stringResource(R.string.comment_skipped_budget))
            CommentState.NO_API -> Muted(stringResource(R.string.comment_no_api))
        }
        Row {
            TextButton(onClick = onUndo) { Text(stringResource(R.string.undo)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

@Composable
private fun RecallCard(log: LogEntry, tracks: List<Track>, apiAvailable: Boolean, vm: TodayViewModel) {
    val recall = log.structured as? Structured.Recall ?: return
    var answer by remember(log.id) { mutableStateOf("") }
    val title = when (recall.source) {
        RecallSource.OWNERSHIP -> stringResource(R.string.ownership_check)
        RecallSource.REVIEW -> stringResource(R.string.revision)
        RecallSource.UNIT -> stringResource(R.string.recall_check)
    }
    SectionCard(title = "$title · ${tracks.find { it.id == log.trackId }?.title.orEmpty()}") {
        Text(recall.question, style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(answer, { answer = it }, Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.answer_hint)) }, minLines = 2)
        if (apiAvailable) {
            Row {
                Button(onClick = { vm.answer(log, answer) }, enabled = answer.isNotBlank()) { Text(stringResource(R.string.submit)) }
                TextButton(onClick = { vm.skip(log) }) { Text(stringResource(R.string.skip)) }
            }
        } else {
            // Without the API: the user grades themselves, so revision still moves.
            Muted(stringResource(R.string.self_grade))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Grade.SOLID to R.string.grade_solid, Grade.PARTIAL to R.string.grade_partial, Grade.WEAK to R.string.grade_weak).forEach { (g, label) ->
                    OutlinedButton(onClick = { vm.answer(log, answer, g) }) { Text(stringResource(label)) }
                }
                TextButton(onClick = { vm.skip(log) }) { Text(stringResource(R.string.skip)) }
            }
        }
    }
}

@Composable
private fun DueCard(due: List<Pair<TrackUnit, Track>>, onOpen: (TrackUnit) -> Unit) {
    SectionCard(title = stringResource(R.string.revision)) {
        due.take(5).forEach { (unit, track) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(unit.title)
                    Muted(track.title)
                }
                FilledTonalButton(onClick = { onOpen(unit) }) { Text(stringResource(R.string.review_now)) }
            }
        }
        if (due.size > 5) Muted("+${due.size - 5}")
    }
}

@Composable
private fun TimerCard(running: com.khoryz666.waypoint.data.Session?, now: Long, focusMs: Long, active: List<Track>, all: List<Track>, vm: TodayViewModel) {
    SectionCard(title = stringResource(R.string.timer)) {
        if (running != null) {
            val label = all.find { it.id == running.trackId }?.title ?: stringResource(R.string.focus)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text(formatElapsed(running.durationMs(now)), style = MaterialTheme.typography.displayMedium.merge(Numeric))
                }
                Button(onClick = vm::stop) { Text(stringResource(R.string.stop)) }
            }
        }
        Muted(stringResource(R.string.focus_today, formatDuration(focusMs)))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            active.filter { running?.trackId != it.id }.forEach { t ->
                AssistChip(onClick = { vm.start(t.id) }, label = { Text(t.title, maxLines = 1) })
            }
        }
    }
}

@Composable
private fun StepCard(item: StepItem, vm: TodayViewModel) {
    var skipping by remember { mutableStateOf(false) }
    SectionCard(title = "${stringResource(R.string.next_step)} · ${item.track.title}") {
        Text(item.step.text, style = MaterialTheme.typography.bodyLarge)
        item.step.dueHint?.let { Muted(it) }
        Row {
            TextButton(onClick = { vm.stepDone(item.step) }) { Text(stringResource(R.string.done)) }
            if (item.track.domain == Domain.EXERCISE) TextButton(onClick = { skipping = !skipping }) { Text(stringResource(R.string.cant_today)) }
        }
        if (skipping) {
            var note by remember { mutableStateOf("") }
            var reason by remember { mutableStateOf<SkipReason?>(null) }
            val zh = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "zh"
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkipReason.entries.forEach { r -> FilterChip(selected = reason == r, onClick = { reason = r }, label = { Text(if (zh) r.zh else r.en) }) }
            }
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.optional_note)) })
            Button(onClick = { reason?.let { vm.cantDoToday(item.track, it, note.ifBlank { null }); skipping = false } }, enabled = reason != null) { Text(stringResource(R.string.get_fallback)) }
        }
    }
}

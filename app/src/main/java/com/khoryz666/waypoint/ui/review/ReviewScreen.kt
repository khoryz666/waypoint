package com.khoryz666.waypoint.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.khoryz666.waypoint.data.Review
import com.khoryz666.waypoint.data.ReviewQuestion
import com.khoryz666.waypoint.domain.ReviewPeriod
import com.khoryz666.waypoint.ui.Banner
import com.khoryz666.waypoint.ui.Muted
import com.khoryz666.waypoint.ui.SectionCard
import com.khoryz666.waypoint.ui.TextDialog

/** The review tab (spec 7): a short summary, a few questions about your tracks, next week. */
@Composable
fun ReviewScreen(vm: ReviewViewModel) {
    val review by vm.open.collectAsState()
    val reviews by vm.reviews.collectAsState()
    val questions by vm.questions.collectAsState()
    val tracks by vm.activeTracks.collectAsState()
    val summary by vm.summary.collectAsState()
    val answers by vm.answers.collectAsState()
    val fix by vm.fix.collectAsState()
    val top3 by vm.top3.collectAsState()
    val busy by vm.busy.collectAsState()
    val writing by vm.writing.collectAsState()
    val error by vm.error.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val lastWeekOpen by vm.lastWeekOpen.collectAsState()
    var editing by remember { mutableStateOf(false) }
    val r = review
    val submitted = r?.submittedAt != null
    val week = r?.period == ReviewPeriod.WEEK
    val previous = reviews.filter { it.period == ReviewPeriod.WEEK && it.submittedAt != null && r != null && it.start < r.start }.maxByOrNull { it.start }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_review)) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (lastWeekOpen) item { Banner(stringResource(R.string.last_week_unreviewed), stringResource(R.string.open)) { vm.openLastWeek() } }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = week, onClick = { vm.openThisWeek() }, label = { Text(stringResource(R.string.this_week)) }) }
                    item { FilterChip(selected = r?.period == ReviewPeriod.MONTH, onClick = { vm.openLastMonth() }, label = { Text(stringResource(R.string.monthly)) }) }
                    item { FilterChip(selected = r?.period == ReviewPeriod.YEAR, onClick = { vm.openThisYear() }, label = { Text(stringResource(R.string.yearly)) }) }
                }
            }
            if (r == null) return@LazyColumn
            item {
                SectionCard(title = "${r.start} – ${r.end}") {
                    summary.forEach { Text(it) }
                }
            }
            if (week) {
                previous?.oneThingToFix?.takeIf { it.isNotBlank() }?.let { item { SectionCard(title = stringResource(R.string.last_weeks_fix)) { Text(it) } } }
                item {
                    if (submitted) {
                        val asked = com.khoryz666.waypoint.data.ReviewRepository.decodeAnswers(r.answers)
                        if (asked.isNotEmpty()) SectionCard(title = stringResource(R.string.review_questions)) {
                            asked.forEach { (q, a) -> Muted(q); Text(a) }
                        }
                    } else {
                        QuestionsCard(questions, answers, tracks.isNotEmpty(), editing, writing, vm, onToggleEdit = { editing = !editing })
                    }
                }
            }
            if (!submitted) item {
                SectionCard(title = stringResource(if (week) R.string.next_week else R.string.narrative)) {
                    if (week) {
                        OutlinedTextField(fix, vm::setFix, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.one_thing_to_fix)) })
                        OutlinedTextField(top3, vm::setTop3, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.top3_next)) }, minLines = 3)
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(onClick = vm::submit, enabled = !busy) { Text(stringResource(if (week) R.string.submit_review else R.string.write_narrative)) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            if (submitted) item { NarrativeCard(r, busy, error, vm) }
            if (suggestions.isNotEmpty()) item {
                SectionCard(title = stringResource(R.string.reading_suggestions)) {
                    suggestions.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.topic + (s.book?.let { " · $it" } ?: ""), Modifier.weight(1f))
                            TextButton(onClick = { vm.accept(s) }) { Text(stringResource(R.string.add_as_draft)) }
                        }
                    }
                }
            }
            val past = reviews.filter { it.submittedAt != null && it.id != r.id }
            if (past.isNotEmpty()) item {
                SectionCard(title = stringResource(R.string.past_reviews)) {
                    past.forEach { p ->
                        Text(periodLabel(p), Modifier.fillMaxWidth().clickable { vm.show(p) }.padding(vertical = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionsCard(
    questions: List<ReviewQuestion>,
    answers: Map<String, String>,
    hasTracks: Boolean,
    editing: Boolean,
    writing: Boolean,
    vm: ReviewViewModel,
    onToggleEdit: () -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ReviewQuestion?>(null) }
    SectionCard(title = stringResource(R.string.review_questions)) {
        when {
            writing -> LinearProgressIndicator(Modifier.fillMaxWidth())
            questions.isEmpty() && !hasTracks -> Muted(stringResource(R.string.questions_need_track))
            else -> questions.forEach { q ->
                if (editing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(q.text, Modifier.weight(1f).clickable { renaming = q })
                        TextButton(onClick = { vm.deleteQuestion(q.id) }) { Text("✕") }
                    }
                } else {
                    OutlinedTextField(answers[q.text].orEmpty(), { vm.answer(q.text, it) }, Modifier.fillMaxWidth(), label = { Text(q.text) })
                }
            }
        }
        Row {
            if (editing) TextButton(onClick = { adding = true }) { Text(stringResource(R.string.add_question)) }
            if (hasTracks && !editing) TextButton(onClick = vm::writeQuestions, enabled = !writing) { Text(stringResource(R.string.rewrite_questions)) }
            if (questions.isNotEmpty() || editing) TextButton(onClick = onToggleEdit) { Text(stringResource(if (editing) R.string.done else R.string.edit)) }
        }
    }
    if (adding) TextDialog(stringResource(R.string.add_question), onDismiss = { adding = false }) { text ->
        if (text.isNotBlank()) vm.addQuestion(text.trim())
        adding = false
    }
    renaming?.let { q ->
        TextDialog(stringResource(R.string.edit), initial = q.text, onDismiss = { renaming = null }) { text ->
            if (text.isNotBlank()) vm.updateQuestion(q.copy(text = text.trim()))
            renaming = null
        }
    }
}

@Composable
private fun NarrativeCard(r: Review, busy: Boolean, error: String?, vm: ReviewViewModel) {
    SectionCard(title = stringResource(R.string.narrative)) {
        if (r.narrative.isNotBlank()) Text(r.narrative) else TextButton(onClick = vm::renarrate, enabled = !busy) { Text(stringResource(R.string.write_narrative)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (r.period == ReviewPeriod.WEEK) {
            if (r.oneThingToFix.isNotBlank()) { Muted(stringResource(R.string.one_thing_to_fix)); Text(r.oneThingToFix) }
            if (r.top3.isNotEmpty()) { Muted(stringResource(R.string.top3_next)); r.top3.forEachIndexed { i, t -> Text("${i + 1}. $t") } }
        }
    }
}

@Composable
private fun periodLabel(r: Review): String = when (r.period) {
    ReviewPeriod.WEEK -> stringResource(R.string.week_of, r.start.toString())
    ReviewPeriod.MONTH -> r.start.toString().take(7)
    ReviewPeriod.YEAR -> r.start.year.toString()
}

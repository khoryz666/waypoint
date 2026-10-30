package com.khoryz666.waypoint.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.khoryz666.waypoint.R
import com.khoryz666.waypoint.data.ChecklistQuestion
import com.khoryz666.waypoint.data.ReviewAnswer
import com.khoryz666.waypoint.data.ReviewRepository
import com.khoryz666.waypoint.domain.Lang
import com.khoryz666.waypoint.domain.QuestionType
import com.khoryz666.waypoint.domain.ReviewPeriod
import com.khoryz666.waypoint.domain.ReviewStats
import com.khoryz666.waypoint.domain.autoAnswer
import com.khoryz666.waypoint.domain.formatMinutes
import com.khoryz666.waypoint.domain.formatMoneyDelta
import com.khoryz666.waypoint.ui.Banner
import com.khoryz666.waypoint.ui.Muted
import com.khoryz666.waypoint.ui.SectionCard

/** The review tab (spec 7): this week's numbers filled in, the checklist, past reviews. */
@Composable
fun ReviewScreen(vm: ReviewViewModel, onEditChecklist: () -> Unit) {
    val review by vm.open.collectAsState()
    val reviews by vm.reviews.collectAsState()
    val pillars by vm.pillars.collectAsState()
    val questions by vm.questions.collectAsState()
    val answers by vm.answers.collectAsState()
    val reflection by vm.reflection.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val suggestions by vm.suggestions.collectAsState()
    val lastWeekOpen by vm.lastWeekOpen.collectAsState()
    val lang by vm.lang.collectAsState()
    val r = review
    val stats = r?.let { ReviewRepository.decodeStats(it.stats) }
    val submitted = r?.submittedAt != null
    val previous = reviews.filter { it.period == ReviewPeriod.WEEK && it.submittedAt != null && r != null && it.start < r.start }.maxByOrNull { it.start }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_review)) }, actions = { TextButton(onClick = onEditChecklist) { Text(stringResource(R.string.edit_checklist)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (lastWeekOpen && r?.start != null) item { Banner(stringResource(R.string.last_week_unreviewed), stringResource(R.string.open)) { vm.openLastWeek() } }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::openThisWeek) { Text(stringResource(R.string.this_week)) }
                    OutlinedButton(onClick = vm::openLastMonth) { Text(stringResource(R.string.monthly)) }
                    OutlinedButton(onClick = vm::openThisYear) { Text(stringResource(R.string.yearly)) }
                }
            }
            if (r != null && stats != null) {
                item { StatsCard(r.period, "${r.start} – ${r.end}", stats, lang) }
                previous?.oneThingToFix?.takeIf { it.isNotBlank() && r.period == ReviewPeriod.WEEK }?.let { fix -> item { SectionCard(title = stringResource(R.string.last_weeks_fix)) { Text(fix) } } }
                if (r.period == ReviewPeriod.WEEK) {
                    pillars.forEach { p ->
                        val qs = questions.filter { it.pillarId == p.id }
                        item(key = "pillar-${p.id}") {
                            SectionCard(title = p.name + if (p.noTargets) " · " + stringResource(R.string.no_targets) else "") {
                                qs.forEach { q -> QuestionRow(q, stats, answers[q.id] ?: ReviewAnswer(), lang, p.trackIds, enabled = !submitted) { change -> vm.answer(q.id, change) } }
                            }
                        }
                    }
                }
                item {
                    SectionCard(title = stringResource(R.string.reflection)) {
                        OutlinedTextField(reflection, { vm.reflection.value = it }, Modifier.fillMaxWidth(), enabled = !submitted, minLines = 2)
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (!submitted) Button(onClick = vm::submit, enabled = !busy) { Text(stringResource(R.string.submit_review)) }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (submitted) item {
                    SectionCard(title = stringResource(R.string.narrative)) {
                        if (r.narrative.isNotBlank()) Text(r.narrative) else TextButton(onClick = vm::renarrate, enabled = !busy) { Text(stringResource(R.string.write_narrative)) }
                        if (r.oneThingToFix.isNotBlank()) { Text(stringResource(R.string.one_thing_to_fix), style = MaterialTheme.typography.titleSmall); Text(r.oneThingToFix) }
                        if (r.top3.isNotEmpty()) { Text(stringResource(R.string.top3), style = MaterialTheme.typography.titleSmall); r.top3.forEachIndexed { i, t -> Text("${i + 1}. $t") } }
                    }
                }
                if (suggestions.isNotEmpty()) item {
                    SectionCard(title = stringResource(R.string.reading_suggestions)) {
                        suggestions.forEach { s ->
                            Row {
                                Text(s.topic + (s.book?.let { " · $it" } ?: ""), Modifier.weight(1f))
                                TextButton(onClick = { vm.accept(s) }) { Text(stringResource(R.string.add_as_draft)) }
                            }
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.past_reviews), style = MaterialTheme.typography.titleMedium) }
            items(reviews.filter { it.submittedAt != null }, key = { "past-${it.id}" }) { past ->
                SectionCard {
                    Row(Modifier.fillMaxWidth().clickable { vm.show(past) }.padding(vertical = 4.dp)) {
                        Text("${past.period.name.lowercase()} · ${past.start}", Modifier.weight(1f))
                        Muted(past.oneThingToFix.take(40))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(period: ReviewPeriod, title: String, stats: ReviewStats, lang: Lang) {
    SectionCard(title = "${period.name.lowercase()} · $title") {
        Text(stringResource(R.string.stats_focus, formatMinutes(stats.focusMinutes.toLong()), formatMinutes(stats.joyMinutes.toLong())))
        Text(stringResource(R.string.stats_minimum, stats.minimumDays, stats.daysWithMinimum, stats.planDays))
        Text(autoAnswer(com.khoryz666.waypoint.domain.QuestionKey.SLEEP_NIGHTS_IN_RANGE, stats, lang))
        Text(stringResource(R.string.stats_balance, formatMoneyDelta(stats.balance.endMinor - stats.balance.startMinor, stats.balance.currency), formatMoneyDelta(stats.balance.net30Minor, stats.balance.currency)))
        stats.tracks.forEach { t -> Muted("${t.title}: ${formatMinutes(t.focusMinutes.toLong())} · ${t.unitsDone} units · ${t.logs} logs") }
        if (stats.completed.isNotEmpty()) Muted("✓ " + stats.completed.joinToString("; "))
        if (stats.aborted.isNotEmpty()) Muted("✗ " + stats.aborted.joinToString("; "))
    }
}

@Composable
private fun QuestionRow(q: ChecklistQuestion, stats: ReviewStats, a: ReviewAnswer, lang: Lang, pillarTracks: List<Long>, enabled: Boolean, onChange: ((ReviewAnswer) -> ReviewAnswer) -> Unit) {
    Text(q.text, style = MaterialTheme.typography.titleSmall)
    when (q.type) {
        QuestionType.AUTO -> {
            Text(q.key?.let { autoAnswer(it, stats, lang, pillarTracks) }.orEmpty())
            OutlinedTextField(a.note, { v -> onChange { it.copy(note = v) } }, Modifier.fillMaxWidth(), enabled = enabled, placeholder = { Text(stringResource(R.string.optional_note)) }, singleLine = true)
        }
        QuestionType.YES_NO -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = a.yes == true, onClick = { onChange { it.copy(yes = true) } }, enabled = enabled, label = { Text(stringResource(R.string.yes)) })
                FilterChip(selected = a.yes == false, onClick = { onChange { it.copy(yes = false) } }, enabled = enabled, label = { Text(stringResource(R.string.no)) })
            }
            OutlinedTextField(a.note, { v -> onChange { it.copy(note = v) } }, Modifier.fillMaxWidth(), enabled = enabled, placeholder = { Text(stringResource(R.string.optional_note)) }, singleLine = true)
        }
        QuestionType.FREE_TEXT -> OutlinedTextField(a.text, { v -> onChange { it.copy(text = v) } }, Modifier.fillMaxWidth(), enabled = enabled, minLines = 2)
    }
}

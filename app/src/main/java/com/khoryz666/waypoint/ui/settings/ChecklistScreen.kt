package com.khoryz666.waypoint.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.khoryz666.waypoint.AppContainer
import com.khoryz666.waypoint.R
import com.khoryz666.waypoint.data.ChecklistQuestion
import com.khoryz666.waypoint.data.Pillar
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.domain.QuestionKey
import com.khoryz666.waypoint.domain.QuestionType
import com.khoryz666.waypoint.domain.parseChecklistMarkdown
import com.khoryz666.waypoint.domain.starterChecklist
import com.khoryz666.waypoint.ui.ChoiceChip
import com.khoryz666.waypoint.ui.ConfirmDialog
import com.khoryz666.waypoint.ui.LabeledRow
import com.khoryz666.waypoint.ui.Muted
import com.khoryz666.waypoint.ui.SectionCard
import com.khoryz666.waypoint.ui.TextDialog
import kotlinx.coroutines.launch

/** The weekly checklist editor (spec 7.2): pillars, questions and their types; Markdown import. */
@Composable
fun ChecklistScreen(c: AppContainer, onBack: () -> Unit) {
    val pillars by c.reviews.pillars.collectAsState(initial = emptyList())
    val questions by c.reviews.questions.collectAsState(initial = emptyList())
    val tracks by c.tracks.all.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var addingPillar by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.edit_checklist)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { addingPillar = true }) { Text(stringResource(R.string.add_pillar)) }
                    TextButton(onClick = { importing = true }) { Text(stringResource(R.string.import_markdown)) }
                    TextButton(onClick = { resetting = true }) { Text(stringResource(R.string.use_starter)) }
                }
            }
            items(pillars, key = { it.id }) { p -> PillarEditor(p, questions.filter { it.pillarId == p.id }, tracks, c) }
        }
    }
    if (addingPillar) TextDialog(stringResource(R.string.add_pillar), singleLine = true, required = true, onDismiss = { addingPillar = false }) { scope.launch { c.reviews.addPillar(it.trim()) }; addingPillar = false }
    if (importing) TextDialog(stringResource(R.string.import_markdown), label = stringResource(R.string.markdown_hint), onDismiss = { importing = false }) { md ->
        val drafts = parseChecklistMarkdown(md)
        if (drafts.isNotEmpty()) scope.launch { c.reviews.replaceChecklist(drafts) }
        importing = false
    }
    if (resetting) ConfirmDialog(stringResource(R.string.use_starter), stringResource(R.string.replace_checklist), onDismiss = { resetting = false }) {
        scope.launch { c.reviews.replaceChecklist(starterChecklist(c.settings.current().profile.language)) }
        resetting = false
    }
}

@Composable
private fun PillarEditor(p: Pillar, questions: List<ChecklistQuestion>, tracks: List<Track>, c: AppContainer) {
    val scope = rememberCoroutineScope()
    var rename by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    SectionCard(title = p.name) {
        LabeledRow(stringResource(R.string.no_targets)) { Switch(checked = p.noTargets, onCheckedChange = { scope.launch { c.reviews.updatePillar(p.copy(noTargets = it)) } }) }
        if (p.noTargets) {
            Muted(stringResource(R.string.pillar_tracks))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                tracks.forEach { t -> FilterChip(selected = t.id in p.trackIds, onClick = { scope.launch { c.reviews.updatePillar(p.copy(trackIds = if (t.id in p.trackIds) p.trackIds - t.id else p.trackIds + t.id)) } }, label = { Text(t.title) }) }
            }
        }
        questions.forEach { q -> QuestionEditor(q, c) }
        Row {
            TextButton(onClick = { adding = true }) { Text(stringResource(R.string.add_question)) }
            TextButton(onClick = { rename = true }) { Text(stringResource(R.string.rename)) }
            TextButton(onClick = { scope.launch { c.reviews.deletePillar(p.id) } }) { Text(stringResource(R.string.delete)) }
        }
    }
    if (rename) TextDialog(stringResource(R.string.rename), initial = p.name, singleLine = true, required = true, onDismiss = { rename = false }) { scope.launch { c.reviews.updatePillar(p.copy(name = it.trim())) }; rename = false }
    if (adding) TextDialog(stringResource(R.string.add_question), required = true, onDismiss = { adding = false }) {
        scope.launch { c.reviews.addQuestion(ChecklistQuestion(pillarId = p.id, order = questions.size, text = it.trim(), type = QuestionType.YES_NO)) }
        adding = false
    }
}

@Composable
private fun QuestionEditor(q: ChecklistQuestion, c: AppContainer) {
    val scope = rememberCoroutineScope()
    var text by remember(q.id) { mutableStateOf(q.text) }
    OutlinedTextField(text, { text = it; scope.launch { c.reviews.updateQuestion(q.copy(text = it)) } }, Modifier.fillMaxWidth(), singleLine = true)
    Row {
        ChoiceChip(q.type.name, QuestionType.entries, { it.name }, { t -> scope.launch { c.reviews.updateQuestion(q.copy(type = t, key = if (t == QuestionType.YES_NO) null else q.key)) } })
        if (q.type != QuestionType.YES_NO) {
            val keys = if (q.type == QuestionType.AUTO) QuestionKey.entries.filter { it != QuestionKey.TOP_3 && it != QuestionKey.ONE_THING_TO_FIX } else listOf(QuestionKey.ONE_THING_TO_FIX, QuestionKey.TOP_3)
            ChoiceChip(q.key?.name ?: "—", listOf<QuestionKey?>(null) + keys, { it?.name ?: "—" }, { k -> scope.launch { c.reviews.updateQuestion(q.copy(key = k)) } })
        }
        TextButton(onClick = { scope.launch { c.reviews.deleteQuestion(q.id) } }) { Text("✕") }
    }
}

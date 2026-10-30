package com.khoryz666.waypoint.ui.tracks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.khoryz666.waypoint.R
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.ui.Muted
import com.khoryz666.waypoint.ui.SectionCard
import com.khoryz666.waypoint.ui.domainLabel
import kotlinx.coroutines.launch

/** The DeepSeek-app handoff (spec 4): describe → copy prompt → discuss in DeepSeek → paste → preview → save. */
@Composable
fun NewTrackScreen(vm: NewTrackViewModel, onBack: () -> Unit, onCreated: (Long) -> Unit) {
    val stage by vm.stage.collectAsState()
    val busy by vm.busy.collectAsState()
    val created by vm.created.collectAsState()
    val tooMany by vm.tooMany.collectAsState()
    LaunchedEffect(created) { created?.let(onCreated) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_track)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (stage) {
                Stage.FORM -> DescribeStep(vm)
                Stage.HANDOFF -> HandoffStep(vm)
                Stage.PREVIEW -> PreviewStep(vm)
            }
        }
    }
    if (tooMany.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { vm.pauseAndActivate(null) },
            title = { Text(stringResource(R.string.too_many_active)) },
            text = {
                Column {
                    Text(stringResource(R.string.too_many_active_body))
                    tooMany.forEach { t -> TextButton(onClick = { vm.pauseAndActivate(t) }) { Text(stringResource(R.string.pause_x, t.title)) } }
                }
            },
            confirmButton = { TextButton(onClick = { vm.pauseAndActivate(null) }) { Text(stringResource(R.string.keep_as_draft)) } },
        )
    }
}

@Composable
private fun DescribeStep(vm: NewTrackViewModel) {
    val domain by vm.domain.collectAsState()
    val title by vm.title.collectAsState()
    val initial by vm.initialState.collectAsState()
    val notes by vm.notes.collectAsState()
    Text(stringResource(R.string.pick_domain), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Domain.entries.forEach { d -> FilterChip(selected = d == domain, onClick = { vm.domain.value = d }, label = { Text(domainLabel(d)) }) }
    }
    OutlinedTextField(title, { vm.title.value = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.title)) }, singleLine = true)
    OutlinedTextField(initial, { vm.initialState.value = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.current_state)) }, minLines = 3)
    OutlinedTextField(notes, { vm.notes.value = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.handoff_notes)) }, minLines = 2)
    Button(onClick = vm::toHandoff, enabled = title.isNotBlank()) { Text(stringResource(R.string.next)) }
}

@Composable
private fun HandoffStep(vm: NewTrackViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val error by vm.error.collectAsState()
    var pasted by remember { mutableStateOf("") }
    SectionCard(title = stringResource(R.string.handoff_step1)) {
        Muted(stringResource(R.string.handoff_explain))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { copy(context, vm.prompt()) } }) { Text(stringResource(R.string.copy_prompt)) }
            OutlinedButton(onClick = { openDeepSeek(context) }) { Text(stringResource(R.string.open_deepseek)) }
        }
    }
    SectionCard(title = stringResource(R.string.handoff_step2)) {
        OutlinedTextField(pasted, { pasted = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.paste_result)) }, minLines = 4, maxLines = 10)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pasted = paste(context) }) { Text(stringResource(R.string.paste_clipboard)) }
            Button(onClick = { vm.paste(pasted) }, enabled = pasted.isNotBlank()) { Text(stringResource(R.string.parse)) }
        }
        error?.let { e ->
            Text(stringResource(R.string.handoff_failed, e), color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.repair(pasted) }) { Text(stringResource(R.string.repair_with_api)) }
                TextButton(onClick = vm::manual) { Text(stringResource(R.string.fill_manually)) }
            }
        }
    }
    TextButton(onClick = vm::manual) { Text(stringResource(R.string.skip_handoff)) }
}

@Composable
private fun PreviewStep(vm: NewTrackViewModel) {
    val form by vm.form.collectAsState()
    val domain by vm.domain.collectAsState()
    val warnings by vm.warnings.collectAsState()
    val error by vm.error.collectAsState()
    warnings.forEach { Text(it, color = MaterialTheme.colorScheme.secondary) }
    fun set(change: (DraftForm) -> DraftForm) { vm.form.value = change(vm.form.value) }
    Muted(domainLabel(domain))
    OutlinedTextField(form.title, { v -> set { it.copy(title = v) } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.title)) }, singleLine = true)
    OutlinedTextField(form.goal, { v -> set { it.copy(goal = v) } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.goal)) }, minLines = 2)
    OutlinedTextField(form.initialState, { v -> set { it.copy(initialState = v) } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.current_state)) }, minLines = 2)
    OutlinedTextField(form.criteria, { v -> set { it.copy(criteria = v) } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.criteria_lines)) }, minLines = 2)
    OutlinedTextField(form.targetDate, { v -> set { it.copy(targetDate = v) } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.target_date)) }, singleLine = true)
    OutlinedTextField(form.units, { v -> set { it.copy(units = v) } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.units_lines)) }, minLines = 4, maxLines = 12)
    OutlinedTextField(form.planNotes, { v -> set { it.copy(planNotes = v) } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.plan_notes)) }, minLines = 2)
    if (domain == Domain.PROJECT) OutlinedTextField(form.repos, { v -> set { it.copy(repos = v) } }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.github_repos)) }, singleLine = true)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.save(activate = false) }) { Text(stringResource(R.string.save_draft)) }
        Button(onClick = { vm.save(activate = true) }) { Text(stringResource(R.string.activate)) }
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Waypoint", text))
}

private fun paste(context: Context): String =
    context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()

/** Launches the DeepSeek app by package, falling back to chat.deepseek.com (spec 4 step 2). */
private fun openDeepSeek(context: Context) {
    val app = context.packageManager.getLaunchIntentForPackage("com.deepseek.chat")
    context.startActivity((app ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.deepseek.com"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

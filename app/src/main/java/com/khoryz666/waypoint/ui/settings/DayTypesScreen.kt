package com.khoryz666.waypoint.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.khoryz666.waypoint.AppContainer
import com.khoryz666.waypoint.R
import com.khoryz666.waypoint.data.DayType
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.domain.DayTarget
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.ui.LabeledRow
import com.khoryz666.waypoint.ui.Muted
import com.khoryz666.waypoint.ui.SectionCard
import com.khoryz666.waypoint.ui.TextDialog
import com.khoryz666.waypoint.ui.domainLabel
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Day types (spec 5.6): name, default weekdays, minimum and plan, nudges. */
@Composable
fun DayTypesScreen(c: AppContainer, onBack: () -> Unit) {
    val types by c.db.dayTypes().observeAll().collectAsState(initial = emptyList())
    val tracks by c.tracks.active.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    fun save(t: DayType) = scope.launch {
        // A weekday has one default type: taking it here releases it everywhere else.
        types.filter { it.id != t.id && it.weekdays.any { d -> d in t.weekdays } }.forEach { o -> c.db.dayTypes().update(o.copy(weekdays = o.weekdays - t.weekdays.toSet())) }
        c.db.dayTypes().update(t)
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.day_types)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }, actions = { TextButton(onClick = { adding = true }) { Text(stringResource(R.string.add)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (types.isEmpty()) item { Muted(stringResource(R.string.no_day_types)) }
            items(types, key = { it.id }) { t -> DayTypeEditor(t, tracks, onSave = { save(it) }, onDelete = { scope.launch { c.db.dayTypes().delete(t.id) } }) }
        }
    }
    if (adding) TextDialog(stringResource(R.string.add), singleLine = true, required = true, onDismiss = { adding = false }) { name ->
        scope.launch { c.db.dayTypes().insert(DayType(name = name.trim(), order = types.size)) }
        adding = false
    }
}

@Composable
private fun DayTypeEditor(t: DayType, tracks: List<Track>, onSave: (DayType) -> Unit, onDelete: () -> Unit) {
    var rename by remember { mutableStateOf(false) }
    val locale = Locale.getDefault()
    SectionCard(title = t.name) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..7).forEach { d ->
                FilterChip(selected = d in t.weekdays, onClick = { onSave(t.copy(weekdays = if (d in t.weekdays) t.weekdays - d else t.weekdays + d)) }, label = { Text(DayOfWeek.of(d).getDisplayName(TextStyle.SHORT, locale)) })
            }
        }
        LabeledRow(stringResource(R.string.nudges)) { Switch(checked = t.nudges, onCheckedChange = { onSave(t.copy(nudges = it)) }) }
        Text(stringResource(R.string.minimum), style = MaterialTheme.typography.titleSmall)
        TargetEditor(t.minimum, tracks) { onSave(t.copy(minimum = it)) }
        Text(stringResource(R.string.plan), style = MaterialTheme.typography.titleSmall)
        TargetEditor(t.plan, tracks) { onSave(t.copy(plan = it)) }
        Row {
            TextButton(onClick = { rename = true }) { Text(stringResource(R.string.rename)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
        }
    }
    if (rename) TextDialog(stringResource(R.string.rename), initial = t.name, singleLine = true, required = true, onDismiss = { rename = false }) { onSave(t.copy(name = it.trim())); rename = false }
}

@Composable
private fun TargetEditor(target: DayTarget, tracks: List<Track>, onChange: (DayTarget) -> Unit) {
    NumberRow(stringResource(R.string.any_focus_minutes), target.anyFocusMinutes) { onChange(target.copy(anyFocusMinutes = it)) }
    NumberRow(stringResource(R.string.any_units), target.anyUnits) { onChange(target.copy(anyUnits = it)) }
    Muted(stringResource(R.string.any_domains))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Domain.entries.filter { it != Domain.MONEY }.forEach { d ->
            FilterChip(selected = d in target.anyDomains, onClick = { onChange(target.copy(anyDomains = if (d in target.anyDomains) target.anyDomains - d else target.anyDomains + d)) }, label = { Text(domainLabel(d)) })
        }
    }
    LabeledRow(stringResource(R.string.exercise_day)) { Switch(checked = target.exercise, onCheckedChange = { onChange(target.copy(exercise = it)) }) }
    tracks.filter { it.domain != Domain.MONEY && it.domain != Domain.EXERCISE }.forEach { t ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.title, Modifier.weight(1f), maxLines = 1)
            SmallNumber(stringResource(R.string.min_short), target.focusMinutes[t.id] ?: 0) { v -> onChange(target.copy(focusMinutes = (target.focusMinutes + (t.id to v)).filterValues { it > 0 })) }
            SmallNumber(stringResource(R.string.units_short), target.unitsDone[t.id] ?: 0) { v -> onChange(target.copy(unitsDone = (target.unitsDone + (t.id to v)).filterValues { it > 0 })) }
        }
    }
}

@Composable
private fun NumberRow(label: String, value: Int, onChange: (Int) -> Unit) {
    LabeledRow(label) { SmallNumber("", value, onChange) }
}

@Composable
private fun SmallNumber(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value == 0) "" else value.toString()) }
    OutlinedTextField(
        text, { v -> text = v.filter(Char::isDigit); onChange(text.toIntOrNull() ?: 0) }, Modifier.width(84.dp),
        label = if (label.isNotEmpty()) ({ Text(label) }) else null, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

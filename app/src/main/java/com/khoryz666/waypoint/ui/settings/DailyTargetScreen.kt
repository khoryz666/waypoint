package com.khoryz666.waypoint.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.domain.DayTarget
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.ui.LabeledRow
import com.khoryz666.waypoint.ui.Muted
import com.khoryz666.waypoint.ui.SectionCard
import com.khoryz666.waypoint.ui.domainLabel
import kotlinx.coroutines.launch

/**
 * The one daily target (spec 5.6): a minimum (still a win) and a plan, plus the idle nudge. It
 * applies every day; there are no named day types or weekday assignments.
 */
@Composable
fun DailyTargetScreen(c: AppContainer, onBack: () -> Unit) {
    val types by c.db.dayTypes().observeAll().collectAsState(initial = emptyList())
    val tracks by c.tracks.active.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { c.ensureDailyTarget() }
    val type = types.firstOrNull()
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.daily_target)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            type?.let { t ->
                item {
                    SectionCard {
                        LabeledRow(stringResource(R.string.nudges)) { Switch(checked = t.nudges, onCheckedChange = { on -> scope.launch { c.db.dayTypes().update(t.copy(nudges = on)) } }) }
                        Text(stringResource(R.string.minimum), style = MaterialTheme.typography.titleSmall)
                        TargetEditor(t.minimum, tracks) { scope.launch { c.db.dayTypes().update(t.copy(minimum = it)) } }
                        Text(stringResource(R.string.plan), style = MaterialTheme.typography.titleSmall)
                        TargetEditor(t.plan, tracks) { scope.launch { c.db.dayTypes().update(t.copy(plan = it)) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetEditor(target: DayTarget, tracks: List<Track>, onChange: (DayTarget) -> Unit) {
    NumberRow(stringResource(R.string.any_focus_minutes), target.anyFocusMinutes) { onChange(target.copy(anyFocusMinutes = it)) }
    NumberRow(stringResource(R.string.any_units), target.anyUnits) { onChange(target.copy(anyUnits = it)) }
    Muted(stringResource(R.string.any_domains))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Domain.entries.forEach { d ->
            FilterChip(selected = d in target.anyDomains, onClick = { onChange(target.copy(anyDomains = if (d in target.anyDomains) target.anyDomains - d else target.anyDomains + d)) }, label = { Text(domainLabel(d)) })
        }
    }
    LabeledRow(stringResource(R.string.exercise_day)) { Switch(checked = target.exercise, onCheckedChange = { onChange(target.copy(exercise = it)) }) }
    tracks.filter { it.domain != Domain.EXERCISE }.forEach { t ->
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

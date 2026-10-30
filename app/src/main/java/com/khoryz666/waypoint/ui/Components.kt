package com.khoryz666.waypoint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.khoryz666.waypoint.R
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.domain.TargetLine
import com.khoryz666.waypoint.domain.TargetUnit
import com.khoryz666.waypoint.domain.TrackStatus
import com.khoryz666.waypoint.domain.formatMinutes
import com.khoryz666.waypoint.ui.theme.SmallCaps

/** A titled card, the building block of every screen. */
@Composable
fun SectionCard(modifier: Modifier = Modifier, title: String? = null, color: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color ?: MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title.uppercase(), style = SmallCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
fun Banner(text: String, action: String? = null, onAction: () -> Unit = {}) {
    SectionCard(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/** One target line with its progress bar: green once met (spec 5.6). */
@Composable
fun TargetRow(line: TargetLine) {
    val done = when (line.unit) {
        TargetUnit.MINUTES -> "${formatMinutes(line.done.toLong())} / ${formatMinutes(line.target.toLong())}"
        TargetUnit.UNITS -> "${line.done} / ${line.target}"
        TargetUnit.CHECK -> if (line.met) "✓" else "—"
    }
    Column {
        Row {
            Text(line.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(done, style = MaterialTheme.typography.bodyMedium)
        }
        LinearProgressIndicator(
            progress = { line.fraction },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            color = if (line.met) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        )
    }
}

/** A chip that opens a menu of [options]. */
@Composable
fun <T> ChoiceChip(label: String, options: List<T>, optionLabel: @Composable (T) -> String, onChoose: (T) -> Unit, selected: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        FilterChip(selected = selected, onClick = { open = true }, label = { Text(label, maxLines = 1) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { open = false; onChoose(option) })
            }
        }
    }
}

/** A dialog with one text field, for reasons, final states and quick edits. */
@Composable
fun TextDialog(
    title: String,
    initial: String = "",
    label: String? = null,
    required: Boolean = false,
    number: Boolean = false,
    singleLine: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = label?.let { { Text(it) } }, singleLine = singleLine,
                keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = !required || text.isNotBlank()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String? = null, confirm: String = stringResource(R.string.ok), onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun LabeledRow(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        content()
    }
}

@Composable
fun domainLabel(domain: Domain): String = stringResource(
    when (domain) {
        Domain.EXERCISE -> R.string.domain_exercise
        Domain.MONEY -> R.string.domain_money
        Domain.PROJECT -> R.string.domain_project
        Domain.FUNDAMENTAL -> R.string.domain_fundamental
        Domain.MATH -> R.string.domain_math
        Domain.READING -> R.string.domain_reading
    },
)

@Composable
fun statusLabel(status: TrackStatus): String = stringResource(
    when (status) {
        TrackStatus.DRAFT -> R.string.status_draft
        TrackStatus.ACTIVE -> R.string.status_active
        TrackStatus.PAUSED -> R.string.status_paused
        TrackStatus.COMPLETED -> R.string.status_completed
        TrackStatus.ABORTED -> R.string.status_aborted
    },
)

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

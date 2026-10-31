package com.khoryz666.waypoint.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.khoryz666.waypoint.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A labelled row whose value opens a date picker; with [onClear], a set date gets an ✕. */
@Composable
fun DateField(label: String, date: LocalDate?, onClear: (() -> Unit)? = null, onChange: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val shown = date?.let { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMd"), locale).format(it) }
    PickerRow(label, shown, onClear?.takeIf { date != null }) { open = true }
    if (open) DateDialog(date ?: LocalDate.now(), onDismiss = { open = false }) { onChange(it); open = false }
}

/** A labelled row whose value opens a 24-hour time picker; [fallback] seeds it when unset. */
@Composable
fun TimeField(label: String, time: LocalTime?, fallback: LocalTime = LocalTime.of(22, 30), onClear: (() -> Unit)? = null, onChange: (LocalTime) -> Unit) {
    var open by remember { mutableStateOf(false) }
    PickerRow(label, time?.let { "%02d:%02d".format(it.hour, it.minute) }, onClear?.takeIf { time != null }) { open = true }
    if (open) TimeDialog(time ?: fallback, onDismiss = { open = false }) { onChange(it); open = false }
}

@Composable
private fun PickerRow(label: String, value: String?, onClear: (() -> Unit)?, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = onOpen) { Text(value ?: stringResource(R.string.set)) }
        if (onClear != null) IconButton(onClick = onClear) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove)) }
    }
}

@Composable
fun DateDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    // The picker works in UTC midnights, whatever the device zone.
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss() }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) { DatePicker(state = state) }
}

@Composable
fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = { TimePicker(state = state) },
    )
}

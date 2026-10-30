package com.khoryz666.waypoint.ui.body

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.khoryz666.waypoint.AppContainer
import com.khoryz666.waypoint.R
import com.khoryz666.waypoint.data.BalanceEvent
import com.khoryz666.waypoint.data.Settings
import com.khoryz666.waypoint.data.SleepRecord
import com.khoryz666.waypoint.domain.BalanceKind
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.domain.LogKind
import com.khoryz666.waypoint.domain.SleepSource
import com.khoryz666.waypoint.domain.SleepStatus
import com.khoryz666.waypoint.domain.balanceAt
import com.khoryz666.waypoint.domain.formatMinutes
import com.khoryz666.waypoint.domain.formatMoney
import com.khoryz666.waypoint.domain.formatMoneyDelta
import com.khoryz666.waypoint.domain.minorUnitFactor
import com.khoryz666.waypoint.domain.netChange
import com.khoryz666.waypoint.domain.sleepWeek
import com.khoryz666.waypoint.domain.weekStart
import com.khoryz666.waypoint.ui.ConfirmDialog
import com.khoryz666.waypoint.ui.Muted
import com.khoryz666.waypoint.ui.SectionCard
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Exercise tracks, sleep (7 / 30 / 365 days) and the balance: the foundations (spec 5.1). */
@Composable
fun BodyScreen(c: AppContainer) {
    val today = c.clock.today()
    val settings by c.settings.settings.collectAsState(initial = Settings())
    val sleep by c.db.sleep().observeBetween(today.minusDays(366), today.plusDays(1)).collectAsState(initial = emptyList())
    val balance by c.db.balance().observeAll().collectAsState(initial = emptyList())
    val tracks by c.tracks.active.collectAsState(initial = emptyList())
    val week = today.weekStart(settings.profile.weekStart)
    val range = c.clock.bounds(week, week.plusDays(7))
    val weekLogs by c.db.logs().observeConfirmedIn(range.start, range.end).collectAsState(initial = emptyList())
    val steps by c.db.nextSteps().observeOpen().collectAsState(initial = emptyList())
    var addSleep by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_body)) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tracks.filter { it.domain == Domain.EXERCISE }.forEach { t ->
                item(key = "ex-${t.id}") {
                    SectionCard(title = t.title) {
                        Text(stringResource(R.string.sessions_this_week, weekLogs.count { it.trackId == t.id && it.kind == LogKind.EXERCISE }))
                        steps.find { it.trackId == t.id }?.let { Muted(stringResource(R.string.next_x, it.text)) }
                        t.goalCriteria.firstOrNull { !it.met }?.let { Muted(stringResource(R.string.closest_goal, it.label)) }
                    }
                }
            }
            item { SleepCard(sleep, settings, today, c) { addSleep = true } }
            item { BalanceCard(balance, settings.profile.currency, c) }
        }
    }
    if (addSleep) ManualSleepDialog(today, onDismiss = { addSleep = false }) { record ->
        addSleep = false
        c.scope.launch { c.input.addSleep(record) }
    }
}

@Composable
private fun SleepCard(records: List<SleepRecord>, s: Settings, today: LocalDate, c: AppContainer, onAdd: () -> Unit) {
    var days by remember { mutableStateOf(7) }
    val targets = s.profile.sleep
    val week = today.weekStart(s.profile.weekStart)
    val thisWeek = sleepWeek(week, records.filter { it.date >= week && it.date < week.plusDays(7) }, targets, c.clock)
    val byDate = records.associateBy { it.date }
    val points = if (days == 365) {
        // A year as weekly averages; weeks without records are gaps.
        (51 downTo 0).map { back ->
            val start = week.minusWeeks(back.toLong())
            val nights = (0L until 7L).mapNotNull { byDate[start.plusDays(it)] }
            val avg = nights.takeIf { it.isNotEmpty() }?.map { it.totalMin }?.average()?.toFloat()
            ChartPoint(start.toString(), avg, "$start · " + (avg?.let { formatMinutes(it.toLong()) } ?: "—") + " · ${nights.size}/7")
        }
    } else {
        (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            val r = byDate[date]
            ChartPoint(date.toString(), r?.totalMin?.toFloat(), "$date · " + (r?.let { formatMinutes(it.totalMin.toLong()) + (it.napMin?.let { n -> " + nap ${n}m" } ?: "") } ?: "—"))
        }
    }
    val status = when (thisWeek.status) {
        SleepStatus.ON_TRACK -> R.string.sleep_on_track
        SleepStatus.WATCH -> R.string.sleep_watch
        SleepStatus.OFF_TRACK -> R.string.sleep_off_track
        SleepStatus.NOT_ENOUGH_DATA -> R.string.sleep_not_enough
    }
    SectionCard(title = stringResource(R.string.sleep)) {
        Text("✦ " + stringResource(status), style = MaterialTheme.typography.titleMedium)
        Muted(stringResource(R.string.sleep_week_line, thisWeek.recorded, thisWeek.averageMin?.let { formatMinutes(it.toLong()) } ?: "—", thisWeek.nightsInRange))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30, 365).forEach { d -> FilterChip(selected = days == d, onClick = { days = d }, label = { Text(stringResource(R.string.days_n, d)) }) }
        }
        BarChart(points, band = targets.minTotalMin.toFloat()..targets.maxTotalMin.toFloat())
        Muted(stringResource(R.string.sleep_band, formatMinutes(targets.minTotalMin.toLong()), formatMinutes(targets.maxTotalMin.toLong())))
        TextButton(onClick = onAdd) { Text(stringResource(R.string.add_sleep_manually)) }
    }
}

@Composable
private fun BalanceCard(events: List<BalanceEvent>, currency: String, c: AppContainer) {
    val now = c.clock.now()
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<BalanceEvent?>(null) }
    val factor = minorUnitFactor(currency).toFloat()
    val zone = c.clock.zone()
    val points = (89 downTo 0).map { back ->
        val at = c.clock.startOf(c.clock.today().minusDays(back.toLong()).plusDays(1)) - 1
        val value = if (events.any { it.occurredAt <= at }) balanceAt(events, at) else null
        ChartPoint(back.toString(), value?.let { it / factor }, Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString() + " · " + (value?.let { formatMoney(it, currency) } ?: "—"))
    }
    SectionCard(title = stringResource(R.string.balance)) {
        Text(formatMoney(balanceAt(events), currency), style = MaterialTheme.typography.headlineSmall)
        Muted(stringResource(R.string.balance_nets, formatMoneyDelta(netChange(events, now, 7), currency), formatMoneyDelta(netChange(events, now, 30), currency)))
        LineChart(points)
        Muted(stringResource(R.string.balance_hint))
        events.takeLast(8).reversed().forEach { e ->
            Row(Modifier.fillMaxWidth().clickable { deleting = e }.padding(vertical = 2.dp)) {
                Text(DateTimeFormatter.ofPattern("MM-dd").withZone(zone).format(Instant.ofEpochMilli(e.occurredAt)), Modifier.padding(end = 8.dp))
                Text(e.note.orEmpty(), Modifier.weight(1f), maxLines = 1)
                Text(if (e.kind == BalanceKind.SET) "= " + formatMoney(e.amount, currency) else formatMoneyDelta(e.amount, currency))
            }
        }
    }
    deleting?.let { e ->
        ConfirmDialog(stringResource(R.string.delete_entry), formatMoneyDelta(e.amount, currency), stringResource(R.string.delete), onDismiss = { deleting = null }) {
            scope.launch { c.db.balance().softDelete(e.id, c.clock.now()) }
            deleting = null
        }
    }
}

/** Bed and wake time for a night without a wearable (spec 6.2). */
@Composable
private fun ManualSleepDialog(today: LocalDate, onDismiss: () -> Unit, onSave: (SleepRecord) -> Unit) {
    var date by remember { mutableStateOf(today.toString()) }
    var bed by remember { mutableStateOf("23:30") }
    var wake by remember { mutableStateOf("07:00") }
    val zone = ZoneId.systemDefault()
    val record = runCatching {
        val d = LocalDate.parse(date)
        val b = LocalTime.parse(bed)
        val bedAt = (if (b.hour >= 12) d.minusDays(1) else d).atTime(b).atZone(zone).toInstant().toEpochMilli()
        val wakeAt = d.atTime(LocalTime.parse(wake)).atZone(zone).toInstant().toEpochMilli()
        require(wakeAt > bedAt)
        SleepRecord(date = d, bedAt = bedAt, wakeAt = wakeAt, totalMin = ((wakeAt - bedAt) / 60_000).toInt(), source = SleepSource.MANUAL)
    }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_sleep_manually)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(date, { date = it }, label = { Text(stringResource(R.string.wake_date)) }, singleLine = true)
                OutlinedTextField(bed, { bed = it }, label = { Text(stringResource(R.string.bed_time)) }, singleLine = true)
                OutlinedTextField(wake, { wake = it }, label = { Text(stringResource(R.string.wake_time)) }, singleLine = true)
                record?.let { Muted(formatMinutes(it.totalMin.toLong())) }
            }
        },
        confirmButton = { TextButton(onClick = { record?.let(onSave) }, enabled = record != null) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

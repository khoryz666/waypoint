package com.khoryz666.waypoint.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.khoryz666.waypoint.AppContainer
import com.khoryz666.waypoint.R
import com.khoryz666.waypoint.work.ReminderWorker
import kotlinx.coroutines.launch

/** The daily close sheet (spec 7.1): today's numbers, open cards, and tomorrow's one thing. */
@Composable
fun DailyCloseScreen(c: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf("") }
    var receipts by remember { mutableStateOf(emptyList<String>()) }
    var tomorrow by remember { mutableStateOf("") }
    val open by c.db.logs().observeUnconfirmed().collectAsState(initial = emptyList())
    LaunchedEffect(Unit) {
        summary = ReminderWorker.dailySummary(c)
        val day = c.clock.dayBounds(c.clock.today())
        receipts = c.db.logs().confirmedIn(day.start, day.end).filter { it.structured !is com.khoryz666.waypoint.domain.Structured.Recall }.map { runCatching { c.receipts.receipt(it) }.getOrDefault(it.rawText.orEmpty()) }.distinct()
        tomorrow = c.settings.current().tomorrowTop.takeIf { c.settings.current().tomorrowTopDate == c.clock.today().plusDays(1) }.orEmpty()
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.daily_close)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(title = stringResource(R.string.nav_today)) {
                Text(summary, style = MaterialTheme.typography.titleMedium)
                receipts.forEach { Muted(it) }
            }
            if (open.isNotEmpty()) SectionCard { Text(stringResource(R.string.open_cards, open.size)) }
            SectionCard(title = stringResource(R.string.tomorrow_one_thing)) {
                OutlinedTextField(tomorrow, { tomorrow = it }, Modifier.fillMaxWidth(), minLines = 2)
                Button(onClick = {
                    scope.launch {
                        c.settings.update { it.copy(tomorrowTop = tomorrow.ifBlank { null }, tomorrowTopDate = c.clock.today().plusDays(1)) }
                        onBack()
                    }
                }) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

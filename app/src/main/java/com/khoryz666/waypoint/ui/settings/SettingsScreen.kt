package com.khoryz666.waypoint.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.khoryz666.waypoint.AppContainer
import com.khoryz666.waypoint.R
import com.khoryz666.waypoint.backup.MIN_PASSPHRASE
import com.khoryz666.waypoint.domain.Lang
import com.khoryz666.waypoint.domain.ModelPrice
import com.khoryz666.waypoint.ui.Banner
import com.khoryz666.waypoint.ui.ChoiceChip
import com.khoryz666.waypoint.ui.ConfirmDialog
import com.khoryz666.waypoint.ui.LabeledRow
import com.khoryz666.waypoint.ui.BrandFooter
import com.khoryz666.waypoint.ui.Muted
import com.khoryz666.waypoint.ui.SectionCard
import com.khoryz666.waypoint.ui.TextDialog
import com.khoryz666.waypoint.ui.TimeDialog
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Which single-field edit is open; [initial] pre-fills it. */
private data class Edit(val title: String, val initial: String, val number: Boolean = false, val apply: (String) -> Unit)

@Composable
fun SettingsScreen(vm: SettingsViewModel, c: AppContainer, onBack: () -> Unit, onDailyTarget: () -> Unit) {
    val s by vm.settings.collectAsState()
    val usage by vm.usage.collectAsState()
    val budget by vm.budget.collectAsState()
    val models by vm.models.collectAsState()
    val message by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()
    val consent by vm.consent.collectAsState()
    val activity = LocalContext.current as? Activity
    var edit by remember { mutableStateOf<Edit?>(null) }
    var passphrase by remember { mutableStateOf(false) }
    var warn by remember { mutableStateOf(false) }
    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result -> vm.authorized(c.googleAuth.tokenFrom(result.data)) }
    LaunchedEffect(consent) { consent?.let { authLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build()) } }
    val locale = Locale.forLanguageTag(s.profile.language.tag)

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            message?.let { item { Banner(it, stringResource(R.string.ok)) { vm.message.value = null } } }
            item {
                SectionCard(title = stringResource(R.string.profile)) {
                    LabeledRow(stringResource(R.string.language)) {
                        ChoiceChip(if (s.profile.language == Lang.ZH) "简体中文" else "English", Lang.entries, { if (it == Lang.ZH) "简体中文" else "English" }, { vm.setLanguage(it, activity) })
                    }
                    Row(stringResource(R.string.display_name), s.profile.displayName ?: "—") { edit = Edit(it, s.profile.displayName.orEmpty()) { v -> vm.update { st -> st.copy(profile = st.profile.copy(displayName = v.ifBlank { null })) } } }
                    Row(stringResource(R.string.timezone), s.profile.timezoneOverride?.id ?: stringResource(R.string.device_zone, ZoneId.systemDefault().id)) {
                        edit = Edit(it, s.profile.timezoneOverride?.id.orEmpty()) { v -> vm.update { st -> st.copy(profile = st.profile.copy(timezoneOverride = v.trim().takeIf { z -> z.isNotEmpty() }?.let { z -> runCatching { ZoneId.of(z) }.getOrNull() })) } }
                    }
                    LabeledRow(stringResource(R.string.week_start)) {
                        ChoiceChip(s.profile.weekStart.getDisplayName(TextStyle.SHORT, locale), listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY), { it.getDisplayName(TextStyle.SHORT, locale) }, { d -> vm.update { st -> st.copy(profile = st.profile.copy(weekStart = d)) } })
                    }
                    Row(stringResource(R.string.currency), s.profile.currency) { edit = Edit(it, s.profile.currency) { v -> runCatching { java.util.Currency.getInstance(v.trim().uppercase()) }.getOrNull()?.let { cur -> vm.update { st -> st.copy(profile = st.profile.copy(currency = cur.currencyCode)) } } } }
                }
            }
            item {
                SectionCard(title = "DeepSeek") {
                    Row(stringResource(R.string.api_key), s.apiKeyHint?.let { "••••$it" } ?: "—") { edit = Edit(it, "") { v -> vm.setApiKey(v) } }
                    LabeledRow(stringResource(R.string.fast_model)) { ChoiceChip(s.fastModel, models.ifEmpty { listOf(s.fastModel) }, { it }, { m -> vm.update { st -> st.copy(fastModel = m) } }) }
                    LabeledRow(stringResource(R.string.review_model)) { ChoiceChip(s.reviewModel, models.ifEmpty { listOf(s.reviewModel) }, { it }, { m -> vm.update { st -> st.copy(reviewModel = m) } }) }
                    TextButton(onClick = vm::refreshModels, enabled = s.hasApiKey) { Text(stringResource(R.string.refresh_models)) }
                    Row(stringResource(R.string.monthly_budget), "¥%.0f".format(s.monthlyBudgetCny)) { edit = Edit(it, s.monthlyBudgetCny.toString(), number = true) { v -> v.toDoubleOrNull()?.let { b -> vm.update { st -> st.copy(monthlyBudgetCny = b) } } } }
                    budget?.let { b -> Text(stringResource(R.string.spend_line, "¥%.2f".format(usage.sumOf { it.costCny }), "¥%.2f".format(b.projectedCny))) }
                    usage.groupBy { it.promptName }.mapValues { (_, l) -> l.sumOf { it.costCny } }.entries.sortedByDescending { it.value }.forEach { (p, v) -> Muted("$p · ¥%.3f".format(v)) }
                    Text(stringResource(R.string.prices), style = MaterialTheme.typography.titleSmall)
                    s.prices.forEach { (model, p) ->
                        Row(model, "%.2f / %.2f / %.2f".format(p.cacheHit, p.cacheMiss, p.output)) {
                            edit = Edit(it, "${p.cacheHit} ${p.cacheMiss} ${p.output}") { v ->
                                v.split(' ', ',', '/').mapNotNull { n -> n.trim().toDoubleOrNull() }.takeIf { n -> n.size == 3 }?.let { (a, b, o) -> vm.update { st -> st.copy(prices = st.prices + (model to ModelPrice(a, b, o))) } }
                            }
                        }
                    }
                    Muted(stringResource(R.string.prices_hint))
                }
            }
            item {
                SectionCard(title = "GitHub") {
                    Row(stringResource(R.string.github_token), s.githubTokenHint?.let { "••••$it · ${s.githubLogin.orEmpty()}" } ?: "—") { edit = Edit(it, "") { v -> vm.setGithubToken(v) } }
                    Muted(stringResource(R.string.github_hint))
                }
            }
            item {
                SectionCard(title = stringResource(R.string.targets_and_reminders)) {
                    TextButton(onClick = onDailyTarget) { Text(stringResource(R.string.daily_target)) }
                    TimeRow(stringResource(R.string.daily_close_time), s.dailyCloseTime) { t -> vm.update { it.copy(dailyCloseTime = t) }; vm.rescheduleReminders() }
                    LabeledRow(stringResource(R.string.review_day)) {
                        ChoiceChip(s.weeklyReviewDay.getDisplayName(TextStyle.SHORT, locale), DayOfWeek.entries, { it.getDisplayName(TextStyle.SHORT, locale) }, { d -> vm.update { it.copy(weeklyReviewDay = d) }; vm.rescheduleReminders() })
                    }
                    TimeRow(stringResource(R.string.review_time), s.weeklyReviewTime) { t -> vm.update { it.copy(weeklyReviewTime = t) }; vm.rescheduleReminders() }
                    TimeRow(stringResource(R.string.waking_start), s.waking.start) { t -> vm.update { it.copy(waking = it.waking.copy(start = t)) } }
                    TimeRow(stringResource(R.string.waking_end), s.waking.end) { t -> vm.update { it.copy(waking = it.waking.copy(end = t)) } }
                    LabeledRow(stringResource(R.string.reading_recall)) { Switch(checked = s.readingRecall, onCheckedChange = { on -> vm.update { it.copy(readingRecall = on) } }) }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.sleep)) {
                    Row(stringResource(R.string.sleep_app), s.profile.sleepAppHint ?: "—") { edit = Edit(it, s.profile.sleepAppHint.orEmpty()) { v -> vm.update { st -> st.copy(profile = st.profile.copy(sleepAppHint = v.ifBlank { null })) } } }
                }
            }
            item { BackupSection(s.backup, c, onConnect = vm::backupNow, onPassphrase = { if (s.backup.passphraseWarningShown) passphrase = true else warn = true }, onBackupNow = vm::backupNow) }
            item { BrandFooter() }
        }
    }
    edit?.let { e -> TextDialog(e.title, initial = e.initial, number = e.number, singleLine = true, onDismiss = { edit = null }) { e.apply(it); edit = null } }
    if (warn) ConfirmDialog(stringResource(R.string.passphrase_warning_title), stringResource(R.string.passphrase_warning), onDismiss = { warn = false }) { warn = false; passphrase = true }
    if (passphrase) {
        TextDialog(stringResource(R.string.backup_passphrase), label = stringResource(R.string.passphrase_min, MIN_PASSPHRASE), singleLine = true, onDismiss = { passphrase = false }) { v ->
            if (v.length >= MIN_PASSPHRASE) { vm.setPassphrase(v, activity); passphrase = false } else vm.message.value = activity?.getString(R.string.passphrase_min, MIN_PASSPHRASE)
        }
    }
}

@Composable
private fun BackupSection(b: com.khoryz666.waypoint.data.BackupState, c: AppContainer, onConnect: () -> Unit, onPassphrase: () -> Unit, onBackupNow: () -> Unit) {
    SectionCard(title = stringResource(R.string.backup)) {
        val last = b.lastSuccessAt?.let { ago(c.clock.now() - it) }
        Text(if (last != null) stringResource(R.string.last_backup, last) else stringResource(R.string.never_backed_up))
        b.lastError?.let { Muted(it) }
        if (b.needsAuthorization) Text(stringResource(R.string.backup_reauthorize), color = MaterialTheme.colorScheme.error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!b.hasKey) Button(onClick = onPassphrase) { Text(stringResource(R.string.set_up_backup)) }
            else {
                Button(onClick = onBackupNow) { Text(stringResource(R.string.back_up_now)) }
                if (b.needsAuthorization) OutlinedButton(onClick = onConnect) { Text(stringResource(R.string.reauthorize)) }
                TextButton(onClick = onPassphrase) { Text(stringResource(R.string.new_backup_chain)) }
            }
        }
        Muted(stringResource(R.string.backup_hint))
    }
}

@Composable
private fun ago(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 60 -> stringResource(R.string.minutes_ago, minutes.toInt())
        minutes < 48 * 60 -> stringResource(R.string.hours_ago, (minutes / 60).toInt())
        else -> stringResource(R.string.days_ago, (minutes / 1440).toInt())
    }
}

@Composable
private fun Row(label: String, value: String, onEdit: (String) -> Unit) {
    LabeledRow(label, Modifier.clickable { onEdit(label) }.padding(vertical = 6.dp)) { Text(value, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
}

@Composable
private fun TimeRow(label: String, time: LocalTime, onChange: (LocalTime) -> Unit) {
    var open by remember { mutableStateOf(false) }
    LabeledRow(label, Modifier.clickable { open = true }.padding(vertical = 6.dp)) { Text(time.toString(), color = MaterialTheme.colorScheme.primary) }
    if (open) TimeDialog(time, onDismiss = { open = false }) { onChange(it); open = false }
}

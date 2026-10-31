package com.khoryz666.waypoint.ui.onboarding

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.khoryz666.waypoint.AppContainer
import com.khoryz666.waypoint.R
import com.khoryz666.waypoint.backup.BackupFile
import com.khoryz666.waypoint.data.Settings
import com.khoryz666.waypoint.domain.Lang
import com.khoryz666.waypoint.domain.parseHm
import com.khoryz666.waypoint.ui.BrandHeader
import com.khoryz666.waypoint.ui.Muted
import com.khoryz666.waypoint.ui.TimeField
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** First run (spec 5.0). The app knows nothing about the user until this is done. */
@Composable
fun OnboardingScreen(vm: OnboardingViewModel, c: AppContainer) {
    val step by vm.step.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val consent by vm.consent.collectAsState()
    val s by vm.settings.collectAsState(initial = Settings())
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r -> vm.authorized(c.googleAuth.tokenFrom(r.data)) }
    LaunchedEffect(consent) { consent?.let { launcher.launch(IntentSenderRequest.Builder(it.intentSender).build()) } }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BrandHeader()
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when (step) {
            Step.START -> {
                Text(stringResource(R.string.onboarding_welcome))
                Button(onClick = { vm.go(Step.LANGUAGE) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.start_fresh)) }
                OutlinedButton(onClick = vm::startRestore, Modifier.fillMaxWidth()) { Text(stringResource(R.string.restore_from_drive)) }
            }
            Step.LANGUAGE -> LanguageStep(vm)
            Step.REGION -> RegionStep(vm, s)
            Step.API -> ApiStep(vm, s)
            Step.SLEEP -> SleepStep(vm)
            Step.OPTIONAL -> OptionalStep(vm, s)
            Step.RESTORE -> RestoreStep(vm)
            Step.RESTORE_KEY -> Unit
        }
    }
}

@Composable
private fun StepTitle(n: Int, title: String) {
    Muted(stringResource(R.string.step_n, n, 5))
    Text(title, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun Nav(onNext: () -> Unit, onSkip: (() -> Unit)?, nextEnabled: Boolean = true, nextLabel: String = stringResource(R.string.next)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onNext, enabled = nextEnabled) { Text(nextLabel) }
        onSkip?.let { TextButton(onClick = it) { Text(stringResource(R.string.skip)) } }
    }
}

@Composable
private fun LanguageStep(vm: OnboardingViewModel) {
    val activity = LocalContext.current as? Activity
    val device = Lang.of(Locale.getDefault().toLanguageTag())
    var lang by remember { mutableStateOf(device) }
    var name by remember { mutableStateOf("") }
    StepTitle(1, "Language · 语言")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = lang == Lang.EN, onClick = { lang = Lang.EN }, label = { Text("English") })
        FilterChip(selected = lang == Lang.ZH, onClick = { lang = Lang.ZH }, label = { Text("简体中文") })
    }
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.display_name)) }, singleLine = true)
    Button(onClick = { vm.setLanguage(lang, name, activity) }) { Text(stringResource(R.string.next)) }
}

@Composable
private fun RegionStep(vm: OnboardingViewModel, s: Settings) {
    var week by remember { mutableStateOf(DayOfWeek.MONDAY) }
    var currency by remember(s.profile.currency) { mutableStateOf(s.profile.currency) }
    var balance by remember { mutableStateOf("") }
    val locale = Locale.forLanguageTag(s.profile.language.tag)
    StepTitle(2, stringResource(R.string.region))
    Muted(stringResource(R.string.week_start))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY).forEach { d -> FilterChip(selected = week == d, onClick = { week = d }, label = { Text(d.getDisplayName(TextStyle.SHORT, locale)) }) }
    }
    OutlinedTextField(currency, { currency = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.currency)) }, singleLine = true)
    OutlinedTextField(balance, { balance = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.starting_balance)) }, singleLine = true)
    Nav(onNext = { vm.setRegion(week, currency, balance) }, onSkip = { vm.go(Step.API) })
}

@Composable
private fun ApiStep(vm: OnboardingViewModel, s: Settings) {
    var key by remember { mutableStateOf("") }
    var budget by remember { mutableStateOf("50") }
    StepTitle(3, stringResource(R.string.deepseek_key))
    Muted(stringResource(R.string.deepseek_key_hint))
    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.api_key)) }, singleLine = true)
    OutlinedTextField(budget, { budget = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.monthly_budget) + " (CNY)") }, singleLine = true)
    if (s.hasApiKey) Muted("✓ ${s.fastModel} · ${s.reviewModel}")
    Nav(onNext = { vm.setApiKey(key, budget) }, onSkip = { vm.setApiKey("", budget) })
}

@Composable
private fun SleepStep(vm: OnboardingViewModel) {
    var min by remember { mutableStateOf("7:00") }
    var max by remember { mutableStateOf("8:30") }
    var bed by remember { mutableStateOf<LocalTime?>(null) }
    var app by remember { mutableStateOf("") }
    StepTitle(4, stringResource(R.string.sleep))
    OutlinedTextField(min, { min = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.at_least_hm)) }, singleLine = true)
    OutlinedTextField(max, { max = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.at_most_hm)) }, singleLine = true)
    TimeField(stringResource(R.string.target_bedtime), bed, onClear = { bed = null }) { bed = it }
    OutlinedTextField(app, { app = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sleep_app)) }, singleLine = true)
    Nav(onNext = { vm.setSleep(parseHm(min), parseHm(max), bed, app) }, onSkip = { vm.go(Step.OPTIONAL) })
}

@Composable
private fun OptionalStep(vm: OnboardingViewModel, s: Settings) {
    val activity = LocalContext.current as? Activity
    var token by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    StepTitle(5, stringResource(R.string.optional))
    if (!s.backup.hasKey) {
        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.backup_passphrase)) }, singleLine = true)
        Muted(stringResource(R.string.passphrase_warning))
    }
    OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.github_token_project)) }, singleLine = true)
    Nav(onNext = { vm.setOptional(token, pass, activity) }, onSkip = vm::finish, nextLabel = stringResource(R.string.finish_setup))
}

@Composable
private fun RestoreStep(vm: OnboardingViewModel) {
    val backups by vm.backups.collectAsState()
    val activity = LocalContext.current as? Activity
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf<BackupFile?>(null) }
    var pass by remember { mutableStateOf("") }
    Text(stringResource(R.string.restore_from_drive), style = MaterialTheme.typography.titleLarge)
    if (backups.isEmpty()) Muted(stringResource(R.string.no_backups))
    backups.forEach { b ->
        Row(Modifier.fillMaxWidth().clickable { chosen = b }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = chosen == b, onClick = { chosen = b }, label = { Text(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(b.createdAt))) })
            Muted("  ${b.size / 1024} KB")
        }
    }
    chosen?.let { b ->
        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.backup_passphrase)) }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { activity?.let { vm.savedPassphrase(it) }?.let { pass = it } } }) { Text(stringResource(R.string.use_saved_passphrase)) }
            Button(onClick = { vm.restore(b, pass) }, enabled = pass.isNotEmpty()) { Text(stringResource(R.string.restore)) }
        }
    }
    TextButton(onClick = { vm.go(Step.START) }) { Text(stringResource(R.string.back)) }
}

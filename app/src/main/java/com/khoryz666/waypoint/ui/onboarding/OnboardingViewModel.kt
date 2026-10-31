package com.khoryz666.waypoint.ui.onboarding

import android.app.Activity
import android.app.PendingIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khoryz666.waypoint.AppContainer
import com.khoryz666.waypoint.backup.BackupFile
import com.khoryz666.waypoint.backup.NeedsAuthorization
import com.khoryz666.waypoint.backup.NewerBackup
import com.khoryz666.waypoint.backup.WrongPassphrase
import com.khoryz666.waypoint.data.BalanceEvent
import com.khoryz666.waypoint.domain.BalanceKind
import com.khoryz666.waypoint.domain.Lang
import com.khoryz666.waypoint.domain.parseMinor
import com.khoryz666.waypoint.timer.TimerService
import com.khoryz666.waypoint.ui.Locales
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Onboarding steps in spec 5.0 order. Everything but language can be skipped. */
enum class Step { START, LANGUAGE, REGION, API, SLEEP, OPTIONAL, RESTORE, RESTORE_KEY }

class OnboardingViewModel(private val c: AppContainer) : ViewModel() {
    val step = MutableStateFlow(Step.START)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val models = MutableStateFlow<List<String>>(emptyList())
    val backups = MutableStateFlow<List<BackupFile>>(emptyList())
    val consent = MutableStateFlow<PendingIntent?>(null)
    val settings = c.settings.settings
    private var restored = false
    private var token: String? = null

    fun go(next: Step) {
        error.value = null
        step.value = next
    }

    fun setLanguage(lang: Lang, name: String, activity: Activity?) = viewModelScope.launch {
        c.settings.update { it.copy(profile = it.profile.copy(language = lang, displayName = name.ifBlank { null })) }
        Locales.save(c.appContext, lang.tag)
        go(Step.REGION)
        activity?.recreate()
    }

    fun setRegion(zone: String, weekStart: java.time.DayOfWeek, currency: String, startingBalance: String) = viewModelScope.launch {
        val cur = runCatching { java.util.Currency.getInstance(currency.trim().uppercase()).currencyCode }.getOrNull()
        c.settings.update { s ->
            s.copy(profile = s.profile.copy(
                timezoneOverride = zone.trim().takeIf { it.isNotEmpty() }?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() },
                weekStart = weekStart,
                currency = cur ?: s.profile.currency,
            ))
        }
        val code = cur ?: c.settings.current().profile.currency
        startingBalance.takeIf { it.isNotBlank() }?.let { parseMinor(it.trim(), code) }?.let {
            c.db.balance().insert(BalanceEvent(occurredAt = c.clock.now(), kind = BalanceKind.SET, amount = it, note = null))
        }
        go(Step.API)
    }

    fun setApiKey(key: String, budget: String) = work {
        if (key.isNotBlank()) {
            val list = c.llm.models(key.trim())
            models.value = list
            c.settings.setApiKey(key)
            c.settings.update { s ->
                s.copy(
                    fastModel = list.firstOrNull { it.contains("flash") } ?: s.fastModel,
                    reviewModel = list.firstOrNull { it.contains("pro") } ?: s.reviewModel,
                    monthlyBudgetCny = budget.toDoubleOrNull() ?: s.monthlyBudgetCny,
                )
            }
        }
        go(if (restored) finishStep() else Step.SLEEP)
    }

    fun setSleep(min: Int?, max: Int?, bedtime: String, app: String) = viewModelScope.launch {
        c.settings.update { s ->
            s.copy(profile = s.profile.copy(
                sleep = s.profile.sleep.copy(
                    minTotalMin = min ?: s.profile.sleep.minTotalMin,
                    maxTotalMin = max ?: s.profile.sleep.maxTotalMin,
                    bedtime = runCatching { java.time.LocalTime.parse(bedtime.trim()) }.getOrNull(),
                ),
                sleepAppHint = app.ifBlank { null },
            ))
        }
        go(Step.OPTIONAL)
    }

    fun setOptional(githubToken: String, passphrase: String, activity: Activity?) = work {
        if (githubToken.isNotBlank()) c.settings.setGithubToken(githubToken, runCatching { c.github.login(githubToken.trim()) }.getOrNull())
        if (passphrase.isNotBlank()) {
            require(passphrase.length >= com.khoryz666.waypoint.backup.MIN_PASSPHRASE) { "passphrase too short" }
            c.backup.setPassphrase(passphrase)
            if (activity != null) c.googleAuth.savePassphrase(activity, passphrase)
            c.settings.update { it.copy(backup = it.backup.copy(passphraseWarningShown = true)) }
        }
        finish()
    }

    // Restore (spec 9.5): authorize Drive, list backups, decrypt with the saved or typed passphrase.

    fun startRestore() = work {
        try {
            token = c.googleAuth.token()
            backups.value = c.backup.list(token!!)
            go(Step.RESTORE)
        } catch (e: NeedsAuthorization) {
            consent.value = e.pendingIntent
        }
    }

    fun authorized(t: String?) = work {
        consent.value = null
        token = t ?: error("authorization cancelled")
        backups.value = c.backup.list(token!!)
        go(Step.RESTORE)
    }

    fun restore(file: BackupFile, passphrase: String) = work {
        try {
            c.backup.restore(token ?: c.googleAuth.token(), file, passphrase)
            restored = true
            go(Step.API)
        } catch (e: WrongPassphrase) {
            error.value = "wrong passphrase"
        } catch (e: NewerBackup) {
            error.value = e.message
        }
    }

    suspend fun savedPassphrase(activity: Activity): String? = c.googleAuth.readPassphrase(activity)

    private fun finishStep() = Step.OPTIONAL

    /** Ends on Today; re-arms workers and the timer (restore included). */
    fun finish() = viewModelScope.launch {
        c.ensureDailyTarget()
        c.settings.update { it.copy(onboarded = true) }
        c.scheduleWork()
        TimerService.start(c.appContext)
    }

    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        error.value = null
        try {
            block()
        } catch (e: Exception) {
            error.value = e.message ?: e.javaClass.simpleName
        } finally {
            busy.value = false
        }
    }
}

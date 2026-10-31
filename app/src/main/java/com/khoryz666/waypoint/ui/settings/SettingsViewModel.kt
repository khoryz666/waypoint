package com.khoryz666.waypoint.ui.settings

import android.app.Activity
import android.app.PendingIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khoryz666.waypoint.AppContainer
import com.khoryz666.waypoint.backup.NeedsAuthorization
import com.khoryz666.waypoint.data.Settings
import com.khoryz666.waypoint.data.UsageRecord
import com.khoryz666.waypoint.domain.BudgetState
import com.khoryz666.waypoint.domain.Lang
import com.khoryz666.waypoint.ui.Locales
import com.khoryz666.waypoint.work.BackupWorker
import com.khoryz666.waypoint.work.ParseWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())
    val usage = c.db.usage().observeSince(c.clock.startOf(YearMonth.from(c.clock.today()).atDay(1))).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<UsageRecord>())
    val budget = MutableStateFlow<BudgetState?>(null)
    val models = MutableStateFlow<List<String>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    /** Google's consent screen, when Drive authorization needs the user. */
    val consent = MutableStateFlow<PendingIntent?>(null)

    init {
        viewModelScope.launch { budget.value = runCatching { c.llm.budget() }.getOrNull() }
    }

    fun update(change: (Settings) -> Settings) = viewModelScope.launch { c.settings.update(change) }

    fun setLanguage(lang: Lang, activity: Activity?) = viewModelScope.launch {
        val from = c.settings.current().profile.language
        c.settings.update { it.copy(profile = it.profile.copy(language = lang)) }
        c.reviews.translateDefaultQuestions(from, lang)
        Locales.save(c.appContext, lang.tag)
        activity?.recreate()
    }

    /** Saves the key, then lists models to pick Flash and Pro from (spec 8.1). */
    fun setApiKey(key: String) = work {
        val models = c.llm.models(key.trim())
        c.settings.setApiKey(key)
        this.models.value = models
        c.settings.update { s ->
            s.copy(
                fastModel = models.firstOrNull { it.contains("flash") } ?: s.fastModel,
                reviewModel = models.firstOrNull { it.contains("pro") } ?: s.reviewModel,
            )
        }
        ParseWorker.scheduler(c.appContext).schedule()
    }

    fun refreshModels() = work { models.value = c.llm.models(c.settings.apiKey() ?: error("no key")) }

    fun setGithubToken(token: String) = work {
        if (token.isBlank()) {
            c.settings.setGithubToken(null, null)
        } else {
            val login = c.github.login(token.trim())
            c.settings.setGithubToken(token, login)
            com.khoryz666.waypoint.work.CommitSyncWorker.now(c.appContext)
        }
    }

    fun setPassphrase(passphrase: String, activity: Activity?) = work {
        c.backup.setPassphrase(passphrase)
        if (activity != null) c.googleAuth.savePassphrase(activity, passphrase)
        c.settings.update { it.copy(backup = it.backup.copy(passphraseWarningShown = true)) }
        backupNow()
    }

    fun backupNow() = work {
        try {
            c.backup.backup(c.googleAuth.token())
            message.value = "✓"
        } catch (e: NeedsAuthorization) {
            consent.value = e.pendingIntent
        }
    }

    /** After the consent screen: the token is valid, so back up right away. */
    fun authorized(token: String?) = work {
        consent.value = null
        if (token != null) {
            c.settings.update { it.copy(backup = it.backup.copy(needsAuthorization = false)) }
            if (c.settings.current().backup.hasKey) c.backup.backup(token)
        }
    }

    fun rescheduleReminders() = viewModelScope.launch { c.scheduleWork() }

    fun scheduleBackup() = BackupWorker.now(c.appContext)

    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        try {
            block()
        } catch (e: Exception) {
            message.value = e.message ?: e.javaClass.simpleName
        } finally {
            busy.value = false
        }
    }
}

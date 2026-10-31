package com.khoryz666.waypoint.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.khoryz666.waypoint.domain.Lang
import com.khoryz666.waypoint.domain.ModelPrice
import com.khoryz666.waypoint.domain.SavingsGoal
import com.khoryz666.waypoint.domain.SleepTargets
import com.khoryz666.waypoint.domain.WakingHours
import com.khoryz666.waypoint.domain.WaypointJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Everything in DataStore: the profile, model and budget settings, reminder times, and small
 * pieces of state. Secrets are stored only as [SecretCipher] ciphertext.
 */
class SettingsStore(private val store: DataStore<Preferences>, private val secrets: SecretCipher) {

    val settings: Flow<Settings> = store.data.map(::read)

    suspend fun current(): Settings = settings.first()

    suspend fun apiKey(): String? = store.data.first()[K.apiKey]?.let(secrets::decryptString)
    suspend fun githubToken(): String? = store.data.first()[K.githubToken]?.let(secrets::decryptString)
    suspend fun backupKey(): ByteArray? = store.data.first()[K.backupKey]?.let(secrets::decrypt)

    suspend fun setApiKey(key: String?) = store.edit {
        if (key.isNullOrBlank()) it.remove(K.apiKey) else it[K.apiKey] = secrets.encryptString(key.trim())
        it[K.apiKeyHint] = key?.trim()?.takeLast(4) ?: ""
        it.remove(K.apiProblem)
    }

    suspend fun setGithubToken(token: String?, login: String?) = store.edit {
        if (token.isNullOrBlank()) it.remove(K.githubToken) else it[K.githubToken] = secrets.encryptString(token.trim())
        it[K.githubTokenHint] = token?.trim()?.takeLast(4) ?: ""
        it.putOrRemove(K.githubLogin, login)
    }

    suspend fun setBackupKey(key: ByteArray?) = store.edit {
        if (key == null) it.remove(K.backupKey) else it[K.backupKey] = secrets.encrypt(key)
    }

    suspend fun update(change: (Settings) -> Settings) {
        store.edit { prefs -> write(prefs, change(read(prefs))) }
    }

    private fun read(p: Preferences) = Settings(
        onboarded = p[K.onboarded] ?: false,
        profile = UserProfile(
            displayName = p[K.displayName],
            language = Lang.of(p[K.language] ?: java.util.Locale.getDefault().toLanguageTag()),
            timezoneOverride = p[K.timezone]?.let { runCatching { ZoneId.of(it) }.getOrNull() },
            weekStart = p[K.weekStart]?.let(DayOfWeek::of) ?: DayOfWeek.MONDAY,
            currency = p[K.currency] ?: defaultCurrency(),
            sleep = SleepTargets(
                minTotalMin = p[K.sleepMin] ?: 420,
                maxTotalMin = p[K.sleepMax] ?: 510,
                bedtime = p[K.bedtime]?.let(LocalTime::parse),
                bedtimeWindowMin = 45,
            ),
            sleepAppHint = p[K.sleepAppHint],
            savings = p[K.savingsGoal]?.let { SavingsGoal(it, p[K.savingsBy]?.let(LocalDate::parse)) },
        ),
        hasApiKey = p[K.apiKey] != null,
        apiKeyHint = p[K.apiKeyHint]?.ifEmpty { null },
        fastModel = p[K.fastModel] ?: "deepseek-flash",
        reviewModel = p[K.reviewModel] ?: "deepseek-v4-pro",
        prices = p[K.prices]?.let { runCatching { WaypointJson.decodeFromString(priceMap, it) }.getOrNull() } ?: com.khoryz666.waypoint.domain.DEFAULT_PRICES,
        monthlyBudgetCny = p[K.budget] ?: 50.0,
        // A key problem only means something while a key is set.
        apiProblem = p[K.apiProblem]?.takeIf { p[K.apiKey] != null }?.let { runCatching { ApiProblem.valueOf(it) }.getOrNull() },
        hasGithubToken = p[K.githubToken] != null,
        githubTokenHint = p[K.githubTokenHint]?.ifEmpty { null },
        githubLogin = p[K.githubLogin],
        dailyCloseTime = p[K.dailyClose]?.let(LocalTime::parse) ?: LocalTime.of(22, 30),
        weeklyReviewDay = p[K.reviewDay]?.let(DayOfWeek::of) ?: DayOfWeek.SUNDAY,
        weeklyReviewTime = p[K.reviewTime]?.let(LocalTime::parse) ?: LocalTime.of(20, 0),
        waking = WakingHours(
            p[K.wakingStart]?.let(LocalTime::parse) ?: LocalTime.of(8, 0),
            p[K.wakingEnd]?.let(LocalTime::parse) ?: LocalTime.of(23, 30),
        ),
        readingRecall = p[K.readingRecall] ?: false,
        tomorrowTop = p[K.tomorrowTop],
        tomorrowTopDate = p[K.tomorrowTopDate]?.let(LocalDate::parse),
        lastNudgeAt = p[K.lastNudgeAt],
        backup = BackupState(
            account = p[K.backupAccount],
            hasKey = p[K.backupKey] != null,
            lastSuccessAt = p[K.backupSuccess],
            lastAttemptAt = p[K.backupAttempt],
            lastError = p[K.backupError],
            needsAuthorization = p[K.backupNeedsAuth] ?: false,
            passphraseWarningShown = p[K.backupWarned] ?: false,
        ),
    )

    private fun write(p: MutablePreferences, s: Settings) {
        p[K.onboarded] = s.onboarded
        p.putOrRemove(K.displayName, s.profile.displayName)
        p[K.language] = s.profile.language.tag
        p.putOrRemove(K.timezone, s.profile.timezoneOverride?.id)
        p[K.weekStart] = s.profile.weekStart.value
        p[K.currency] = s.profile.currency
        p[K.sleepMin] = s.profile.sleep.minTotalMin
        p[K.sleepMax] = s.profile.sleep.maxTotalMin
        p.putOrRemove(K.bedtime, s.profile.sleep.bedtime?.toString())
        p.putOrRemove(K.sleepAppHint, s.profile.sleepAppHint)
        p.putOrRemove(K.savingsGoal, s.profile.savings?.amountMinor)
        p.putOrRemove(K.savingsBy, s.profile.savings?.by?.toString())
        p[K.fastModel] = s.fastModel
        p[K.reviewModel] = s.reviewModel
        p[K.prices] = WaypointJson.encodeToString(priceMap, s.prices)
        p[K.budget] = s.monthlyBudgetCny
        p.putOrRemove(K.apiProblem, s.apiProblem?.name)
        p.putOrRemove(K.githubLogin, s.githubLogin)
        p[K.dailyClose] = s.dailyCloseTime.toString()
        p[K.reviewDay] = s.weeklyReviewDay.value
        p[K.reviewTime] = s.weeklyReviewTime.toString()
        p[K.wakingStart] = s.waking.start.toString()
        p[K.wakingEnd] = s.waking.end.toString()
        p[K.readingRecall] = s.readingRecall
        p.putOrRemove(K.tomorrowTop, s.tomorrowTop)
        p.putOrRemove(K.tomorrowTopDate, s.tomorrowTopDate?.toString())
        p.putOrRemove(K.lastNudgeAt, s.lastNudgeAt)
        p.putOrRemove(K.backupAccount, s.backup.account)
        p.putOrRemove(K.backupSuccess, s.backup.lastSuccessAt)
        p.putOrRemove(K.backupAttempt, s.backup.lastAttemptAt)
        p.putOrRemove(K.backupError, s.backup.lastError)
        p[K.backupNeedsAuth] = s.backup.needsAuthorization
        p[K.backupWarned] = s.backup.passphraseWarningShown
    }

    private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    private fun defaultCurrency(): String =
        runCatching { java.util.Currency.getInstance(java.util.Locale.getDefault()).currencyCode }.getOrDefault("USD")

    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val displayName = stringPreferencesKey("displayName")
        val language = stringPreferencesKey("language")
        val timezone = stringPreferencesKey("timezone")
        val weekStart = intPreferencesKey("weekStart")
        val currency = stringPreferencesKey("currency")
        val sleepMin = intPreferencesKey("sleepMin")
        val sleepMax = intPreferencesKey("sleepMax")
        val bedtime = stringPreferencesKey("bedtime")
        val sleepAppHint = stringPreferencesKey("sleepAppHint")
        val savingsGoal = longPreferencesKey("savingsGoal")
        val savingsBy = stringPreferencesKey("savingsBy")
        val apiKey = stringPreferencesKey("apiKey")
        val apiKeyHint = stringPreferencesKey("apiKeyHint")
        val fastModel = stringPreferencesKey("fastModel")
        val reviewModel = stringPreferencesKey("reviewModel")
        val prices = stringPreferencesKey("prices")
        val budget = doublePreferencesKey("budget")
        val apiProblem = stringPreferencesKey("apiProblem")
        val githubToken = stringPreferencesKey("githubToken")
        val githubTokenHint = stringPreferencesKey("githubTokenHint")
        val githubLogin = stringPreferencesKey("githubLogin")
        val dailyClose = stringPreferencesKey("dailyClose")
        val reviewDay = intPreferencesKey("reviewDay")
        val reviewTime = stringPreferencesKey("reviewTime")
        val wakingStart = stringPreferencesKey("wakingStart")
        val wakingEnd = stringPreferencesKey("wakingEnd")
        val readingRecall = booleanPreferencesKey("readingRecall")
        val tomorrowTop = stringPreferencesKey("tomorrowTop")
        val tomorrowTopDate = stringPreferencesKey("tomorrowTopDate")
        val lastNudgeAt = longPreferencesKey("lastNudgeAt")
        val backupKey = stringPreferencesKey("backupKey")
        val backupAccount = stringPreferencesKey("backupAccount")
        val backupSuccess = longPreferencesKey("backupSuccess")
        val backupAttempt = longPreferencesKey("backupAttempt")
        val backupError = stringPreferencesKey("backupError")
        val backupNeedsAuth = booleanPreferencesKey("backupNeedsAuth")
        val backupWarned = booleanPreferencesKey("backupWarned")
    }

    private companion object {
        val priceMap = MapSerializer(String.serializer(), ModelPrice.serializer())
    }
}

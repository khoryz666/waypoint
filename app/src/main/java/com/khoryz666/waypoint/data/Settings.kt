package com.khoryz666.waypoint.data

import com.khoryz666.waypoint.domain.DEFAULT_PRICES
import com.khoryz666.waypoint.domain.Lang
import com.khoryz666.waypoint.domain.ModelPrice
import com.khoryz666.waypoint.domain.SleepTargets
import com.khoryz666.waypoint.domain.WakingHours
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Who the user is, from onboarding (spec 5.0). Every field is editable in settings. */
data class UserProfile(
    val displayName: String? = null,
    val language: Lang = Lang.EN,
    val timezoneOverride: ZoneId? = null,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val currency: String = "USD",
    val sleep: SleepTargets = SleepTargets(),
    val sleepAppHint: String? = null,
)

/** Why the API queue is paused (spec 8.1): a 401 or a 402 from DeepSeek. */
enum class ApiProblem { KEY_INVALID, NO_BALANCE }

data class Settings(
    val onboarded: Boolean = false,
    val profile: UserProfile = UserProfile(),
    val hasApiKey: Boolean = false,
    val apiKeyHint: String? = null,
    val fastModel: String = "deepseek-flash",
    val reviewModel: String = "deepseek-v4-pro",
    val prices: Map<String, ModelPrice> = DEFAULT_PRICES,
    val monthlyBudgetCny: Double = 50.0,
    val apiProblem: ApiProblem? = null,
    val hasGithubToken: Boolean = false,
    val githubTokenHint: String? = null,
    val githubLogin: String? = null,
    val dailyCloseTime: LocalTime = LocalTime.of(22, 30),
    val weeklyReviewDay: DayOfWeek = DayOfWeek.SUNDAY,
    val weeklyReviewTime: LocalTime = LocalTime.of(20, 0),
    val waking: WakingHours = WakingHours(),
    val readingRecall: Boolean = false,
    /** "明天最重要的一件事" from the daily close, shown on Today on [tomorrowTopDate]. */
    val tomorrowTop: String? = null,
    val tomorrowTopDate: LocalDate? = null,
    val lastNudgeAt: Long? = null,
    val backup: BackupState = BackupState(),
)

data class BackupState(
    val account: String? = null,
    val hasKey: Boolean = false,
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    val needsAuthorization: Boolean = false,
    val passphraseWarningShown: Boolean = false,
)

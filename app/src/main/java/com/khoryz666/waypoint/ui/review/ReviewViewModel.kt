package com.khoryz666.waypoint.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khoryz666.waypoint.AppContainer
import com.khoryz666.waypoint.data.Review
import com.khoryz666.waypoint.data.ReviewAnswer
import com.khoryz666.waypoint.data.ReviewRepository
import com.khoryz666.waypoint.domain.Lang
import com.khoryz666.waypoint.domain.ReviewPeriod
import com.khoryz666.waypoint.llm.Suggestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class ReviewViewModel(private val c: AppContainer) : ViewModel() {
    val reviews = c.reviews.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pillars = c.reviews.pillars.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val questions = c.reviews.questions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val open = MutableStateFlow<Review?>(null)
    val answers = MutableStateFlow<Map<Long, ReviewAnswer>>(emptyMap())
    val reflection = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val lastWeekOpen = MutableStateFlow(false)
    val lang = MutableStateFlow(Lang.EN)

    init {
        viewModelScope.launch {
            lang.value = c.settings.current().profile.language
            lastWeekOpen.value = c.reviews.lastWeekUnreviewed()
            openPeriod(ReviewPeriod.WEEK, c.reviews.currentWeekStart())
        }
    }

    fun openPeriod(period: ReviewPeriod, start: LocalDate) = viewModelScope.launch {
        val review = c.reviews.prepare(period, start)
        show(review)
    }

    fun openThisWeek() = viewModelScope.launch { openPeriod(ReviewPeriod.WEEK, c.reviews.currentWeekStart()) }
    fun openLastWeek() = viewModelScope.launch { openPeriod(ReviewPeriod.WEEK, c.reviews.currentWeekStart().minusDays(7)) }
    fun openLastMonth() = openPeriod(ReviewPeriod.MONTH, c.clock.today().minusMonths(1).withDayOfMonth(1))
    fun openThisYear() = openPeriod(ReviewPeriod.YEAR, c.clock.today().withDayOfYear(1).let { if (c.clock.today().dayOfYear < 15) it.minusYears(1) else it })

    fun show(review: Review) {
        open.value = review
        answers.value = ReviewRepository.decodeAnswers(review.answers)
        reflection.value = review.reflection
        suggestions.value = emptyList()
        error.value = null
    }

    fun answer(questionId: Long, change: (ReviewAnswer) -> ReviewAnswer) {
        answers.value = answers.value + (questionId to change(answers.value[questionId] ?: ReviewAnswer()))
        open.value?.let { r -> viewModelScope.launch { c.reviews.saveAnswers(r, answers.value, reflection.value) } }
    }

    fun submit() = viewModelScope.launch {
        val review = open.value ?: return@launch
        busy.value = true
        try {
            val (saved, ideas) = c.reviews.submit(review, answers.value, reflection.value)
            open.value = saved
            suggestions.value = ideas
            if (saved.narrative.isEmpty()) error.value = "narrative unavailable (budget or network); try again later"
            // The weekly review also refreshes every active track's long-term memory (spec 8.4).
            if (review.period == ReviewPeriod.WEEK) c.scope.launch { c.db.tracks().active().forEach { t -> runCatching { c.feedback.refreshSummary(t) } } }
        } finally {
            busy.value = false
        }
    }

    fun renarrate() = viewModelScope.launch {
        val review = open.value ?: return@launch
        busy.value = true
        try {
            open.value = c.reviews.renarrate(review)
            error.value = null
        } catch (e: Exception) {
            error.value = e.message
        } finally {
            busy.value = false
        }
    }

    fun accept(s: Suggestion) = viewModelScope.launch {
        c.reviews.acceptSuggestion(s)
        suggestions.value = suggestions.value - s
    }
}

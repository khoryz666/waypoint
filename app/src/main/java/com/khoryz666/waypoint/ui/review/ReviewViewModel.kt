package com.khoryz666.waypoint.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khoryz666.waypoint.AppContainer
import com.khoryz666.waypoint.data.Review
import com.khoryz666.waypoint.data.ReviewQuestion
import com.khoryz666.waypoint.data.ReviewRepository
import com.khoryz666.waypoint.domain.Lang
import com.khoryz666.waypoint.domain.ReviewPeriod
import com.khoryz666.waypoint.domain.reviewSummary
import com.khoryz666.waypoint.llm.Suggestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class ReviewViewModel(private val c: AppContainer) : ViewModel() {
    val reviews = c.reviews.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val questions = c.reviews.questions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeTracks = c.tracks.active.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val open = MutableStateFlow<Review?>(null)
    val summary = MutableStateFlow<List<String>>(emptyList())
    val answers = MutableStateFlow<Map<String, String>>(emptyMap())
    val fix = MutableStateFlow("")
    val top3 = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val writing = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val lastWeekOpen = MutableStateFlow(false)
    private var lang = Lang.EN

    init {
        viewModelScope.launch {
            lang = c.settings.current().profile.language
            lastWeekOpen.value = c.reviews.lastWeekUnreviewed()
            openPeriod(ReviewPeriod.WEEK, c.reviews.currentWeekStart())
            // First review with tracks but no questions yet: let DeepSeek write them.
            if (c.db.reviewQuestions().all().isEmpty() && c.tracks.active.first().isNotEmpty()) writeQuestions()
        }
    }

    private suspend fun openPeriod(period: ReviewPeriod, start: LocalDate) = show(c.reviews.prepare(period, start))

    fun openThisWeek() = viewModelScope.launch { openPeriod(ReviewPeriod.WEEK, c.reviews.currentWeekStart()) }
    fun openLastWeek() = viewModelScope.launch { openPeriod(ReviewPeriod.WEEK, c.reviews.currentWeekStart().minusDays(7)) }
    fun openLastMonth() = viewModelScope.launch { openPeriod(ReviewPeriod.MONTH, c.clock.today().minusMonths(1).withDayOfMonth(1)) }
    fun openThisYear() = viewModelScope.launch {
        val today = c.clock.today()
        openPeriod(ReviewPeriod.YEAR, today.withDayOfYear(1).let { if (today.dayOfYear < 15) it.minusYears(1) else it })
    }

    fun show(review: Review) = viewModelScope.launch {
        open.value = review
        answers.value = ReviewRepository.decodeAnswers(review.answers)
        fix.value = review.oneThingToFix
        top3.value = review.top3.joinToString("\n")
        suggestions.value = emptyList()
        error.value = null
        summary.value = ReviewRepository.decodeStats(review.stats)?.let { reviewSummary(it, c.reviews.previousStats(review), review.period, lang) }.orEmpty()
    }

    fun answer(question: String, text: String) {
        answers.value = answers.value + (question to text)
        saveDraft()
    }

    fun setFix(text: String) {
        fix.value = text
        saveDraft()
    }

    fun setTop3(text: String) {
        top3.value = text
        saveDraft()
    }

    private fun saveDraft() {
        val r = open.value ?: return
        viewModelScope.launch { open.value = c.reviews.saveDraft(r, answers.value, fix.value, top3Lines()) }
    }

    private fun top3Lines() = top3.value.lines().map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ')', ' ') }.filter { it.isNotEmpty() }.take(3)

    fun writeQuestions() = viewModelScope.launch {
        writing.value = true
        try {
            c.reviews.writeQuestions()
        } finally {
            writing.value = false
        }
    }

    fun addQuestion(text: String) = viewModelScope.launch { c.reviews.addQuestion(text) }
    fun updateQuestion(q: ReviewQuestion) = viewModelScope.launch { c.reviews.updateQuestion(q) }
    fun deleteQuestion(id: Long) = viewModelScope.launch { c.reviews.deleteQuestion(id) }

    fun submit() = viewModelScope.launch {
        val review = open.value ?: return@launch
        busy.value = true
        try {
            val asked = if (review.period == ReviewPeriod.WEEK) questions.value.map { it.text }.toSet() else emptySet()
            val (saved, ideas) = c.reviews.submit(review, answers.value.filterKeys { it in asked }, fix.value, top3Lines())
            open.value = saved
            suggestions.value = ideas
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

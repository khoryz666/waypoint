package com.khoryz666.waypoint.ingest

import com.khoryz666.waypoint.data.AppDatabase
import com.khoryz666.waypoint.data.LogEntry
import com.khoryz666.waypoint.data.SettingsStore
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.domain.AppClock
import com.khoryz666.waypoint.domain.Domain
import com.khoryz666.waypoint.domain.LogKind
import com.khoryz666.waypoint.domain.ParseStatus
import com.khoryz666.waypoint.domain.Structured
import com.khoryz666.waypoint.domain.UnitStatus
import com.khoryz666.waypoint.domain.dayTypeFor
import com.khoryz666.waypoint.llm.ApiException
import com.khoryz666.waypoint.llm.ContextBuilder
import com.khoryz666.waypoint.llm.LlmService
import com.khoryz666.waypoint.llm.ParseOutput
import com.khoryz666.waypoint.llm.Part
import com.khoryz666.waypoint.llm.SleepOutput
import com.khoryz666.waypoint.llm.toStructured
import kotlinx.serialization.SerializationException

/** Below this routing confidence the confirm card asks for the track (spec 5.2). */
const val ROUTE_CONFIDENCE = 0.7

/** What happened to one queued entry. */
enum class ParseOutcome { PARSED, FAILED, RETRY_LATER, PAUSED }

/**
 * Turns a PENDING entry into PARSED (or FAILED) with one Flash call: parse_sleep_image for
 * screenshots sent without a track, parse_input for everything else (spec 5.2, 6.2).
 */
class Parser(
    private val db: AppDatabase,
    private val llm: LlmService,
    private val context: ContextBuilder,
    private val settings: SettingsStore,
    private val images: ImageStore,
    private val clock: AppClock,
) {
    suspend fun parse(log: LogEntry): ParseOutcome {
        val s = settings.current()
        if (!s.hasApiKey || s.apiProblem != null) return ParseOutcome.PAUSED
        return try {
            val parsed = if (log.rawImageUris.isNotEmpty() && log.trackId == null) parseSleep(log) else parseText(log)
            db.logs().update(parsed)
            if (parsed.parseStatus == ParseStatus.FAILED) ParseOutcome.FAILED else ParseOutcome.PARSED
        } catch (e: ApiException.Network) {
            ParseOutcome.RETRY_LATER
        } catch (e: ApiException.KeyInvalid) {
            ParseOutcome.PAUSED
        } catch (e: ApiException.NoKey) {
            ParseOutcome.PAUSED
        } catch (e: ApiException.NoBalance) {
            ParseOutcome.PAUSED
        } catch (e: ApiException.Http) {
            if (e.code == 429 || e.code >= 500) ParseOutcome.RETRY_LATER else fail(log, e.message)
        } catch (e: ApiException.ModelUnavailable) {
            fail(log, e.message)
        } catch (e: SerializationException) {
            fail(log, e.message)
        } catch (e: IllegalArgumentException) {
            fail(log, e.message)
        }
    }

    private suspend fun fail(log: LogEntry, message: String?): ParseOutcome {
        db.logs().update(log.copy(parseStatus = ParseStatus.FAILED, parseError = message?.take(300)))
        return ParseOutcome.FAILED
    }

    private suspend fun profileBlock(sleepHint: Boolean): String {
        val s = settings.current()
        val type = dayTypeFor(clock.today(), db.dayTypes().all(), db.dayTypes().assignments(clock.today(), clock.today().plusDays(1)))
        return context.profile(s.profile, type?.name, sleepHint)
    }

    private suspend fun parseSleep(log: LogEntry): LogEntry {
        val parts = listOf(Part.Text(profileBlock(sleepHint = true) + "\n" + log.rawText.orEmpty())) +
            log.rawImageUris.mapNotNull(images::dataUrl).map(Part::Image)
        val out = llm.json("parse_sleep_image", parts, 600, SleepOutput.serializer())
        return log.copy(
            trackId = null,
            structured = Structured.Sleep(out.nights, out.screenType),
            parseStatus = ParseStatus.PARSED,
            confidence = 1.0,
            parseError = null,
        )
    }

    private suspend fun parseText(log: LogEntry): LogEntry {
        val active = db.tracks().active()
        val chosen = log.trackId?.let { id -> active.find { it.id == id } ?: db.tracks().get(id) }
        val trackList = (if (chosen != null) listOf(chosen) else active).map { trackLine(it, outline = true) }.joinToString("\n\n")
        val text = buildString {
            append(profileBlock(sleepHint = false))
            append("\n## Active tracks\n").append(trackList.ifEmpty { "(none)" })
            if (chosen != null) append("\n\nTrack chip: ${chosen.id} (the user chose this track)")
            append("\n\n## Report\n").append(log.rawText.orEmpty())
        }
        val parts = listOf(Part.Text(text)) + log.rawImageUris.mapNotNull(images::dataUrl).map(Part::Image)
        val out = llm.json("parse_input", parts, 800, ParseOutput.serializer())
        val structured = out.toStructured(settings.current().profile.currency, clock)
        val trackless = structured is Structured.Sleep || structured is Structured.Balance
        val routed = when {
            chosen != null -> chosen.id
            trackless -> null
            else -> out.trackId?.takeIf { id -> active.any { it.id == id } }
        }
        val validUnits = routed?.let { id -> db.units().forTrack(id).map { it.id }.toSet() }.orEmpty()
        return log.copy(
            trackId = routed,
            kind = kindOf(structured),
            structured = cleanUnits(structured, validUnits),
            parseStatus = ParseStatus.PARSED,
            confidence = if (chosen != null || trackless) 1.0 else out.confidence,
            parseError = null,
        )
    }

    private suspend fun trackLine(track: Track, outline: Boolean): String {
        val units = if (outline) db.units().forTrack(track.id) else emptyList()
        // Keep the outline small: open units first, at most 40 per track.
        val shown = (units.filter { it.status != UnitStatus.DONE } + units.filter { it.status == UnitStatus.DONE }.takeLast(5)).take(40)
        return "- ${track.id} [${track.domain}] ${track.title}: ${track.goal.lineSequence().firstOrNull().orEmpty()}" +
            if (shown.isEmpty()) "" else "\n" + shown.joinToString("\n") { "    ${it.id}: ${it.title} [${it.status}]" }
    }

    companion object {
        fun kindOf(s: Structured): LogKind = when (s) {
            is Structured.Exercise -> LogKind.EXERCISE
            is Structured.Balance -> LogKind.BALANCE
            is Structured.Commits -> LogKind.COMMITS
            is Structured.Recall -> LogKind.RECALL
            else -> LogKind.REPORT
        }

        /** Drops unit ids the model invented; only ids in the routed track's outline survive. */
        fun cleanUnits(s: Structured, valid: Set<Long>): Structured = when (s) {
            is Structured.Fundamental -> s.copy(unitsDone = s.unitsDone.filter { it in valid }, unitsStarted = s.unitsStarted.filter { it in valid })
            is Structured.Math -> s.copy(unitsDone = s.unitsDone.filter { it in valid }, unitsStarted = s.unitsStarted.filter { it in valid })
            is Structured.Reading -> s.copy(unitsDone = s.unitsDone.filter { it in valid })
            is Structured.Project -> s.copy(milestonesDone = s.milestonesDone.filter { it in valid })
            else -> s
        }

        /** The kind of log a track's domain produces when it has to be filled in by hand. */
        fun blankFor(domain: Domain?): Structured = when (domain) {
            Domain.EXERCISE -> Structured.Exercise()
            Domain.MATH -> Structured.Math()
            Domain.READING -> Structured.Reading()
            Domain.PROJECT -> Structured.Project()
            Domain.FUNDAMENTAL -> Structured.Fundamental()
            null -> Structured.Note()
        }
    }
}

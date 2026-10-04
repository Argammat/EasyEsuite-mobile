package com.easyesuite.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Inclusive ISO-8601 UTC bounds the API expects: `date_after=…T00:00:00Z&date_before=…T23:59:59Z`.
 * Bounds are computed in the *user's* zone (a "today" in Los Angeles is not a UTC day).
 */
data class DateRange(val start: LocalDate, val end: LocalDate, val label: String) {

    fun toQuery(zone: ZoneId = ZoneId.systemDefault()): Map<String, Any?> = mapOf(
        "date_after" to ISO_UTC.format(start.atStartOfDay(zone).toInstant()),
        "date_before" to ISO_UTC.format(end.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1)),
    )

    companion object {
        private val ISO_UTC: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

        fun today(zone: ZoneId = ZoneId.systemDefault()): DateRange {
            val d = LocalDate.now(zone); return DateRange(d, d, "Today")
        }
        fun yesterday(zone: ZoneId = ZoneId.systemDefault()): DateRange {
            val d = LocalDate.now(zone).minusDays(1); return DateRange(d, d, "Yesterday")
        }
        fun last7Days(zone: ZoneId = ZoneId.systemDefault()): DateRange {
            val d = LocalDate.now(zone); return DateRange(d.minusDays(6), d, "Last 7 days")
        }
        fun last30Days(zone: ZoneId = ZoneId.systemDefault()): DateRange {
            val d = LocalDate.now(zone); return DateRange(d.minusDays(29), d, "Last 30 days")
        }
        fun thisMonth(zone: ZoneId = ZoneId.systemDefault()): DateRange {
            val d = LocalDate.now(zone); return DateRange(d.withDayOfMonth(1), d, "This month")
        }
        fun lastMonth(zone: ZoneId = ZoneId.systemDefault()): DateRange {
            val first = LocalDate.now(zone).withDayOfMonth(1).minusMonths(1)
            return DateRange(first, first.plusMonths(1).minusDays(1), "Last month")
        }
        fun yearToDate(zone: ZoneId = ZoneId.systemDefault()): DateRange {
            val d = LocalDate.now(zone); return DateRange(d.withDayOfYear(1), d, "Year to date")
        }

        val presets: List<() -> DateRange> = listOf(
            { today() }, { yesterday() }, { last7Days() }, { last30Days() }, { thisMonth() }, { lastMonth() }, { yearToDate() },
        )
    }
}

object DateText {
    private val display = DateTimeFormatter.ofPattern("MMM d, yyyy")
    private val displayWithTime = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")

    /** Parses the API's `2026-10-03T19:54:02.861763Z` or `2026-09-03 16:14:32.325946+00:00` forms. */
    fun parse(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().replace(' ', 'T')
        return runCatching { Instant.parse(s) }.getOrElse {
            runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrElse {
                runCatching { LocalDate.parse(s.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
            }
        }
    }

    fun short(raw: String?, zone: ZoneId = ZoneId.systemDefault()): String =
        parse(raw)?.atZone(zone)?.format(display) ?: "—"

    fun long(raw: String?, zone: ZoneId = ZoneId.systemDefault()): String =
        parse(raw)?.atZone(zone)?.format(displayWithTime) ?: "—"

    /** "2h ago", "3d ago" for list rows. */
    fun relative(raw: String?, now: Instant = Instant.now()): String {
        val t = parse(raw) ?: return "—"
        val minutes = ChronoUnit.MINUTES.between(t, now)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 60 * 24 -> "${minutes / 60}h ago"
            minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)}d ago"
            else -> short(raw)
        }
    }

    /** `yyyy-MM-dd` for dates in request bodies. */
    fun apiDate(date: LocalDate): String = date.toString()

    fun isValid(raw: String): Boolean = try { LocalDate.parse(raw); true } catch (e: DateTimeParseException) { false }
}

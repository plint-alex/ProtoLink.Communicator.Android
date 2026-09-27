package ru.protolink.communicator.util

import com.google.gson.JsonElement
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale
import kotlin.math.abs

object JsonValues {
    fun asText(el: JsonElement?): String? {
        if (el == null || el.isJsonNull) return null
        if (!el.isJsonPrimitive) return null
        val p = el.asJsonPrimitive
        return when {
            p.isString -> p.asString
            p.isNumber -> p.asString
            p.isBoolean -> p.asBoolean.toString()
            else -> null
        }
    }
}

object ChatTime {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dayFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())

    /** ISO local date-time with optional fractional seconds (up to 9 digits, .NET often sends 7). */
    private val isoLocalFlexible: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
        .optionalEnd()
        .toFormatter()

    /**
     * Parse API timestamps preserving seconds and milliseconds.
     * Prefer UTC when the string has no zone (server CreationTime is UTC).
     */
    fun parseMillis(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val s = raw.trim()
        if (s.all { it.isDigit() }) {
            val n = s.toLongOrNull() ?: return 0L
            // Unix ms
            if (n > 1_000_000_000_000L) return n
            // Unix seconds
            if (n > 1_000_000_000L) return n * 1000L
        }
        runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()?.let { return it }
        // .NET often omits Z: 2026-09-27T10:23:19.5949261
        if (s.length >= 19 && s[4] == '-' && s[10] == 'T' && !s.contains('+') && !s.endsWith("Z", true)) {
            runCatching { Instant.parse(s + "Z").toEpochMilli() }.getOrNull()?.let { return it }
            runCatching {
                LocalDateTime.parse(s, isoLocalFlexible).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()?.let { return it }
        }
        runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        runCatching {
            LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }
        runCatching {
            LocalDateTime.parse(s, isoLocalFlexible).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }
        // Culture-ish: 27.09.2026 10:08:12 (seconds only)
        for (fmt in listOf(
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"),
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm:ss"),
            DateTimeFormatter.ofPattern("M/d/yyyy h:mm:ss a", Locale.US)
        )) {
            runCatching {
                LocalDateTime.parse(s, fmt).atZone(zone).toInstant().toEpochMilli()
            }.getOrNull()?.let { return it }
        }
        return 0L
    }

    /**
     * Prefer entity creationTime (server ms precision). Fall back to value DateTime.
     * When both exist and match within 1s, keep creationTime so fractional seconds win.
     */
    fun resolveMessageMillis(creationTime: String?, updateTime: String?, valueDateRaw: String?): Long {
        val created = parseMillis(creationTime).takeIf { it > 0 }
            ?: parseMillis(updateTime).takeIf { it > 0 }
            ?: 0L
        val fromValue = parseMillis(valueDateRaw).takeIf { it > 0 } ?: 0L
        return when {
            created > 0L && fromValue > 0L ->
                if (abs(created - fromValue) < 1000L) created else maxOf(created, fromValue)
            created > 0L -> created
            else -> fromValue
        }
    }

    fun formatTime(millis: Long): String {
        if (millis <= 0L) return ""
        return Instant.ofEpochMilli(millis).atZone(zone).format(timeFmt)
    }

    fun formatDayLabel(millis: Long): String {
        if (millis <= 0L) return ""
        val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> day.format(dayFmt)
        }
    }

    fun dayKey(millis: Long): LocalDate? {
        if (millis <= 0L) return null
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    }

    fun looksLikeIsoDate(s: String): Boolean {
        if (s.length < 10) return false
        return s[4] == '-' && (s.contains('T') || s.count { it == '-' } >= 2)
    }
}

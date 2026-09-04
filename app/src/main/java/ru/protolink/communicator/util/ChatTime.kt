package ru.protolink.communicator.util

import com.google.gson.JsonElement
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    fun parseMillis(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { java.time.LocalDateTime.parse(raw).atZone(zone).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        return 0L
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
        return s[4] == '-' && (s.contains('T') || s.length >= 10 && s.count { it == '-' } >= 2)
    }
}

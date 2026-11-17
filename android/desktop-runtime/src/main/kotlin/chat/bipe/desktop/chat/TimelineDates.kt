// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object TimelineDates {
    fun label(timestamp: Long, today: LocalDate, zone: ZoneId, locale: Locale, todayLabel: String, yesterdayLabel: String): String {
        val day = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        return when (day) {
            today -> todayLabel
            today.minusDays(1) -> yesterdayLabel
            else -> if (day.year != today.year) DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(day)
                else DateTimeFormatter.ofPattern(if (locale.language == "pt") "EEEE, d 'de' MMMM" else "EEEE, MMMM d", locale).format(day)
        }
    }

    fun fullTimestamp(timestamp: Long, zone: ZoneId, locale: Locale): String = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(timestamp).atZone(zone))
}

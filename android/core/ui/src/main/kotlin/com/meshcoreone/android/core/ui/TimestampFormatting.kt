// PortedFrom: MC1/Extensions/Date+RelativeFormatting.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Components/ConversationTimestamp.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Components/RelativeTimestampText.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ui

import android.content.res.Resources
import android.icu.text.DateTimePatternGenerator
import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.SimpleDateFormat
import android.icu.util.ULocale
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.chrono.Chronology
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

data class RelativeTimeComponent(val amount: Long, val unit: RelativeDateTimeFormatter.RelativeDateTimeUnit)

class TimestampFormatting(
    private val locale: Locale,
    private val zone: ZoneId,
    private val calendar: Chronology = Chronology.ofLocale(locale),
    private val use24HourClock: Boolean? = null,
) {
    private fun relativeFormatter() = RelativeDateTimeFormatter.getInstance(
        ULocale.forLocale(locale), null, RelativeDateTimeFormatter.Style.SHORT,
        DisplayContext.CAPITALIZATION_NONE,
    )

    fun relative(date: Instant, now: Instant): String {
        val component = relativeComponent(date, now)
        return relativeFormatter().formatNumeric(component.amount.toDouble(), component.unit)
    }

    private fun displayTimeZone() =
        android.icu.util.TimeZone.getTimeZone(java.util.TimeZone.getTimeZone(zone).id)

    fun relativeComponent(date: Instant, now: Instant): RelativeTimeComponent {
        val earlier = calendar.zonedDateTime(minOf(date, now), zone)
        val later = calendar.zonedDateTime(maxOf(date, now), zone)
        val units = listOf(
            ChronoUnit.YEARS to RelativeDateTimeFormatter.RelativeDateTimeUnit.YEAR,
            ChronoUnit.MONTHS to RelativeDateTimeFormatter.RelativeDateTimeUnit.MONTH,
            ChronoUnit.WEEKS to RelativeDateTimeFormatter.RelativeDateTimeUnit.WEEK,
            ChronoUnit.DAYS to RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY,
            ChronoUnit.HOURS to RelativeDateTimeFormatter.RelativeDateTimeUnit.HOUR,
            ChronoUnit.MINUTES to RelativeDateTimeFormatter.RelativeDateTimeUnit.MINUTE,
            ChronoUnit.SECONDS to RelativeDateTimeFormatter.RelativeDateTimeUnit.SECOND,
        )
        val (unit, displayUnit) = units.first { (unit, _) ->
            unit == ChronoUnit.SECONDS || earlier.until(later, unit) >= 1
        }
        val whole = earlier.until(later, unit)
        val anchor = earlier.plus(whole, unit)
        val next = anchor.plus(1, unit)
        fun seconds(duration: Duration): Double = duration.seconds.toDouble() + duration.nano / 1_000_000_000.0
        val remaining = seconds(Duration.between(anchor.toInstant(), later.toInstant()))
        val unitLength = seconds(Duration.between(anchor.toInstant(), next.toInstant()))
        val rounded = Math.addExact(whole, if (remaining >= unitLength / 2) 1L else 0L)
        return RelativeTimeComponent(if (date < now) -rounded else rounded, displayUnit)
    }

    fun abbreviatedDate(date: Instant): String {
        val pattern = DateTimePatternGenerator.getInstance(locale).getBestPattern("MMMd")
        val formatter = SimpleDateFormat(pattern, locale)
        formatter.timeZone = displayTimeZone()
        return formatter.format(Date.from(date))
    }

    fun relativeTimestamp(date: Instant, now: Instant, resources: Resources): String {
        val age = Duration.between(date, now)
        return when {
            age < Duration.ofSeconds(60) -> resources.getString(AppChatsStrings.chatsTimestampNow)
            age >= Duration.ofSeconds(604_800) -> abbreviatedDate(date)
            else -> relative(date, now)
        }
    }

    fun relativeTimestamp(timestamp: UInt, now: Instant, resources: Resources): String =
        relativeTimestamp(Instant.ofEpochSecond(timestamp.toLong()), now, resources)

    fun conversationTimestamp(date: Instant, now: Instant): String {
        val dateDay = calendar.zonedDateTime(date, zone).toLocalDate()
        val nowDay = calendar.zonedDateTime(now, zone).toLocalDate()
        return when (dateDay) {
            nowDay -> SimpleDateFormat(DateTimePatternGenerator.getInstance(locale).getBestPattern(
                when (use24HourClock) { true -> "Hm"; false -> "hm"; null -> "jm" },
            ), locale).also {
                it.timeZone = displayTimeZone()
            }.format(Date.from(date))
            nowDay.minus(1, ChronoUnit.DAYS) -> {
                val component = relativeComponent(date, now)
                RelativeDateTimeFormatter.getInstance(ULocale.forLocale(locale), null,
                    RelativeDateTimeFormatter.Style.LONG, DisplayContext.CAPITALIZATION_NONE)
                    .format(component.amount.toDouble(), component.unit)
            }
            else -> abbreviatedDate(date)
        }
    }
}

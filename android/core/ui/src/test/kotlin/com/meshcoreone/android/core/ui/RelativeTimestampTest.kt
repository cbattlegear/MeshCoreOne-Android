// PortedFrom: MC1Tests/Views/RelativeTimestampTextTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS")
class RelativeTimestampTest : SourceCaseProof() {
    private val now = Instant.ofEpochSecond(1_700_000_000)
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources
    private val formatter = TimestampFormatting(Locale.US, ZoneOffset.UTC)
    private fun format(secondsAgo: Long) = formatter.relativeTimestamp((now.epochSecond - secondsAgo).toUInt(), now, resources)
    private fun nowText() = resources.getString(AppChatsStrings.chatsTimestampNow)

    @OriginalCase("RelativeTimestampTextTests::Returns 'Now' for timestamps under 60 seconds()")
    @Test fun immediate() = prove { assertEquals(nowText(), format(0)) }
    @OriginalCase("RelativeTimestampTextTests::Returns 'Now' at 59 seconds ago()")
    @Test fun lastNowSecond() = prove { assertEquals(nowText(), format(59)) }
    @OriginalCase("RelativeTimestampTextTests::Returns relative format at exactly 60 seconds()")
    @Test fun exactMinute() = prove { assertNotEquals(nowText(), format(60)); assertTrue(format(60).isNotEmpty()) }
    @OriginalCase("RelativeTimestampTextTests::Returns non-empty string for minutes ago()")
    @Test fun minutes() = prove { assertTrue(format(120).isNotEmpty()) }
    @OriginalCase("RelativeTimestampTextTests::Returns non-empty string for hours ago()")
    @Test fun hours() = prove { assertTrue(format(3600).isNotEmpty()) }
    @OriginalCase("RelativeTimestampTextTests::Returns non-empty string for yesterday()")
    @Test fun yesterday() = prove { assertTrue(format(86400).isNotEmpty()) }
    @OriginalCase("RelativeTimestampTextTests::Returns non-empty string for days ago()")
    @Test fun days() = prove { assertTrue(format(172_800).isNotEmpty()) }
    @OriginalCase("RelativeTimestampTextTests::Returns abbreviated date format for 7+ days ago()")
    @Test fun week() = prove { assertTrue(' ' in format(604_800)) }
    @OriginalCase("RelativeTimestampTextTests::Returns abbreviated date format for old dates()")
    @Test fun old() = prove { assertTrue(' ' in format(2_592_000)) }
    @OriginalCase("RelativeTimestampTextTests::Uses relative format just before week threshold()")
    @Test fun lastRelativeSecond() = prove { assertTrue(format(604_799).isNotEmpty()) }
    @OriginalCase("RelativeTimestampTextTests::Uses date format at exactly week threshold()")
    @Test fun exactWeek() = prove { assertTrue(' ' in format(604_800)) }

    @Test fun independentNativeTextVectorsDistinguishRelativeUnitsAndTheExactWeekBoundary() {
        for ((age, expected) in listOf(
            60L to "1 min. ago",
            120L to "2 min. ago",
            3_600L to "1 hr. ago",
            86_400L to "1 day ago",
            172_800L to "2 days ago",
            604_799L to "7 days ago",
            604_800L to "Nov 7",
            2_592_000L to "Oct 15",
        )) {
            assertEquals(expected, format(age), "Native en-US/UTC age $age")
        }
    }

    @Test fun futureFractionalBoundaryAndCivilYesterdayAcrossDstUseTheInjectedClockAndZone() {
        assertEquals(nowText(), formatter.relativeTimestamp(now.plusSeconds(3600), now, resources))
        assertEquals(nowText(), formatter.relativeTimestamp(now.minusSeconds(59).minusNanos(999_999_999), now, resources))
        val zone = ZoneId.of("America/New_York")
        val current = Instant.parse("2024-03-11T04:30:00Z")
        val previousDay = Instant.parse("2024-03-10T05:30:00Z")
        val text = TimestampFormatting(Locale.US, zone).conversationTimestamp(previousDay, current)
        assertTrue(text.lowercase(Locale.ROOT).contains("yesterday"), text)
        for (locale in listOf(Locale.CHINA, Locale.KOREA, Locale.GERMAN, Locale("ar"))) {
            assertTrue(TimestampFormatting(locale, zone).relative(previousDay, current).isNotEmpty())
        }

    }

    @Test fun relativeComponentsAreIntegralAndChooseMinutesHoursDaysWeeksMonthsAndYears() {
        val reference = Instant.parse("2024-01-15T12:00:00Z")
        val vectors = listOf(
            Triple(reference.minusSeconds(90), -2L, android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.MINUTE),
            Triple(reference.plusSeconds(90), 2L, android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.MINUTE),
            Triple(reference.minusSeconds(5_400), -2L, android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.HOUR),
            Triple(reference.minusSeconds(129_600), -2L, android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY),
            Triple(reference.minusSeconds(1_209_600), -2L, android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.WEEK),
            Triple(Instant.parse("2023-12-15T12:00:00Z"), -1L, android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.MONTH),
            Triple(reference.minusSeconds(400L * 86400), -1L, android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.YEAR),
        )
        for ((date, amount, unit) in vectors) {
            assertEquals(RelativeTimeComponent(amount, unit), formatter.relativeComponent(date, reference))
        }
        assertFalse(formatter.relative(reference.minusSeconds(90), reference).contains("1.5"))
    }

    @Test fun calendarDayLengthAcrossDstUsesTheInjectedZoneRatherThanFixed86400SecondDays() {
        val source = Instant.parse("2024-03-09T17:00:00Z")
        val later = Instant.parse("2024-03-10T16:00:00Z")
        val local = TimestampFormatting(Locale.US, ZoneId.of("America/New_York"))
        assertEquals(RelativeTimeComponent(-1, android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY),
            local.relativeComponent(source, later))
        assertEquals(RelativeTimeComponent(-23, android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.HOUR),
            formatter.relativeComponent(source, later))
        val autumn = local.relativeComponent(Instant.parse("2024-11-02T16:00:00Z"), Instant.parse("2024-11-03T17:00:00Z"))
        assertEquals(RelativeTimeComponent(-1, android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY), autumn)
    }

    @Test fun numericRelativeTextDoesNotBecomeNamedYesterdayAndKeepsIntegralNativeUnits() {
        val expected = android.icu.text.RelativeDateTimeFormatter.getInstance(
            android.icu.util.ULocale.forLocale(Locale.US), null,
            android.icu.text.RelativeDateTimeFormatter.Style.SHORT, android.icu.text.DisplayContext.CAPITALIZATION_NONE)
        for ((seconds, amount, unit) in listOf(
            Triple(90L, -2.0, android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.MINUTE),
            Triple(5_400L, -2.0, android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.HOUR),
            Triple(86_400L, -1.0, android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY),
        )) {
            assertEquals(expected.formatNumeric(amount, unit), formatter.relative(now.minusSeconds(seconds), now))
        }
        assertNotEquals(expected.format(-1.0, android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY),
            formatter.relative(now.minusSeconds(86_400), now))
    }

    @Test fun conversationPreviousDayUsesNamedRelativeComponentsRatherThanAlwaysYesterday() {
        val current = Instant.parse("2024-04-16T08:00:00Z")
        val lastNight = Instant.parse("2024-04-15T22:00:00Z")
        val expected = android.icu.text.RelativeDateTimeFormatter.getInstance(
            android.icu.util.ULocale.forLocale(Locale.US), null,
            android.icu.text.RelativeDateTimeFormatter.Style.LONG, android.icu.text.DisplayContext.CAPITALIZATION_NONE)
        assertEquals(expected.format(-10.0, android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.HOUR),
            formatter.conversationTimestamp(lastNight, current))
        assertEquals(expected.format(-1.0, android.icu.text.RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY),
            formatter.conversationTimestamp(current.minusSeconds(86_400), current))
    }

    @Test fun conversationClockHonorsExplicit24HourChoiceAndFixedOffsetWithoutFallingBackToGmt() {
        val date = Instant.parse("2024-01-15T15:05:00Z")
        assertEquals("15:05", TimestampFormatting(Locale.US, ZoneOffset.UTC, use24HourClock = true)
            .conversationTimestamp(date, date.plusSeconds(60)))
        val twelveHour = TimestampFormatting(Locale.US, ZoneOffset.UTC, use24HourClock = false)
            .conversationTimestamp(date, date.plusSeconds(60))
        assertTrue(twelveHour.startsWith("3:05"), twelveHour)
        assertTrue(twelveHour.contains("PM"), twelveHour)
        val offset = TimestampFormatting(Locale.US, ZoneOffset.ofHoursMinutes(5, 45), use24HourClock = true)
        assertEquals("20:50", offset.conversationTimestamp(date, date.plusSeconds(60)))
        assertEquals("Jan 16", offset.abbreviatedDate(Instant.parse("2024-01-15T20:00:00Z")))
    }
}

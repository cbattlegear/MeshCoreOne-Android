// PortedFrom: MC1/Views/Components/ConversationTimestamp.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Components/RelativeTimestampText.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
private fun currentMinute(clock: Clock): Instant {
    val time by produceState(clock.instant(), clock) {
        while (currentCoroutineContext().isActive) {
            val instant = clock.instant()
            val wait = 60_000L - Math.floorMod(instant.toEpochMilli(), 60_000L)
            delay(wait)
            value = clock.instant()
        }
    }
    return time
}

@Composable
fun ConversationTimestamp(
    date: Instant,
    clock: Clock,
    modifier: Modifier = Modifier,
    referenceDate: Instant? = null,
    style: TextStyle = MaterialTheme.typography.bodySmall,
) {
    LocalConfiguration.current
    val context = LocalContext.current
    val resources = context.resources
    val now = referenceDate ?: currentMinute(clock)
    val formatter = TimestampFormatting(resourceLocale(resources), clock.zone,
        use24HourClock = android.text.format.DateFormat.is24HourFormat(context))
    Text(formatter.conversationTimestamp(date, now), modifier, style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun RelativeTimestampText(
    date: Instant,
    clock: Clock,
    modifier: Modifier = Modifier,
    referenceDate: Instant? = null,
) {
    LocalConfiguration.current
    val resources = LocalContext.current.resources
    val now = referenceDate ?: currentMinute(clock)
    Text(TimestampFormatting(resourceLocale(resources), clock.zone).relativeTimestamp(date, now, resources),
        modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun RelativeTimestampText(timestamp: UInt, clock: Clock, modifier: Modifier = Modifier, referenceDate: Instant? = null) =
    RelativeTimestampText(Instant.ofEpochSecond(timestamp.toLong()), clock, modifier, referenceDate)

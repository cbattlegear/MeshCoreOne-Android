// AndroidOnly: WP-312 Advanced map ranges based on the post-pin iOS map filter.
package com.meshcoreone.android.core.maps

import java.time.Duration
import java.time.Instant

enum class MapDurationUnit(val seconds: Long) {
    minutes(60), hours(3600), days(86400),
}

@ConsistentCopyVisibility
data class MapDuration private constructor(val value: Int, val unit: MapDurationUnit) {
    val seconds: Long get() = value.toLong() * unit.seconds

    companion object {
        fun of(value: Int, unit: MapDurationUnit) = MapDuration(value.coerceIn(1, 9999), unit)
    }
}

@ConsistentCopyVisibility
data class MapLastHeardRange private constructor(val minAge: MapDuration?, val maxAge: MapDuration?) {
    val isActive: Boolean get() = minAge != null || maxAge != null

    fun contains(heardAt: Instant?, now: Instant): Boolean {
        if (!isActive) return true
        if (heardAt == null) return false
        val age = if (heardAt.isAfter(now)) Duration.ZERO else Duration.between(heardAt, now)
        return (minAge == null || age >= Duration.ofSeconds(minAge.seconds)) &&
            (maxAge == null || age <= Duration.ofSeconds(maxAge.seconds))
    }

    fun withMinAge(value: MapDuration?) = of(value, maxAge)
    fun withMaxAge(value: MapDuration?): MapLastHeardRange =
        if (minAge != null && value != null && minAge.seconds > value.seconds) MapLastHeardRange(value, value)
        else MapLastHeardRange(minAge, value)

    companion object {
        fun of(minAge: MapDuration? = null, maxAge: MapDuration? = null): MapLastHeardRange =
            MapLastHeardRange(minAge, if (minAge != null && maxAge != null && minAge.seconds > maxAge.seconds) minAge else maxAge)
    }
}

@ConsistentCopyVisibility
data class MapHopRange private constructor(val min: Int, val max: Int?) {
    val isActive: Boolean get() = min > 0 || max != null
    fun contains(hops: Long?): Boolean =
        !isActive || (hops != null && hops >= min && (max == null || hops <= max))

    fun withMin(value: Int) = of(value, max)
    fun withMax(value: Int?): MapHopRange {
        val upper = value?.coerceIn(0, 63)
        return of(if (upper != null && min > upper) upper else min, upper)
    }

    companion object {
        fun of(min: Int = 0, max: Int? = null): MapHopRange {
            val lower = min.coerceIn(0, 63)
            val upper = max?.coerceIn(0, 63)?.coerceAtLeast(lower)
            return MapHopRange(lower, upper?.takeUnless { it == 63 })
        }
    }
}

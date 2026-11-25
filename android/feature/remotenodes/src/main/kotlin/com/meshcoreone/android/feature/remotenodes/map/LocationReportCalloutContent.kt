// PortedFrom: MC1/Views/RemoteNodes/Location/LocationReportCallout.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.map

import java.time.Instant

/**
 * Content of the popover shown when a location report pin is tapped (Swift `LocationReportCallout`):
 * a headline relative time and a detail line of the absolute time plus the altitude when known. A
 * report is an observation, so the callout states when, not a name or an action. Text formatting
 * (`LocationReportFormat` relative/absolute time and altitude) is passed in by the caller.
 */
data class LocationReportCalloutContent(val timestamp: Instant, val altitude: Double?) {
    /** Headline: `LocationReportFormat.relativeTime(for: timestamp, relativeTo: now)`. */
    fun headline(now: Instant, relativeTime: (Instant, Instant) -> String): String = relativeTime(timestamp, now)

    /** `"<absolute time>"`, plus `" · <altitude>"` when the report carries an altitude. */
    fun detail(absoluteTime: (Instant) -> String, altitudeText: (Double) -> String): String {
        val time = absoluteTime(timestamp)
        return altitude?.let { "$time$DETAIL_SEPARATOR${altitudeText(it)}" } ?: time
    }

    companion object {
        private const val DETAIL_SEPARATOR = " · "

        fun of(report: LocationPathMapBuilder.LocationReport): LocationReportCalloutContent =
            LocationReportCalloutContent(report.timestamp, report.altitude)
    }
}

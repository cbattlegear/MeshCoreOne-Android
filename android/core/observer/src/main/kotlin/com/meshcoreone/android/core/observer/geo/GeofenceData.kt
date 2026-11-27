// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.geo

/**
 * Plain-text polygon format (no JSON library in this module): `polygon <region>` starts a ring, then one `<lat> <lon>`
 * vertex per line. `#` comments and blank lines are ignored. Throws IllegalArgumentException naming the line on bad input.
 */
object GeofenceData {
    fun parse(text: String): Map<String, List<GeoPolygon>> {
        val result = LinkedHashMap<String, MutableList<GeoPolygon>>()
        var region: String? = null
        var vertices = ArrayList<LatLon>()
        var startLine = 0

        fun finish() {
            val name = region ?: return
            result.getOrPut(name) { ArrayList() } += try {
                GeoPolygon(vertices)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("line $startLine: polygon '$name': ${e.message}")
            }
        }

        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            val number = index + 1
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val parts = line.split(Regex("\\s+"))
            if (parts[0] == "polygon") {
                require(parts.size == 2) { "line $number: expected 'polygon <region>'" }
                finish()
                region = parts[1]; vertices = ArrayList(); startLine = number
            } else {
                require(region != null) { "line $number: vertex before any 'polygon' line" }
                require(parts.size == 2) { "line $number: expected '<lat> <lon>'" }
                val lat = parts[0].toDoubleOrNull()
                val lon = parts[1].toDoubleOrNull()
                require(lat != null && lon != null) { "line $number: not a number" }
                vertices += try {
                    LatLon(lat, lon)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("line $number: ${e.message}")
                }
            }
        }
        finish()
        return result
    }
}

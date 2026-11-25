// PortedFrom: MC1/Services/RegionResolver.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/RegionalAreas.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.content

import com.meshcoreone.android.core.services.device.RegionalAreas

object RegionalAreaMatcher : RegionAreaMatching {
    override fun matchSubdivision(country: String, normalized: String?): String? =
        RegionalAreas.matchSubdivision(country, normalized)

    override fun matchCounty(country: String, state: String?, normalized: String?): String? =
        RegionalAreas.matchCounty(country, state, normalized)
}

// PortedFrom: MC1/Resources/Generated/L10n.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// WP-005 Native typed printf calls and lossless Long plural selection for the twelve locales.
package com.meshcoreone.android.core.l10n

import android.content.res.Resources
import java.util.Locale
import kotlin.math.abs

internal object L10nFormatting {
    private fun locale(resources: Resources): Locale = resources.configuration.locales[0]

    internal fun format(resources: Resources, template: String, args: Array<out Any>): String {
        require(args.filterIsInstance<Double>().all(Double::isFinite)) {
            "Nonfinite printf floating-point arguments are not supported by the pinned localization contract"
        }
        return String.format(locale(resources), template, *args)
    }

    fun string(resources: Resources, resourceId: Int, vararg args: Any): String =
        format(resources, resources.getString(resourceId), args)

    fun plural(resources: Resources, resourceId: Int, quantity: Long, vararg args: Any): String =
        format(resources, resources.getQuantityString(resourceId, quantitySelector(quantity)), args)

    internal fun quantitySelector(quantity: Long): Int {
        if (quantity in 0..Int.MAX_VALUE.toLong()) return quantity.toInt()
        if (quantity in -Int.MAX_VALUE.toLong()..-1) return (-quantity).toInt()
        // The twelve locales' integer CLDR rules above one million depend only on
        // residues modulo 10/100/1,000,000. Keep those residues without narrowing
        // the displayed signed number, including the native Int.MIN_VALUE
        // absolute-value overflow, and never map a large "...1" to 1.
        return 1_000_000 + abs(quantity % 1_000_000).toInt()
    }
}

// PortedFrom: MC1/Intents/MC1AppShortcutsProvider.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.platform.shortcuts

/** Framework-free description of one dynamic shortcut; stable ids survive republishing. */
data class ShortcutSpec(
    val id: String,
    val shortLabel: String,
    val longLabel: String,
    val action: String,
    val targetId: String? = null,
    val reach: AdvertReach? = null,
    val sharesToTarget: Boolean = false,
)

object ShortcutPlan {
    /** Status and both advert shortcuts first, then one send shortcut per recipient (direct-share targets) up to the cap. */
    fun build(targets: List<ShortcutTarget>, text: ShortcutText, maxCount: Int): List<ShortcutSpec> {
        val fixed = listOf(
            ShortcutSpec(ShortcutContract.ID_STATUS, text.statusShortTitle, text.statusShortTitle, ShortcutContract.ACTION_STATUS),
            advert(AdvertReach.ZERO_HOP, ShortcutContract.ID_ADVERT_ZERO_HOP, text),
            advert(AdvertReach.FLOOD, ShortcutContract.ID_ADVERT_FLOOD, text),
        )
        val room = (maxCount - fixed.size).coerceAtLeast(0)
        val recipients = targets
            .filter { it.id.length <= ShortcutContract.MAX_ID_CHARS - ShortcutContract.SEND_SHORTCUT_PREFIX.length }
            .take(room)
            .map {
                ShortcutSpec(
                    id = ShortcutContract.SEND_SHORTCUT_PREFIX + it.id,
                    shortLabel = it.displayName,
                    longLabel = it.displayName + " - " + it.subtitle,
                    action = ShortcutContract.ACTION_SEND_TARGET,
                    targetId = it.id,
                    sharesToTarget = true,
                )
            }
        return (fixed + recipients).take(maxCount.coerceAtLeast(0))
    }

    private fun advert(reach: AdvertReach, id: String, text: ShortcutText) = ShortcutSpec(
        id = id,
        shortLabel = text.advertShortTitle,
        longLabel = text.advertShortTitle + " (" + text.advertReachLabel(reach) + ")",
        action = ShortcutContract.ACTION_ADVERT,
        reach = reach,
    )
}

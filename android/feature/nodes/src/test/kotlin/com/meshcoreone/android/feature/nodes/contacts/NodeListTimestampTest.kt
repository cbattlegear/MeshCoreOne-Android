// AndroidOnly: WP-311 Deterministic upstream 2570130657 node-list timestamp and lazy-row snapshot regressions.
package com.meshcoreone.android.feature.nodes.contacts

import com.meshcoreone.android.feature.nodes.model.NodeSegment
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import com.meshcoreone.android.feature.nodes.support.Fixtures.contact
import com.meshcoreone.android.feature.nodes.support.Harness
import com.meshcoreone.android.feature.nodes.support.ManualClock
import com.meshcoreone.android.feature.nodes.support.scenario
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class NodeListTimestampTest {
    private val referenceTime = Instant.ofEpochSecond(1_700_000_000)

    @Test
    fun `plausible adverts include both bounds and prevail over newer receiver timestamps`() {
        for (advert in listOf(1_577_836_800u, 1_600_000_000u, 1_700_000_300u)) {
            val row = contact(lastAdvertTimestamp = advert, lastModified = UInt.MAX_VALUE)
                .copy(lastHeardTimestamp = 1_700_000_600u)
            assertEquals(advert, row.nodeListTimestamp(referenceTime), "Advert $advert")
        }
    }

    @Test
    fun `invalid adverts fall back only to positive last heard without validating its age`() {
        for (advert in listOf(0u, 1_577_836_799u, 1_700_000_301u, UInt.MAX_VALUE)) {
            for (heard in listOf(1u, 1_700_000_600u, UInt.MAX_VALUE)) {
                val row = contact(lastAdvertTimestamp = advert).copy(lastHeardTimestamp = heard)
                assertEquals(heard, row.nodeListTimestamp(referenceTime), "Advert $advert, heard $heard")
            }
            for (heard in listOf(null, 0u)) {
                val row = contact(lastAdvertTimestamp = advert, lastModified = UInt.MAX_VALUE)
                    .copy(lastHeardTimestamp = heard)
                assertNull(row.nodeListTimestamp(referenceTime), "Advert $advert, heard $heard")
            }
        }
    }

    @Test
    fun `sort uses inclusive validity bounds and keeps unknowns and equal dates stable`() = scenario {
        val holder = ContactsStateHolder(Harness(clock).dependencies, scope)
        for ((advert, expected) in listOf(
            1_577_836_799u to listOf("Other", "Subject"),
            1_577_836_800u to listOf("Subject", "Other"),
            1_700_000_300u to listOf("Subject", "Other"),
            1_700_000_301u to listOf("Other", "Subject"),
        )) {
            val subject = contact(name = "Subject", lastAdvertTimestamp = advert, lastModified = UInt.MAX_VALUE)
                .copy(lastHeardTimestamp = 1_577_836_797u)
            val other = contact(name = "Other").copy(lastHeardTimestamp = 1_577_836_798u)
            holder.seed { it.copy(contacts = listOf(subject, other)) }
            assertEquals(expected, holder.filteredContacts(
                "", NodeSegment.CONTACTS, NodeSortOrder.LAST_HEARD, null, referenceTime,
            ).map { it.name }, "Advert $advert")
        }
        val knownZ = contact(name = "Known Z", lastAdvertTimestamp = 1_699_999_990u)
        val knownA = contact(name = "Known A", lastAdvertTimestamp = 1_699_999_990u, lastModified = UInt.MAX_VALUE)
        val unknownZ = contact(name = "Unknown Z", lastModified = UInt.MAX_VALUE)
        val unknownA = contact(name = "Unknown A").copy(lastHeardTimestamp = 0u)
        holder.seed { it.copy(contacts = listOf(unknownZ, knownZ, unknownA, knownA)) }
        assertEquals(listOf(knownZ, knownA, unknownZ, unknownA), holder.filteredContacts(
            "", NodeSegment.CONTACTS, NodeSortOrder.entries.single { it.rawValue == "lastHeard" }, null, referenceTime,
        ))
    }

    @Test
    fun `delayed rows keep the date used for ordering until the list is reevaluated`() = scenario {
        for (heard in listOf(1_699_913_600u, null, 0u)) {
            val harness = Harness(ManualClock(referenceTime))
            val holder = ContactsStateHolder(harness.dependencies, scope)
            val actions = ContactListActions(holder, harness.dependencies)
            val lateAdvert = contact(name = "Late advert", lastAdvertTimestamp = 1_700_000_301u)
                .copy(lastHeardTimestamp = heard)
            val other = contact(name = "Other", lastAdvertTimestamp = 1_699_996_400u)
            holder.seed { it.copy(contacts = listOf(lateAdvert, other)) }
            val original = actions.filteredContacts(
                "", NodeSegment.CONTACTS, NodeSortOrder.LAST_HEARD, null,
            )
            harness.clock.advanceBy(120.seconds)
            val refreshed = actions.filteredContacts(
                "", NodeSegment.CONTACTS, NodeSortOrder.LAST_HEARD, null,
            )
            assertEquals(listOf(other, lateAdvert), original.contacts)
            assertEquals(heard?.takeIf { it > 0u }, original.timestampsById[lateAdvert.id])
            assertEquals(listOf(lateAdvert, other), refreshed.contacts)
            assertEquals(1_700_000_301u, refreshed.timestampsById[lateAdvert.id])
        }
    }
}

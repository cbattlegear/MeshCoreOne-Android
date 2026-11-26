// PortedFrom: MC1Services/Sources/MC1Services/Services/InboundHopAdoption.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.reactions

/** The `(hopCount, advertTimestamp)` pair to store for an inbound advert. Swift `Int` hop counts are 64-bit. */
internal data class InboundHopAdoption(val hopCount: Long, val advertTimestamp: UInt?)

/**
 * Adopt rule for inbound advert hop counts, shared by the persistence store and its test mock so the policy
 * has a single definition. Returns the pair to store, or null to no-op (the caller skips the save to avoid
 * observer churn on repeated floods).
 *
 * Rules:
 *  - stored timestamp null -> adopt the incoming pair
 *  - incoming.ts > stored.ts -> adopt (newer advert; hop may rise or fall)
 *  - incoming.ts == stored.ts && incoming.hops < stored.hops -> adopt (closer copy of this broadcast)
 *  - else -> no-op
 *
 * A null incoming timestamp is "no grouping signal": adopted only while the stored timestamp is also null.
 * Once a timestamp is stored, an ungrouped incoming read is a no-op, since its ordering against the stored
 * value is unknowable. A null stored hop count compares as `Long.MAX_VALUE` (Swift `Int.max`).
 *
 * Known limitation: a node that reboots and loses its RTC re-advertises with a lower timestamp, freezing the
 * stored count until the row ages out of the discovered-node cap. Reset detection is out of scope.
 *
 * core:data keeps an internal copy (`RepositoryPolicies.adoptInboundHop`) because it cannot see this module;
 * `InboundHopAdoptionTest` pins the two to the same results.
 */
internal fun adoptInboundHop(
    storedHops: Long?,
    storedTimestamp: UInt?,
    incomingHops: Long,
    incomingTimestamp: UInt?,
): InboundHopAdoption? {
    if (storedTimestamp == null) return InboundHopAdoption(incomingHops, incomingTimestamp)
    if (incomingTimestamp == null) return null
    if (incomingTimestamp > storedTimestamp) return InboundHopAdoption(incomingHops, incomingTimestamp)
    if (incomingTimestamp == storedTimestamp && incomingHops < (storedHops ?: Long.MAX_VALUE)) {
        return InboundHopAdoption(incomingHops, incomingTimestamp)
    }
    return null
}

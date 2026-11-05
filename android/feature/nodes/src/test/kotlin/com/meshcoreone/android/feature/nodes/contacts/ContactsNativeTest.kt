// AndroidOnly: WP-311 Native coverage of nodes-list sync, delete, favorite/block/nickname, load races and row actions.
package com.meshcoreone.android.feature.nodes.contacts

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.deps.NodesContactFailure
import com.meshcoreone.android.feature.nodes.deps.NodesConnectionSnapshot
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import com.meshcoreone.android.feature.nodes.deps.SyncProgress
import com.meshcoreone.android.feature.nodes.model.NodeSegment
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import com.meshcoreone.android.feature.nodes.support.Fixtures
import com.meshcoreone.android.feature.nodes.support.Fixtures.contact
import com.meshcoreone.android.feature.nodes.support.Harness
import com.meshcoreone.android.feature.nodes.support.FakeAdvertisementService
import com.meshcoreone.android.feature.nodes.support.scenario
import com.meshcoreone.android.feature.nodes.support.settle
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Test

class ContactsNativeTest {
    @Test
    fun `same-graph favorites on different rows remain concurrent when the older finishes first`() =
        verifyConcurrentFavorites(olderFinishesFirst = true, olderFails = false)

    @Test
    fun `late same-graph favorite success releases only its own row guard`() =
        verifyConcurrentFavorites(olderFinishesFirst = false, olderFails = false)

    @Test
    fun `late same-graph favorite failure releases only its own row guard`() =
        verifyConcurrentFavorites(olderFinishesFirst = false, olderFails = true)

    private fun verifyConcurrentFavorites(olderFinishesFirst: Boolean, olderFails: Boolean) = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val first = contact(radioId = radio, name = "First")
        val second = contact(radioId = radio, name = "Second")
        harness.store.contacts += listOf(first, second)
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.loadContacts(radio)
        val firstRelease = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val sent = mutableListOf<UUID>()
        val persist = harness.contactService.favoriteBehavior
        harness.contactService.favoriteBehavior = { key, favorite ->
            sent += key.id
            if (key.id == first.id) {
                firstRelease.await()
                if (olderFails) error("first favorite failed")
            } else {
                secondRelease.await()
            }
            persist(key, favorite)
        }
        fun enabled(row: ContactDTO) =
            ContactRowActions.actions(row, RowActionEdge.LEADING, DeviceConnectionState.READY, null, holder.state.value).single().enabled
        val older = scope.launch { holder.toggleFavorite(first) }
        settle()
        assertEquals(setOf(first.id), holder.state.value.togglingFavoriteIds)
        assertFalse(enabled(first))
        assertTrue(enabled(second), "A different row remains visibly actionable")
        val newer = scope.launch { holder.toggleFavorite(second) }
        settle()
        assertEquals(listOf(first.id, second.id), sent, "Both visibly enabled commands must actually start")
        assertEquals(setOf(first.id, second.id), holder.state.value.togglingFavoriteIds)
        assertFalse(enabled(first))
        assertFalse(enabled(second))
        holder.toggleFavorite(first)
        holder.toggleFavorite(second)
        assertEquals(listOf(first.id, second.id), sent, "Each pending row rejects only its own duplicate")
        val completed = if (olderFinishesFirst) first else second
        val remaining = if (olderFinishesFirst) second else first
        if (olderFinishesFirst) {
            firstRelease.complete(Unit)
            older.join()
        } else {
            secondRelease.complete(Unit)
            newer.join()
        }
        assertEquals(setOf(remaining.id), holder.state.value.togglingFavoriteIds)
        assertTrue(enabled(completed))
        assertFalse(enabled(remaining), "Another row's cleanup must not re-enable this held action")
        holder.toggleFavorite(remaining)
        assertEquals(listOf(first.id, second.id), sent)
        if (olderFinishesFirst) {
            secondRelease.complete(Unit)
            newer.join()
        } else {
            firstRelease.complete(Unit)
            older.join()
        }
        assertFalse(older.isCancelled)
        assertFalse(newer.isCancelled)
        assertTrue(holder.state.value.togglingFavoriteIds.isEmpty())
        assertTrue(enabled(first))
        assertTrue(enabled(second))
        assertEquals(!olderFails, holder.state.value.contacts.first { it.id == first.id }.isFavorite)
        assertTrue(holder.state.value.contacts.first { it.id == second.id }.isFavorite)
        if (olderFails) assertEquals(NodesMessage.Text("failed: first favorite failed"), holder.state.value.errorMessage)
        else assertNull(holder.state.value.errorMessage)
    }

    @Test
    fun `favorite reload acquisition cannot supersede a replacement graphs held load`() =
        verifyMutationReloadAcquisition("favorite")

    @Test
    fun `block reload acquisition cannot supersede a replacement graphs held load`() =
        verifyMutationReloadAcquisition("block")

    private fun verifyMutationReloadAcquisition(action: String) = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val row = contact(radioId = radio, name = "Admitted")
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.upsert(row)
        val replacement = Harness(clock).connect(radio)
        val freshRow = contact(radioId = radio, name = "Fresh B")
        replacement.store.contacts += freshRow
        val freshEntered = CompletableDeferred<Unit>()
        val freshRelease = CompletableDeferred<Unit>()
        replacement.store.fetchContactsGate = {
            freshEntered.complete(Unit)
            freshRelease.await()
        }
        var oldFetches = 0
        harness.store.fetchContactsGate = { oldFetches++ }
        var fresh: kotlinx.coroutines.Job? = null
        var swaps = 0
        harness.session.onOfflineStoreRead = {
            harness.session.onOfflineStoreRead = {}
            swaps++
            harness.session.contacts = replacement.contactService
            harness.session.servicesStore = replacement.store
            harness.session.offlineStore = replacement.store
            // Start B synchronously inside A's provider read, before A can mutate loadGeneration.
            fresh = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                if (action == "favorite") holder.toggleFavorite(row) else holder.toggleBlocked(row)
            }
            assertTrue(freshEntered.isCompleted, "B must already own a held reload when A's acquisition returns")
            assertTrue(holder.state.value.isLoading)
        }
        val old = scope.launch {
            if (action == "favorite") holder.toggleFavorite(row) else holder.toggleBlocked(row)
        }
        old.join()
        assertEquals(1, swaps)
        assertTrue(old.isCancelled)
        assertEquals(0, oldFetches, "Retired A must reject the acquired store before starting a load")
        assertEquals(1, harness.contactService.calls.size)
        assertEquals(1, replacement.contactService.calls.size)
        assertEquals(listOf(row), holder.state.value.contacts)
        assertTrue(holder.state.value.isLoading, "A cleanup cannot replace B's loading lifetime")
        assertNull(holder.state.value.errorMessage)
        if (action == "favorite") assertEquals(setOf(row.id), holder.state.value.togglingFavoriteIds)
        freshRelease.complete(Unit)
        checkNotNull(fresh).join()
        assertFalse(checkNotNull(fresh).isCancelled)
        assertEquals(listOf(freshRow, row), holder.state.value.contacts, "B's load generation must remain publishable")
        assertFalse(holder.state.value.isLoading, "B must finish its own loading lifetime")
        assertTrue(holder.state.value.togglingFavoriteIds.isEmpty())
        assertNull(holder.state.value.errorMessage)
        assertNull(holder.state.value.errorConnection)
    }

    @Test
    fun `retired favorite cleanup cannot clear a different B favorite guard`() = verifyHeldMutation("favorite", fails = false)

    @Test
    fun `retired favorite failure cannot fail or release B favorite`() = verifyHeldMutation("favorite", fails = true)

    @Test
    fun `retired delete completion cannot hide B row or clear B delete guard`() = verifyHeldMutation("delete", fails = false)

    @Test
    fun `retired delete failure cannot fail or release B delete`() = verifyHeldMutation("delete", fails = true)

    private fun verifyHeldMutation(action: String, fails: Boolean) = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val first = contact(radioId = radio, name = "First")
        val second = contact(radioId = radio, name = "Second")
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.upsert(first)
        holder.upsert(second)
        val oldRelease = CompletableDeferred<Unit>()
        val oldEntered = CompletableDeferred<Unit>()
        suspend fun oldWork() {
            oldEntered.complete(Unit)
            oldRelease.await()
            if (fails) error("retired mutation failed")
        }
        harness.contactService.favoriteBehavior = { _, _ -> oldWork() }
        harness.contactService.removeContact = { _, _ -> oldWork() }
        val old = scope.launch {
            if (action == "favorite") holder.toggleFavorite(first) else holder.deleteContact(first)
        }
        oldEntered.await()
        val replacement = Harness(clock).connect(radio)
        val newRelease = CompletableDeferred<Unit>()
        val newEntered = CompletableDeferred<Unit>()
        suspend fun newWork() { newEntered.complete(Unit); newRelease.await() }
        replacement.contactService.favoriteBehavior = { _, _ -> newWork() }
        replacement.contactService.removeContact = { _, _ -> newWork() }
        harness.session.contacts = replacement.contactService
        harness.session.adverts = replacement.adverts
        harness.session.servicesStore = replacement.store
        harness.session.offlineStore = replacement.store
        // No screen hook: fresh entry itself must retire A's guard.
        val target = if (action == "favorite") second else first
        val fresh = scope.launch {
            if (action == "favorite") holder.toggleFavorite(target) else holder.deleteContact(target)
        }
        newEntered.await()
        if (action == "favorite") assertEquals(second.id, holder.state.value.togglingFavoriteId)
        else assertTrue(first.id in holder.state.value.deletingIds)
        oldRelease.complete(Unit)
        old.join()
        assertTrue(old.isCancelled)
        assertNull(holder.state.value.errorMessage)
        assertEquals(listOf(first, second), holder.state.value.contacts)
        if (action == "favorite") {
            assertEquals(second.id, holder.state.value.togglingFavoriteId, "A cleanup must not remove B's different-contact guard")
            holder.toggleFavorite(second)
        } else {
            assertTrue(first.id in holder.state.value.deletingIds, "A cleanup must not remove B's same-contact guard")
            holder.deleteContact(first)
        }
        assertEquals(1, replacement.contactService.calls.size, "B remains protected against a duplicate action")
        newRelease.complete(Unit)
        fresh.join()
        assertFalse(fresh.isCancelled)
        assertNull(holder.state.value.errorMessage)
        assertNull(holder.state.value.togglingFavoriteId)
        assertTrue(holder.state.value.deletingIds.isEmpty())
        if (action == "delete") assertEquals(listOf(second), holder.state.value.contacts)
    }

    @Test
    fun `retired favorite fetch cannot publish stale rows into same-radio B`() = verifyMutationFetch("favorite", fails = false)

    @Test
    fun `retired favorite fetch failure cannot publish into same-radio B`() = verifyMutationFetch("favorite", fails = true)

    @Test
    fun `retired block fetch cannot publish stale rows into same-radio B`() = verifyMutationFetch("block", fails = false)

    @Test
    fun `retired block fetch failure cannot publish into same-radio B`() = verifyMutationFetch("block", fails = true)

    private fun verifyMutationFetch(action: String, fails: Boolean) = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val row = contact(radioId = radio, name = "Admitted")
        val stale = contact(radioId = radio, name = "Stale A")
        harness.store.contacts += stale
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.upsert(row)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.store.fetchContactsGate = {
            entered.complete(Unit)
            release.await()
            if (fails) error("retired fetch failed")
        }
        val old = scope.launch {
            if (action == "favorite") holder.toggleFavorite(row) else holder.toggleBlocked(row)
        }
        entered.await()
        assertTrue(holder.state.value.isLoading)
        val replacement = Harness(clock).connect(radio)
        val freshRow = contact(radioId = radio, name = "Fresh B")
        replacement.store.contacts += freshRow
        val freshEntered = CompletableDeferred<Unit>()
        val freshRelease = CompletableDeferred<Unit>()
        replacement.store.fetchContactsGate = {
            freshEntered.complete(Unit)
            freshRelease.await()
        }
        harness.session.contacts = replacement.contactService
        harness.session.servicesStore = replacement.store
        harness.session.offlineStore = replacement.store
        holder.onConnectionChanged()
        assertFalse(holder.state.value.isLoading)
        assertNull(holder.state.value.togglingFavoriteId)
        val fresh = scope.launch {
            if (action == "favorite") holder.toggleFavorite(row) else holder.toggleBlocked(row)
        }
        freshEntered.await()
        release.complete(Unit)
        old.join()
        assertTrue(old.isCancelled)
        assertTrue(holder.state.value.isLoading, "A cleanup cannot finish B's held reload")
        if (action == "favorite") assertEquals(row.id, holder.state.value.togglingFavoriteId)
        assertEquals(listOf(row), holder.state.value.contacts)
        assertNull(holder.state.value.errorMessage)
        assertNull(holder.state.value.errorConnection)
        freshRelease.complete(Unit)
        fresh.join()
        assertFalse(fresh.isCancelled)
        assertEquals(listOf(freshRow, row), holder.state.value.contacts)
        assertFalse(holder.state.value.isLoading)
        assertNull(holder.state.value.togglingFavoriteId)
        assertNull(holder.state.value.errorMessage)
    }

    @Test
    fun `delete rejects a graph replaced during service acquisition then fresh B succeeds`() = verifyMutationAcquisition("delete")

    @Test
    fun `block rejects a graph replaced during service acquisition then fresh B succeeds`() = verifyMutationAcquisition("block")

    @Test
    fun `favorite rejects a graph replaced during service acquisition then fresh B succeeds`() = verifyMutationAcquisition("favorite")

    private fun verifyMutationAcquisition(action: String) = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val row = contact(radioId = radio)
        val owner = NodesConnectionSnapshot(radio, harness.session.connectionIdentity())
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.upsert(row)
        val replacement = Harness(clock).connect(radio)
        replacement.store.contacts += row
        var swaps = 0
        harness.session.onContactServiceRead = {
            harness.session.onContactServiceRead = {}
            swaps++
            harness.session.contacts = replacement.contactService
            harness.session.adverts = replacement.adverts
            harness.session.servicesStore = replacement.store
            harness.session.offlineStore = replacement.store
        }
        suspend fun perform(binding: NodesConnectionSnapshot) = when (action) {
            "delete" -> holder.deleteContact(row, binding)
            "block" -> holder.toggleBlocked(row, binding)
            else -> holder.toggleFavorite(row, binding)
        }
        var cancelled = false
        try { perform(owner) } catch (_: CancellationException) { cancelled = true }
        assertEquals(1, swaps)
        assertTrue(cancelled)
        assertTrue(harness.contactService.calls.isEmpty())
        assertTrue(replacement.contactService.calls.isEmpty())
        assertEquals(listOf(row), holder.state.value.contacts)
        assertTrue(holder.state.value.deletingIds.isEmpty())
        assertNull(holder.state.value.togglingFavoriteId)
        assertNull(holder.state.value.errorMessage)
        perform(NodesConnectionSnapshot(radio, harness.session.connectionIdentity()))
        assertEquals(1, replacement.contactService.calls.size)
        assertTrue(harness.contactService.calls.isEmpty())
        assertNull(holder.state.value.errorMessage)
    }

    @Test
    fun `sync flags the advert pipeline, streams progress and reloads`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val release = CompletableDeferred<Unit>()
        harness.contactService.sync = {
            harness.contactService.progress.emit(SyncProgress(3, 10))
            release.await()
            harness.store.contacts += contact(radioId = radio, name = "Synced")
        }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        val job = scope.launch { holder.syncContacts(radio) }
        settle()
        assertTrue(holder.state.value.isSyncing)
        assertEquals(SyncProgress(3, 10), holder.state.value.syncProgress)
        holder.syncContacts(radio) // re-trigger while syncing is a no-op
        assertEquals(1, harness.contactService.calls.count { it == "sync" })
        release.complete(Unit)
        job.join()
        val state = holder.state.value
        assertFalse(state.isSyncing)
        assertNull(state.syncProgress)
        assertEquals(listOf("Synced"), state.contacts.map { it.name })
        assertEquals(listOf(true, false), harness.adverts.syncingFlags)
        assertEquals(0, harness.contactService.progress.subscriberCount)
    }

    @Test
    fun `cancelled sync is not reported and still resets the advert flag`() = scenario {
        val harness = Harness(clock).connect()
        harness.contactService.sync = { CompletableDeferred<Unit>().await() }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        val job = scope.launch { holder.syncContacts(checkNotNull(harness.session.radioId)) }
        settle()
        job.cancel()
        job.join()
        assertNull(holder.state.value.errorMessage)
        assertFalse(holder.state.value.isSyncing)
        assertEquals(listOf(true, false), harness.adverts.syncingFlags)
    }

    @Test
    fun `held sync resets only its original sessions advert pipeline`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val release = CompletableDeferred<Unit>()
        harness.contactService.sync = { release.await() }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        val syncing = scope.launch { holder.syncContacts(radio) }
        settle()
        val replacement = FakeAdvertisementService()
        harness.session.adverts = replacement
        release.complete(Unit)
        syncing.join()
        assertEquals(listOf(true, false), harness.adverts.syncingFlags)
        assertTrue(replacement.syncingFlags.isEmpty())
        assertEquals(0, harness.contactService.progress.subscriberCount)
        assertFalse(holder.state.value.isSyncing)
    }

    @Test
    fun `same-radio swap during contact acquisition rejects the mixed capture before side effects`() =
        verifySyncAcquisitionSwap("contact")

    @Test
    fun `same-radio swap during advert acquisition rejects the mixed capture before side effects`() =
        verifySyncAcquisitionSwap("advert")

    @Test
    fun `same-radio swap while setting advert flag cleans only the captured pipeline`() =
        verifySyncAcquisitionSwap("flag")

    @Test
    fun `sync entry rejects an initiating graph retired before holder acquisition`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val owner = harness.session.connectionIdentity()
        val replacement = Harness(clock).connect(radio)
        harness.session.contacts = replacement.contactService
        harness.session.adverts = replacement.adverts
        harness.session.servicesStore = replacement.store
        harness.session.offlineStore = replacement.store
        var acquired = false
        harness.session.onContactServiceRead = { acquired = true }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        var cancelled = false
        try {
            holder.syncContacts(radio, owner)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertFalse(acquired, "Retired owner rejection must precede service acquisition")
        assertTrue(harness.contactService.calls.isEmpty())
        assertTrue(replacement.contactService.calls.isEmpty())
        assertTrue(harness.adverts.syncingFlags.isEmpty())
        assertTrue(replacement.adverts.syncingFlags.isEmpty())
        assertNull(holder.state.value.errorMessage)
        harness.session.onContactServiceRead = {}
        holder.syncContacts(radio, harness.session.connectionIdentity())
        assertEquals(listOf("sync"), replacement.contactService.calls)
        assertEquals(listOf(true, false), replacement.adverts.syncingFlags)
    }

    @Test
    fun `not-ready publication after its final check retains the original error owner`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val owner = harness.session.connectionIdentity()
        harness.session.state = DeviceConnectionState.DISCONNECTED
        val replacement = Harness(clock).connect(radio)
        var checks = 0
        harness.session.onRadioIdRead = {
            if (++checks == 2) {
                harness.session.onRadioIdRead = {}
                harness.session.contacts = replacement.contactService
                harness.session.adverts = replacement.adverts
                harness.session.servicesStore = replacement.store
                harness.session.offlineStore = replacement.store
                harness.session.state = DeviceConnectionState.READY
            }
        }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.syncContacts(radio, owner)
        assertEquals(2, checks)
        assertTrue(holder.state.value.errorConnection?.identity === owner, "The late failure must not acquire B's identity")
        assertTrue(harness.contactService.calls.isEmpty())
        assertTrue(replacement.contactService.calls.isEmpty())
        holder.onConnectionChanged()
        assertNull(holder.state.value.errorMessage, "A readiness error retires even without an active sync")
        assertNull(holder.state.value.errorConnection)
        holder.syncContacts(radio, harness.session.connectionIdentity())
        assertEquals(listOf("sync"), replacement.contactService.calls)
        assertEquals(listOf(true, false), replacement.adverts.syncingFlags)
        assertNull(holder.state.value.errorMessage)
        assertTrue(harness.adverts.syncingFlags.isEmpty())
    }

    private fun verifySyncAcquisitionSwap(boundary: String) = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val replacement = Harness(clock).connect(radio)
        val holder = ContactsStateHolder(harness.dependencies, scope)
        val admitted = contact(radioId = radio, name = "Admitted")
        holder.upsert(admitted)
        var swaps = 0
        val replace: () -> Unit = {
            swaps++
            harness.session.contacts = replacement.contactService
            harness.session.adverts = replacement.adverts
            harness.session.device = replacement.session.device
            harness.session.servicesStore = replacement.store
            harness.session.offlineStore = replacement.store
        }
        when (boundary) {
            "contact" -> harness.session.onContactServiceRead = {
                harness.session.onContactServiceRead = {}
                replace()
            }
            "advert" -> harness.session.onAdvertisementServiceRead = {
                harness.session.onAdvertisementServiceRead = {}
                replace()
            }
            "flag" -> harness.adverts.syncingBehavior = { if (it) replace() }
        }
        var cancelled = false
        try {
            holder.syncContacts(radio)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertEquals(1, swaps, "The graph must actually change during acquisition or flagging")
        assertTrue(cancelled, "A mixed or retired capture must cancel, not masquerade as B")
        assertTrue(harness.contactService.calls.isEmpty(), "A must not subscribe or send after the swap")
        assertTrue(replacement.contactService.calls.isEmpty(), "Acquisition cannot send through B either")
        assertEquals(0, harness.contactService.progress.subscriberCount)
        assertEquals(0, replacement.contactService.progress.subscriberCount)
        assertEquals(if (boundary == "flag") listOf(true, false) else emptyList(), harness.adverts.syncingFlags)
        assertTrue(replacement.adverts.syncingFlags.isEmpty(), "A cleanup must not flag B")
        assertFalse(holder.state.value.isSyncing)
        assertNull(holder.state.value.errorMessage)
        assertEquals(listOf(admitted), holder.state.value.contacts)

        holder.syncContacts(radio)
        assertEquals(listOf("sync"), replacement.contactService.calls)
        assertEquals(listOf(true, false), replacement.adverts.syncingFlags)
        assertEquals(if (boundary == "flag") listOf(true, false) else emptyList(), harness.adverts.syncingFlags)
        assertEquals(0, replacement.contactService.progress.subscriberCount)
        assertFalse(holder.state.value.isSyncing)
        assertNull(holder.state.value.errorMessage)
        assertEquals(listOf(admitted), holder.state.value.contacts)
    }

    @Test
    fun `held same-radio sync success cannot reload through the replacement graph`() = verifySyncReplacement("success")

    @Test
    fun `held same-radio sync progress cannot overwrite replacement progress`() = verifySyncReplacement("progress")

    @Test
    fun `held same-radio sync ordinary failure cannot fail or stop the replacement sync`() = verifySyncReplacement("failure")

    @Test
    fun `same-radio replacement during held sync fetch retires loading before the screen effect`() =
        verifySyncFetchReplacement(fails = false)

    @Test
    fun `same-radio replacement during failing sync fetch retires loading before the screen effect`() =
        verifySyncFetchReplacement(fails = true)

    private fun verifySyncFetchReplacement(fails: Boolean) = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.store.fetchContactsGate = {
            entered.complete(Unit)
            release.await()
            if (fails) error("old store failed")
        }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        val syncing = scope.launch { holder.syncContacts(radio) }
        entered.await()
        assertTrue(holder.state.value.isLoading)
        assertTrue(holder.state.value.isSyncing)
        val replacement = Harness(clock).connect(radio)
        val fresh = contact(radioId = radio, name = "Fresh")
        replacement.store.contacts += fresh
        harness.session.contacts = replacement.contactService
        harness.session.adverts = replacement.adverts
        harness.session.servicesStore = replacement.store
        harness.session.offlineStore = replacement.store
        release.complete(Unit)
        syncing.join()
        assertTrue(syncing.isCancelled)
        assertFalse(holder.state.value.isLoading, "A cleanup must retire loading before a delayed screen effect")
        assertFalse(holder.state.value.isSyncing)
        assertNull(holder.state.value.errorMessage)
        assertTrue(holder.state.value.contacts.isEmpty(), "A cannot publish a stale fetch")
        assertEquals(listOf(true, false), harness.adverts.syncingFlags)
        assertTrue(replacement.adverts.syncingFlags.isEmpty())
        holder.onConnectionChanged()
        assertFalse(holder.state.value.isLoading)
        holder.syncContacts(radio)
        assertEquals(listOf(fresh), holder.state.value.contacts)
        assertFalse(holder.state.value.isLoading)
        assertFalse(holder.state.value.isSyncing)
        assertEquals(listOf("sync"), replacement.contactService.calls)
        assertEquals(listOf(true, false), replacement.adverts.syncingFlags)
    }

    private fun verifySyncReplacement(outcome: String) = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val admitted = contact(radioId = radio, name = "Admitted")
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.upsert(admitted)
        val oldEntered = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        harness.contactService.sync = {
            harness.contactService.progress.emit(SyncProgress(1, 10))
            oldEntered.complete(Unit)
            oldRelease.await()
            when (outcome) {
                "failure" -> error("old graph failed")
                "progress" -> harness.contactService.progress.emit(SyncProgress(99, 100))
            }
        }
        val oldSync = scope.launch { holder.syncContacts(radio) }
        oldEntered.await()
        settle()
        assertEquals(SyncProgress(1, 10), holder.state.value.syncProgress)
        val replacement = Harness(clock).connect(radio)
        val newEntered = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        var newReads = 0
        replacement.store.fetchContactsGate = { newReads++ }
        replacement.contactService.sync = {
            replacement.contactService.progress.emit(SyncProgress(2, 20))
            newEntered.complete(Unit)
            newRelease.await()
        }
        harness.session.contacts = replacement.contactService
        harness.session.adverts = replacement.adverts
        harness.session.device = replacement.session.device
        harness.session.servicesStore = replacement.store
        harness.session.offlineStore = replacement.store
        holder.onConnectionChanged()
        assertEquals(listOf(admitted), holder.state.value.contacts)
        assertFalse(holder.state.value.isSyncing)
        assertNull(holder.state.value.syncProgress)
        assertEquals(0, harness.contactService.progress.subscriberCount)

        val newSync = scope.launch { holder.syncContacts(radio) }
        newEntered.await()
        settle()
        assertEquals(SyncProgress(2, 20), holder.state.value.syncProgress)
        harness.contactService.progress.emit(SyncProgress(88, 100))
        oldRelease.complete(Unit)
        oldSync.join()
        settle()
        assertTrue(holder.state.value.isSyncing, "Only B may finish B's active sync")
        assertNull(holder.state.value.errorMessage)
        assertEquals(SyncProgress(2, 20), holder.state.value.syncProgress)
        assertEquals(listOf(admitted), holder.state.value.contacts)
        assertEquals(0, newReads, "Old success must not fetch through B")
        assertEquals(listOf("sync"), replacement.contactService.calls)
        newRelease.complete(Unit)
        newSync.join()
        assertFalse(holder.state.value.isSyncing)
        assertNull(holder.state.value.syncProgress)
        assertNull(holder.state.value.errorMessage)
        assertEquals(listOf(admitted), holder.state.value.contacts, "Radio-owned admission survives a graph replacement and stale fetch")
        assertEquals(1, newReads)
        assertEquals(listOf(true, false), harness.adverts.syncingFlags)
        assertEquals(listOf(true, false), replacement.adverts.syncingFlags)
        assertEquals(0, replacement.contactService.progress.subscriberCount)
    }

    @Test
    fun `sync failure surfaces the user-facing message`() = scenario {
        val harness = Harness(clock).connect()
        harness.contactService.sync = { error("radio gone") }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.syncContacts(checkNotNull(harness.session.radioId))
        assertEquals(NodesMessage.Text("failed: radio gone"), holder.state.value.errorMessage)
        assertFalse(holder.state.value.isSyncing)
    }

    @Test
    fun `confirmed delete keeps a spinner until the ack then masks the row`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val alice = contact(radioId = radio, name = "Alice")
        harness.store.contacts += alice
        val ack = CompletableDeferred<Unit>()
        harness.contactService.removeContact = { _, _ -> ack.await() }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.loadContacts(radio)
        val job = scope.launch { holder.deleteContact(alice) }
        settle()
        assertTrue(alice.id in holder.state.value.deletingIds)
        assertEquals(listOf(alice.id), holder.state.value.contacts.map { it.id })
        holder.deleteContact(alice) // pending guard
        assertEquals(1, harness.contactService.calls.count { it == "remove" })
        ack.complete(Unit)
        job.join()
        val state = holder.state.value
        assertTrue(state.contacts.isEmpty())
        assertTrue(alice.id in state.pendingRemovalIds)
        assertTrue(state.deletingIds.isEmpty())
        // A reload that no longer sees the row self-heals the mask.
        harness.store.contacts.clear()
        holder.loadContacts(radio)
        assertTrue(holder.state.value.pendingRemovalIds.isEmpty())
    }

    @Test
    fun `contact not found on the radio clears the orphaned local row`() = scenario {
        val harness = Harness(clock).connect()
        val alice = contact(radioId = checkNotNull(harness.session.radioId), name = "Alice")
        harness.contactService.removeContact = { _, _ -> throw NodesContactFailure.ContactNotFound() }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.seed { it.copy(contacts = listOf(alice)) }
        holder.deleteContact(alice)
        assertEquals(listOf("remove", "removeLocal"), harness.contactService.calls)
        assertTrue(holder.state.value.contacts.isEmpty())
        assertNull(holder.state.value.errorMessage)
    }

    @Test
    fun `a silent radio times out after seven seconds and keeps the row`() = scenario {
        val harness = Harness(clock).connect()
        val alice = contact(radioId = checkNotNull(harness.session.radioId), name = "Alice")
        harness.contactService.removeContact = { _, _ -> CompletableDeferred<Unit>().await() }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.seed { it.copy(contacts = listOf(alice)) }
        val job = scope.launch { holder.deleteContact(alice) }
        settle()
        clock.advanceBy(6.seconds)
        assertTrue(alice.id in holder.state.value.deletingIds)
        clock.advanceBy(1.seconds)
        job.join()
        val state = holder.state.value
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_viewmodel_removetimedout), state.errorMessage)
        assertEquals(listOf(alice.id), state.contacts.map { it.id })
        assertTrue(state.deletingIds.isEmpty() && state.pendingRemovalIds.isEmpty())
    }

    @Test
    fun `toggle favorite flips on the radio and reloads`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val alice = contact(radioId = radio, name = "Alice")
        harness.store.contacts += alice
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.loadContacts(radio)
        holder.toggleFavorite(alice)
        assertEquals(listOf("favorite:true"), harness.contactService.calls)
        assertTrue(holder.state.value.contacts.single().isFavorite)
        assertNull(holder.state.value.togglingFavoriteId)
    }

    @Test
    fun `empty nickname is passed as unchanged and block toggles`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val alice = contact(radioId = radio, name = "Alice", nickname = "Al")
        harness.store.contacts += alice
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.loadContacts(radio)
        holder.updateNickname(alice, "")
        holder.toggleBlocked(alice)
        assertEquals(listOf("prefs:nickname=null,isBlocked=null", "prefs:nickname=null,isBlocked=true"), harness.contactService.calls)
        assertTrue(holder.state.value.contacts.single().isBlocked)
    }

    @Test
    fun `a slower load cannot overwrite a newer one`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val gate = CompletableDeferred<Unit>()
        harness.store.contacts += contact(radioId = radio, name = "Old")
        harness.store.fetchContactsGate = { gate.await() }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        val slow = scope.launch { holder.loadContacts(radio) }
        settle()
        harness.store.fetchContactsGate = null
        harness.store.contacts.clear()
        harness.store.contacts += contact(radioId = radio, name = "New")
        holder.loadContacts(radio)
        gate.complete(Unit)
        slow.join()
        assertEquals(listOf("New"), holder.state.value.contacts.map { it.name })
        assertTrue(holder.state.value.hasLoadedOnce)
    }

    @Test
    fun `a failed fetch still finishes loading and the hop fallback is best effort`() = scenario {
        val harness = Harness(clock).connect()
        harness.store.fetchContactsError = IllegalStateException("disk")
        harness.store.fetchDiscoveredError = IllegalStateException("volatile")
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.loadContacts(checkNotNull(harness.session.radioId))
        val state = holder.state.value
        assertEquals(NodesMessage.Text("failed: disk"), state.errorMessage)
        assertTrue(state.hasLoadedOnce)
        assertFalse(state.isLoading)
        assertTrue(state.inboundHopByKey.isEmpty())
    }

    @Test
    fun `inbound hop counts come from discovered nodes`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val key = Fixtures.randomKey()
        harness.store.discovered += Fixtures.node(radioId = radio, publicKey = key, inboundHopCount = 4)
        harness.store.discovered += Fixtures.node(radioId = radio, publicKey = Fixtures.randomKey())
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.loadContacts(radio)
        assertEquals(mapOf(key to 4L), holder.state.value.inboundHopByKey)
    }

    @Test
    fun `search matches a public key hex prefix case-insensitively`() = scenario {
        val holder = ContactsStateHolder(Harness(clock).dependencies, scope)
        val row = contact(name = "Zed", publicKey = Fixtures.key(0x0A, 0xBC))
        holder.seed { it.copy(contacts = listOf(row, contact(name = "Other", publicKey = Fixtures.key(0x11)))) }
        assertEquals(listOf("Zed"), holder.filteredContacts("0abc", NodeSegment.FAVORITES, NodeSortOrder.NAME, null).map { it.name })
    }

    @Test
    fun `list actions fall back to last heard without a location and gate refresh on readiness`() = scenario {
        val harness = Harness(clock).connect()
        val holder = ContactsStateHolder(harness.dependencies, scope)
        val actions = ContactListActions(holder, harness.dependencies)
        holder.seed {
            it.copy(contacts = listOf(contact(name = "A", lastModified = 1u), contact(name = "B", lastModified = 2u)))
        }
        assertEquals(listOf("B", "A"), actions.filteredContacts("", NodeSegment.CONTACTS, NodeSortOrder.DISTANCE, null).map { it.name })
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_list_searchpromptwithcount, 2), actions.searchPrompt)
        harness.session.state = DeviceConnectionState.CONNECTING
        assertEquals(ContactListActions.RefreshOutcome.ShowOfflineAlert, actions.refreshNodes())
        harness.session.state = DeviceConnectionState.DISCONNECTED
        actions.announceOfflineStateIfNeeded()
        assertEquals(listOf(NodesMessage.res(R.string.l10n_app_contacts_contacts_list_offlineannouncement)), harness.announcer.messages)
        assertEquals(NodeSegment.FAVORITES, ContactListActions.segmentAfterLoad(NodeSegment.CONTACTS, loaded = true, hasFavorites = true))
        assertEquals(NodeSegment.ROOMS, ContactListActions.segmentAfterLoad(NodeSegment.ROOMS, loaded = true, hasFavorites = true))
    }

    @Test
    fun `row actions follow edge, type, connection and pending state`() = scenario {
        val chat = contact(name = "Chat")
        val repeater = contact(name = "Rep", type = ContactType.REPEATER, isFavorite = true)
        val ready = DeviceConnectionState.READY
        val idle = ContactsState()
        val all = ContactRowActions.actions(chat, RowActionEdge.ALL, ready, null, idle).map { it.kind }
        assertEquals(listOf(RowActionKind.SEND_MESSAGE, RowActionKind.DELETE, RowActionKind.BLOCK, RowActionKind.FAVORITE), all)
        assertEquals(listOf(RowActionKind.UNFAVORITE), ContactRowActions.actions(repeater, RowActionEdge.LEADING, ready, null, idle).map { it.kind })
        assertEquals(listOf(RowActionKind.DELETE), ContactRowActions.actions(repeater, RowActionEdge.TRAILING, ready, null, idle).map { it.kind })
        val pending = ContactRowActions.actions(chat, RowActionEdge.TRAILING, ready, null, ContactsState(deletingIds = setOf(chat.id)))
        assertFalse(pending.first { it.kind == RowActionKind.DELETE }.enabled)
        assertTrue(ContactRowActions.actions(chat, RowActionEdge.ALL, DeviceConnectionState.CONNECTED, null, idle).none { it.enabled })
        val self = Fixtures.repeated(0x42)
        val vContact = contact(name = "V", publicKey = checkNotNull(VContactIdentity.publicKey(self)))
        assertTrue(ContactRowActions.actions(vContact, RowActionEdge.TRAILING, ready, self, idle).none { it.kind == RowActionKind.DELETE })
        assertFalse(ContactRowActions.allowsFullSwipe(RowActionEdge.TRAILING))
        assertTrue(ContactRowActions.allowsFullSwipe(RowActionEdge.LEADING))
    }

    @Test
    fun `row labels show direct, hop counts and flood`() {
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_route_direct), ContactRowPresentation.routeLabel(contact(outPathLength = 0u), null))
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_route_hops, 2L), ContactRowPresentation.routeLabel(contact(outPathLength = 2u), null))
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_route_hops, 3L), ContactRowPresentation.routeLabel(contact(outPathLength = 0xFFu), 3))
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_route_flood), ContactRowPresentation.routeLabel(contact(outPathLength = 0xFFu), null))
        assertEquals("0AB1", ContactRowPresentation.idPrefixHex(contact(publicKey = Fixtures.key(0x0A, 0xB1)), 2))
    }
}

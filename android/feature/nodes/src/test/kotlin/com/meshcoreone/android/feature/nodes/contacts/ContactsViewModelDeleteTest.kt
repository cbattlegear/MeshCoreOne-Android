// PortedFrom: MC1Tests/Views/Contacts/ContactsViewModelDeleteTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.contacts

import com.meshcoreone.android.feature.nodes.deps.CommandTimeoutException
import com.meshcoreone.android.feature.nodes.deps.withCommandTimeout
import com.meshcoreone.android.feature.nodes.model.NodeSegment
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import com.meshcoreone.android.feature.nodes.support.Fixtures
import com.meshcoreone.android.feature.nodes.support.Fixtures.contact
import com.meshcoreone.android.feature.nodes.support.Harness
import com.meshcoreone.android.feature.nodes.support.OriginalCase
import com.meshcoreone.android.feature.nodes.support.scenario
import com.meshcoreone.android.feature.nodes.support.settle
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import org.junit.Test

/**
 * Delete invariants: a confirmed-but-unreloaded delete is masked out of the filtered list, the pending
 * guard reflects both sets, a delete without a radio surfaces an error, and the timeout bounds the command.
 */
class ContactsViewModelDeleteTest {
    @Test @OriginalCase("ContactsViewModelDeleteTests::filtered contacts excludes masked row()")
    fun `filtered contacts excludes masked row`() = scenario {
        val holder = ContactsStateHolder(Harness(clock).dependencies, scope)
        val device = Fixtures.radio()
        val doomed = contact(radioId = device, name = "Doomed")
        val kept = contact(radioId = device, name = "Kept")
        holder.seed { it.copy(contacts = listOf(doomed, kept), pendingRemovalIds = setOf(doomed.id)) }
        val result = holder.filteredContacts("", NodeSegment.CONTACTS, NodeSortOrder.NAME, null)
        assertEquals(listOf(kept.id), result.map { it.id })
    }

    @Test @OriginalCase("ContactsViewModelDeleteTests::filtered contacts mask applies to search()")
    fun `filtered contacts mask applies to search`() = scenario {
        val holder = ContactsStateHolder(Harness(clock).dependencies, scope)
        val doomed = contact(radioId = Fixtures.radio(), name = "Alice")
        holder.seed { it.copy(contacts = listOf(doomed), pendingRemovalIds = setOf(doomed.id)) }
        assertTrue(holder.filteredContacts("Ali", NodeSegment.CONTACTS, NodeSortOrder.NAME, null).isEmpty())
    }

    @Test @OriginalCase("ContactsViewModelDeleteTests::is delete pending reflects both sets()")
    fun `is delete pending reflects both sets`() = scenario {
        val holder = ContactsStateHolder(Harness(clock).dependencies, scope)
        val id = UUID.randomUUID()
        assertFalse(holder.isDeletePending(id))
        holder.seed { it.copy(pendingRemovalIds = setOf(id)) }
        assertTrue(holder.isDeletePending(id))
        holder.seed { it.copy(pendingRemovalIds = emptySet(), deletingIds = setOf(id)) }
        assertTrue(holder.isDeletePending(id))
    }

    @Test @OriginalCase("ContactsViewModelDeleteTests::mask and deleting sets start empty()")
    fun `mask and deleting sets start empty`() = scenario {
        val state = ContactsStateHolder(Harness(clock).dependencies, scope).state.value
        assertTrue(state.pendingRemovalIds.isEmpty())
        assertTrue(state.deletingIds.isEmpty())
    }

    @Test @OriginalCase("ContactsViewModelDeleteTests::delete contact without service surfaces error and keeps row()")
    fun `delete contact without service surfaces error and keeps row`() = scenario {
        val holder = ContactsStateHolder(Harness(clock).dependencies, scope)
        val alice = contact(name = "Alice")
        holder.seed { it.copy(contacts = listOf(alice)) }
        holder.deleteContact(alice)
        val state = holder.state.value
        assertNotNull(state.errorMessage)
        assertEquals(listOf(alice.id), state.contacts.map { it.id })
        assertTrue(state.pendingRemovalIds.isEmpty())
        assertTrue(state.deletingIds.isEmpty())
    }

    @Test @OriginalCase("ContactsViewModelDeleteTests::bounded command times out()", "native-equivalent")
    fun `bounded command times out`() = scenario {
        // Virtual clock instead of a 20 ms wall-clock race.
        val outcome = scope.async {
            runCatching { withCommandTimeout(clock, 20.milliseconds, "removeContact") { clock.sleep(10.seconds) } }
        }
        settle()
        clock.advanceBy(20.milliseconds)
        val failure = outcome.await().exceptionOrNull()
        assertTrue(failure is CommandTimeoutException, "expected a timeout, got $failure")
        assertEquals("removeContact", failure.operationName)
    }
}

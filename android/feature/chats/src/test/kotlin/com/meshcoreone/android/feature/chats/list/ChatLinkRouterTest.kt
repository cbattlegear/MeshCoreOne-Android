// PortedFrom: MC1Tests/Views/Chats/ChatLinkRouterTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.chats.list.support.FakeLinks
import com.meshcoreone.android.feature.chats.list.support.Fixtures
import com.meshcoreone.android.feature.chats.list.support.OriginalCase
import com.meshcoreone.android.feature.chats.list.support.Scenario
import com.meshcoreone.android.feature.chats.list.support.scenario
import com.meshcoreone.android.feature.chats.list.support.settle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertSame
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import java.lang.management.ManagementFactory
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

class ChatLinkRouterTest {
    private fun Scenario.router(links: FakeLinks = FakeLinks()) = links to ChatLinkRouter(links, scope)

    private fun finishThreads(vararg workers: Pair<Thread, FutureTask<*>>) {
        var failure: Throwable? = null
        for ((thread, task) in workers) {
            if (thread.state == Thread.State.NEW) continue
            try {
                thread.join(10_000)
                check(!thread.isAlive) { "${thread.name} did not terminate" }
                task.get(1, TimeUnit.SECONDS)
            } catch (caught: Throwable) {
                val previous = failure
                if (previous == null) failure = caught else previous.addSuppressed(caught)
            }
        }
        failure?.let { throw it }
    }

    @Test fun contactCaptureDoesNotResampleRadioOrSelfIdentityAfterInitiation() = scenario {
        val publicKey = Bytes(ByteArray(32) { 0x23 })
        val links = FakeLinks()
        val initiatingRadio = links.currentRadioId
        val diagnostics = mutableListOf<String>()
        links.afterContactCapture = {
            links.currentRadioId = RadioId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
            links.connectedDevicePublicKey = publicKey
            links.contactLinkGeneration++
        }
        ChatLinkRouter(links, scope, ChatListDiagnostics { message, _ -> diagnostics += message })
            .route("meshcore://contact/add?name=Captured&public_key=${publicKey.hexString}&type=1")
        settle()
        assertNotEquals(initiatingRadio, links.currentRadioId)
        assertTrue(links.contactFetchRadios.isEmpty())
        assertTrue(links.contactCompletions.isEmpty())
        assertEquals(listOf("Contact-link lookup context is no longer current"), diagnostics)
        assertNull(links.pendingContact)
        assertNull(links.contactDetail)
    }

    @Test fun firstBindingReresolvesTheSameCaptureAndReadsOnlyTheBoundRadio() = scenario {
        val publicKey = Bytes(ByteArray(32) { 0x29 })
        for (found in listOf(false, true)) {
            val links = FakeLinks(currentRadioId = null)
            val saved = Fixtures.contact(publicKey = publicKey)
            if (found) links.storedContacts = listOf(saved)
            var resolutions = 0
            links.afterContactResolve = {
                if (resolutions++ == 0) {
                    links.currentRadioId = Fixtures.radio
                    links.contactLinkGeneration++
                }
            }
            assertTrue(ChatLinkRouter(links, scope).route(
                "meshcore://contact/add?name=Initial&public_key=${publicKey.hexString}&type=2",
            ))
            settle()
            assertEquals(2, resolutions)
            assertEquals(listOf(Fixtures.radio), links.contactFetchRadios)
            if (found) {
                assertEquals(saved, links.contactDetail)
                assertNull(links.pendingContact)
                assertEquals(listOf(ContactLinkCompletion.Navigated), links.contactCompletions)
            } else {
                val pending = assertNotNull(links.pendingContact)
                assertEquals("Initial", pending.name)
                assertEquals(publicKey, pending.publicKey)
                assertEquals(ContactType.REPEATER, pending.contactType)
                assertNull(links.contactDetail)
                assertEquals(listOf(ContactLinkCompletion.ConfirmationStaged), links.contactCompletions)
            }
        }
    }

    @Test fun firstBindingToSelfCompletesWithoutFetchingOrStaging() = scenario {
        val publicKey = Bytes(ByteArray(32) { 0x2a })
        val links = FakeLinks(currentRadioId = null)
        var resolutions = 0
        links.afterContactResolve = {
            if (resolutions++ == 0) {
                links.currentRadioId = Fixtures.radio
                links.connectedDevicePublicKey = publicKey
                links.contactLinkGeneration++
            }
        }
        assertTrue(ChatLinkRouter(links, scope).route(
            "meshcore://contact/add?name=Self&public_key=${publicKey.hexString}&type=1",
        ))
        settle()
        assertEquals(2, resolutions)
        assertTrue(links.contactFetchRadios.isEmpty())
        assertEquals(listOf(ContactLinkCompletion.IgnoredSelfContact), links.contactCompletions)
        assertNull(links.pendingContact)
        assertNull(links.contactDetail)
    }

    @Test fun contactStorageFailureIsReportedWithoutPretendingTheContactIsAbsent() = scenario {
        val links = FakeLinks()
        val failure = IllegalStateException("contact store unavailable")
        val diagnostics = mutableListOf<Pair<String, Throwable?>>()
        links.contactFetch = { _, _ -> throw failure }
        val router = ChatLinkRouter(links, scope, ChatListDiagnostics { message, cause ->
            diagnostics += message to cause
        })
        assertTrue(router.route("meshcore://contact/add?name=Unavailable&public_key=${"2b".repeat(32)}&type=1"))
        settle()
        assertEquals(1, diagnostics.size)
        assertEquals("Contact-link lookup failed", diagnostics.single().first)
        assertSame(failure, diagnostics.single().second)
        val visible = assertNotNull(router.contactLookupFailure.first())
        assertSame(failure, (visible.issue as ContactLinkLookupIssue.Failed).cause)
        assertEquals(0L, visible.attempt)
        assertTrue(links.contactCompletions.isEmpty())
        assertNull(links.pendingContact)
        assertNull(links.contactDetail)
    }

    @Test fun retryUsesTheOriginalCaptureAndRetainsContactMetadata() = scenario {
        val publicKey = Bytes(ByteArray(32) { 0x2d })
        val links = FakeLinks()
        var captures = 0
        links.afterContactCapture = { captures++ }
        links.contactFetch = { _, _ -> throw IllegalStateException("first lookup failed") }
        val router = ChatLinkRouter(links, scope)
        assertTrue(router.route("meshcore://contact/add?name=Retry%20peer&public_key=${publicKey.hexString}&type=2"))
        settle()
        val failure = assertNotNull(router.contactLookupFailure.first())
        assertEquals(listOf(Fixtures.radio), links.contactFetchRadios)
        assertNull(links.pendingContact)

        links.contactFetch = { _, _ -> null }
        router.retryContactLookup(failure)
        settle()
        assertEquals(1, captures)
        assertEquals(listOf(Fixtures.radio, Fixtures.radio), links.contactFetchRadios)
        val pending = assertNotNull(links.pendingContact)
        assertEquals("Retry peer", pending.name)
        assertEquals(publicKey, pending.publicKey)
        assertEquals(ContactType.REPEATER, pending.contactType)
        assertNull(router.contactLookupFailure.first())
    }

    @Test fun retryOfRetiredContextNeverReadsTheReplacementRadio() = scenario {
        val links = FakeLinks()
        val replacement = RadioId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
        links.contactFetch = { _, _ -> throw IllegalStateException("lookup failed") }
        val router = ChatLinkRouter(links, scope)
        assertTrue(router.route("meshcore://contact/add?name=Retry&public_key=${"2e".repeat(32)}&type=1"))
        settle()
        val failure = assertNotNull(router.contactLookupFailure.first())
        links.currentRadioId = replacement
        links.contactLinkGeneration++
        router.retryContactLookup(failure)
        settle()
        val retired = assertNotNull(router.contactLookupFailure.first())
        assertEquals(failure.deliveryId, retired.deliveryId)
        assertEquals(1L, retired.attempt)
        assertEquals(ContactLinkLookupIssue.ContextRetired, retired.issue)
        router.retryContactLookup(retired)
        settle()
        assertEquals(listOf(Fixtures.radio), links.contactFetchRadios)
        assertTrue(links.contactCompletions.isEmpty())
        assertNull(links.pendingContact)
        assertNull(links.contactDetail)
        router.dismissContactLookupFailure(retired)
        assertNull(router.contactLookupFailure.first())
    }

    @Test fun identicalReplacementFailureCannotBeRetriedOrDismissedUsingTheOldDelivery() = scenario {
        val links = FakeLinks()
        links.contactFetch = { _, _ -> throw IllegalStateException("lookup failed") }
        val router = ChatLinkRouter(links, scope)
        val uri = "meshcore://contact/add?name=Same&public_key=${"2f".repeat(32)}&type=1"
        assertTrue(router.route(uri))
        settle()
        val first = assertNotNull(router.contactLookupFailure.first())
        assertTrue(router.route(uri))
        settle()
        val replacement = assertNotNull(router.contactLookupFailure.first())
        assertNotEquals(first.deliveryId, replacement.deliveryId)
        router.retryContactLookup(first)
        router.dismissContactLookupFailure(first)
        settle()
        assertEquals(replacement, router.contactLookupFailure.first())
        assertEquals(2, links.contactFetchRadios.size)

        links.contactFetch = { _, _ -> null }
        router.retryContactLookup(replacement)
        settle()
        assertNull(router.contactLookupFailure.first())
        assertEquals("Same", assertNotNull(links.pendingContact).name)
        assertEquals(3, links.contactFetchRadios.size)
    }

    @Test fun lateFailureCannotReplaceAnIdenticalNewDeliveryFailure() = scenario {
        val links = FakeLinks()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val latestCause = IllegalStateException("replacement failed")
        var calls = 0
        links.contactFetch = { _, _ ->
            if (calls++ == 0) {
                entered.complete(Unit)
                release.await()
                throw IllegalStateException("older failure")
            }
            throw latestCause
        }
        val router = ChatLinkRouter(links, scope)
        val uri = "meshcore://contact/add?name=Same&public_key=${"30".repeat(32)}&type=1"
        assertTrue(router.route(uri))
        entered.await()
        assertTrue(router.route(uri))
        settle()
        val replacement = assertNotNull(router.contactLookupFailure.first())
        assertSame(latestCause, (replacement.issue as ContactLinkLookupIssue.Failed).cause)
        release.complete(Unit)
        settle()
        assertEquals(replacement, router.contactLookupFailure.first())
        assertNull(links.pendingContact)
        assertNull(links.contactDetail)
    }

    @Test fun cancellationBeforeFailurePublicationDoesNotExposeARecoveryPrompt() = scenario {
        val links = FakeLinks()
        val parent = SupervisorJob(scope.coroutineContext[Job])
        val routing = CoroutineScope(scope.coroutineContext + parent)
        links.contactFetch = { _, _ -> throw IllegalStateException("lookup failed") }
        val router = ChatLinkRouter(links, routing, ChatListDiagnostics { _, failure ->
            if (failure != null) parent.cancel()
        })
        assertTrue(router.route("meshcore://contact/add?name=Cancel&public_key=${"31".repeat(32)}&type=1"))
        settle()
        assertNull(router.contactLookupFailure.first())
        assertTrue(links.contactCompletions.isEmpty())
        assertNull(links.pendingContact)
    }

    @Test fun repeatedTargetChangesStopAfterOneReresolution() = scenario {
        val links = FakeLinks()
        val diagnostics = mutableListOf<String>()
        var completions = 0
        val environment = object : ChatLinkEnvironment by links {
            override fun completeResolvedContactLookup(
                context: ContactLinkContext, target: ContactLinkResolvedTarget, link: ContactLinkResult,
                existing: com.meshcoreone.android.core.model.ContactDTO?, publications: ContactLinkPublications,
                cancellation: ContactLinkCancellation,
            ): ContactLinkTargetCompletion {
                cancellation.ensureActive()
                completions++
                return ContactLinkTargetCompletion.TargetChanged
            }
        }
        assertTrue(ChatLinkRouter(environment, scope, ChatListDiagnostics { message, _ -> diagnostics += message })
            .route("meshcore://contact/add?name=Moving&public_key=${"2c".repeat(32)}&type=1"))
        settle()
        assertEquals(2, completions)
        assertEquals(listOf("Contact-link target changed repeatedly"), diagnostics)
        assertNull(links.pendingContact)
        assertNull(links.contactDetail)
    }

    @Test fun heldContactLookupRejectsSameRadioReplacementAndIdenticalNewDeliveryWins() = scenario {
        val publicKey = Bytes(ByteArray(32) { 0x24 })
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val links = FakeLinks()
        var first = true
        links.contactFetch = { _, _ ->
            if (first) {
                first = false
                entered.complete(Unit)
                release.await()
            }
            null
        }
        val router = ChatLinkRouter(links, scope)
        val uri = "meshcore://contact/add?name=Identical&public_key=${publicKey.hexString}&type=1"
        assertTrue(router.route(uri))
        entered.await()
        links.contactLinkGeneration++
        assertTrue(router.route(uri))
        settle()
        val replacement = assertNotNull(links.pendingContact)
        release.complete(Unit)
        settle()
        assertSame(replacement, links.pendingContact)
        assertEquals(listOf(ContactLinkCompletion.ConfirmationStaged, ContactLinkCompletion.StaleContext), links.contactCompletions)
        assertNull(links.contactDetail)
    }

    @Test fun publicationReleaseIsOnceOnlyAndPreservesTheOriginalCancellation() {
        var releases = 0
        val cleanupFailure = IllegalStateException("observer failed")
        val cancellation = CancellationException("lookup cancelled")
        val publications = ContactLinkPublications()
        val thrown = assertFailsWith<CancellationException> {
            publications.use {
                it.defer(object : ContactLinkPublication {
                    override fun release() {
                        releases++
                        throw cleanupFailure
                    }
                })
                throw cancellation
            }
        }
        publications.release()
        assertEquals(1, releases)
        assertSame(cancellation, thrown)
        assertEquals(listOf(cleanupFailure), thrown.suppressed.toList())
    }

    @Test fun processedCancellationPreventsANewContactNavigationCommit() {
        val job = Job()
        val (cancellation, observation) = ContactLinkCancellation.observe(job)
        var writes = 0
        try {
            job.cancel()
            assertFailsWith<CancellationException> {
                cancellation.commitIfActive { writes++ }
            }
            assertEquals(0, writes)
        } finally {
            observation.dispose()
        }
    }

    @Test fun repeatedCancelsDoNotJoinAnAlreadyClaimedContactCommit() {
        val parent = SupervisorJob()
        val job = Job(parent)
        val (cancellation, observation) = ContactLinkCancellation.observe(job)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        val commit = FutureTask {
            cancellation.commitIfActive {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS)) { "Contact commit was not released" }
                writes.incrementAndGet()
            }
        }
        val cancel = FutureTask { parent.cancel() }
        val repeatParent = FutureTask { parent.cancel() }
        val repeatChild = FutureTask { job.cancel() }
        val committingThread = Thread(commit, "contact-commit").apply { isDaemon = true }
        val cancellingThread = Thread(cancel, "contact-cancel").apply { isDaemon = true }
        val repeatParentThread = Thread(repeatParent, "contact-repeat-parent-cancel").apply { isDaemon = true }
        val repeatChildThread = Thread(repeatChild, "contact-repeat-child-cancel").apply { isDaemon = true }
        var failure: Throwable? = null
        try {
            committingThread.start()
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Contact commit did not acquire the gate")
            cancellingThread.start()
            val threads = ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var blocked = false
            while (System.nanoTime() < deadline && cancellingThread.isAlive) {
                val info = threads.getThreadInfo(cancellingThread.id, Int.MAX_VALUE)
                if (info?.threadState == Thread.State.BLOCKED && info.lockOwnerId == committingThread.id) {
                    blocked = true
                    break
                }
                Thread.yield()
            }
            assertTrue(blocked, "The first observer must wait for the claimed commit")
            assertFalse(cancel.isDone)
            repeatParentThread.start()
            repeatParent.get(10, TimeUnit.SECONDS)
            repeatChildThread.start()
            repeatChild.get(10, TimeUnit.SECONDS)
            assertFalse(cancel.isDone)
            assertEquals(0, writes.get())
            assertFailsWith<CancellationException> { cancellation.ensureActive() }
            release.countDown()
            assertEquals(1, commit.get(10, TimeUnit.SECONDS))
            cancel.get(10, TimeUnit.SECONDS)
            assertTrue(parent.isCancelled)
            assertTrue(job.isCancelled)
            assertFailsWith<CancellationException> {
                cancellation.commitIfActive { writes.incrementAndGet() }
            }
            assertEquals(1, writes.get())
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            release.countDown()
            try {
                finishThreads(
                    committingThread to commit, cancellingThread to cancel,
                    repeatParentThread to repeatParent, repeatChildThread to repeatChild,
                )
                if (cancellingThread.state == Thread.State.NEW) parent.cancel()
            } catch (cleanup: Throwable) {
                val original = failure
                if (original == null) throw cleanup else original.addSuppressed(cleanup)
            } finally {
                observation.dispose()
            }
        }
    }

    @Test fun identicalCurrentSessionDeliverySupersedesAnOlderFoundResult() = scenario {
        val publicKey = Bytes(ByteArray(32) { 0x25 })
        val release = CompletableDeferred<com.meshcoreone.android.core.model.ContactDTO?>()
        val entered = CompletableDeferred<Unit>()
        val links = FakeLinks()
        var first = true
        links.contactFetch = { _, _ ->
            if (first) {
                first = false
                entered.complete(Unit)
                release.await()
            } else null
        }
        val router = ChatLinkRouter(links, scope)
        val uri = "meshcore://contact/add?name=Same&public_key=${publicKey.hexString}&type=1"
        assertTrue(router.route(uri))
        entered.await()
        assertTrue(router.route(uri))
        settle()
        val replacement = assertNotNull(links.pendingContact)
        release.complete(Fixtures.contact(publicKey = publicKey))
        settle()
        assertSame(replacement, links.pendingContact)
        assertNull(links.contactDetail)
        assertEquals(listOf(ContactLinkCompletion.ConfirmationStaged, ContactLinkCompletion.StaleContext), links.contactCompletions)
    }

    @Test fun selfContactSupersedesHeldLookupWithoutFetchingSelf() = scenario {
        val publicKey = Bytes(ByteArray(32) { 0x27 })
        val selfKey = Bytes(ByteArray(32) { 0x28 })
        for (found in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<com.meshcoreone.android.core.model.ContactDTO?>()
            val links = FakeLinks(connectedDevicePublicKey = selfKey)
            links.contactFetch = { _, key ->
                assertEquals(publicKey, key)
                entered.complete(Unit)
                release.await()
            }
            val router = ChatLinkRouter(links, scope)
            assertTrue(router.route("meshcore://contact/add?name=Older&public_key=${publicKey.hexString}&type=1"))
            entered.await()
            assertTrue(router.route("meshcore://contact/add?name=Self&public_key=${selfKey.hexString}&type=1"))
            assertEquals(listOf(links.currentRadioId), links.contactFetchRadios)
            release.complete(Fixtures.contact(publicKey = publicKey).takeIf { found })
            settle()
            assertEquals(listOf(ContactLinkCompletion.StaleContext), links.contactCompletions)
            assertNull(links.pendingContact)
            assertNull(links.contactDetail)
        }
    }

    @Test fun contactLookupCancellationDoesNotStageAnAbsentResult() = scenario {
        val links = FakeLinks()
        links.contactFetch = { _, _ -> throw CancellationException("lookup cancelled") }
        val router = ChatLinkRouter(links, scope)
        assertTrue(router.route(
            "meshcore://contact/add?name=Cancelled&public_key=${"26".repeat(32)}&type=1",
        ))
        settle()
        assertNull(router.contactLookupFailure.first())
        assertTrue(links.contactCompletions.isEmpty())
        assertNull(links.pendingContact)
        assertNull(links.contactDetail)
    }

    @Test @OriginalCase("ChatLinkRouterTests::Returns false for plain https URLs (caller falls through to systemAction)()")
    fun `Returns false for plain https URLs`() = scenario {
        assertFalse(router().second.route("https://apple.com"))
    }

    @Test @OriginalCase("ChatLinkRouterTests::Returns false for mailto URLs (caller falls through to systemAction)()")
    fun `Returns false for mailto URLs`() = scenario {
        assertFalse(router().second.route("mailto:test@example.com"))
    }

    @Test @OriginalCase("ChatLinkRouterTests::Returns true for meshcoreone hashtag URLs and stages a pending hashtag()")
    fun `Returns true for meshcoreone hashtag URLs and stages a pending hashtag`() = scenario {
        val (links, router) = router()
        assertTrue(router.route("meshcoreone://hashtag/general"))
        settle()
        assertEquals("#general", links.pendingHashtag)
    }

    @Test @OriginalCase("ChatLinkRouterTests::Returns true for meshcore map URLs and stages map focus immediately()")
    fun `Returns true for meshcore map URLs and stages map focus immediately`() = scenario {
        val (links, router) = router()
        assertTrue(router.route("meshcore://map?lat=37.7749&lon=-122.4194"))
        assertEquals(37.7749 to -122.4194, links.mapFocus)
    }

    @Test @OriginalCase("ChatLinkRouterTests::Returns true for malformed hashtag URLs without crashing()")
    fun `Returns true for malformed hashtag URLs without crashing`() = scenario {
        val (links, router) = router()
        assertTrue(router.route("meshcoreone://hashtag"))
        settle()
        assertNull(links.pendingHashtag)
    }

    @Test @OriginalCase("ChatLinkRouterTests::routeExternalOpen stages a pending contact and switches to the Chats tab()")
    fun `routeExternalOpen stages a pending contact and switches to the Chats tab`() = scenario {
        val (links, router) = router()
        links.selectedTab = AppTab.NODES
        val key = "ab".repeat(32)
        assertTrue(router.routeExternalOpen("meshcore://contact/add?name=NGC-MB&public_key=$key&type=1"))
        assertEquals(AppTab.CHATS, links.selectedTab)
        settle()
        assertNotNull(links.pendingContact)
    }

    @Test @OriginalCase("ChatLinkRouterTests::routeExternalOpen stages a pending channel and switches to the Chats tab()")
    fun `routeExternalOpen stages a pending channel and switches to the Chats tab`() = scenario {
        val (links, router) = router()
        links.selectedTab = AppTab.NODES
        val secret = "ab".repeat(16)
        assertTrue(router.routeExternalOpen("meshcore://channel/add?name=Test&secret=$secret&region_scope=testregion"))
        assertEquals(AppTab.CHATS, links.selectedTab)
        settle()
        assertEquals("Test", links.pendingChannel?.name)
        assertEquals("testregion", links.pendingChannel?.regionScope)
    }

    @Test @OriginalCase("ChatLinkRouterTests::Existing channel match navigates without applying URL region_scope()")
    fun `Existing channel match navigates without applying URL region_scope`() = scenario {
        val secret = Bytes(ByteArray(16) { 0xCD.toByte() })
        val existing = Fixtures.channel("Ops", index = 1u, secret = secret, floodScope = ChannelFloodScope.Region("Germany"))
        val links = FakeLinks().apply { storedChannels = listOf(existing) }
        val router = ChatLinkRouter(links, scope)
        assertTrue(router.route("meshcore://channel/add?name=Ops&secret=${secret.hexString.uppercase()}&region_scope=attacker"))
        settle()
        assertNull(links.pendingChannel)
        assertEquals(existing.id, links.navigatedChannel?.id)
        assertEquals(ChannelFloodScope.Region("Germany"), links.navigatedChannel?.floodScope)
    }

    @Test @OriginalCase("ChatLinkRouterTests::routeExternalOpen restores the previous tab for meshcoreone status URLs()")
    fun `routeExternalOpen restores the previous tab for meshcoreone status URLs`() = scenario {
        val (links, router) = router()
        links.selectedTab = AppTab.NODES
        assertFalse(router.routeExternalOpen("meshcoreone://status"))
        assertEquals(AppTab.NODES, links.selectedTab)
    }

    @Test @OriginalCase("ChatLinkRouterTests::routeExternalOpen ends on the map tab for meshcore map URLs()")
    fun `routeExternalOpen ends on the map tab for meshcore map URLs`() = scenario {
        val (links, router) = router()
        links.selectedTab = AppTab.NODES
        assertTrue(router.routeExternalOpen("meshcore://map?lat=37.7749&lon=-122.4194"))
        assertEquals(AppTab.MAP, links.selectedTab)
        assertNotNull(links.mapFocus)
    }

    @Test @OriginalCase("ChatLinkRouterTests::routeExternalOpen restores the previous tab for a malformed meshcore URL()")
    fun `routeExternalOpen restores the previous tab for a malformed meshcore URL`() = scenario {
        val (links, router) = router()
        links.selectedTab = AppTab.NODES
        assertFalse(router.routeExternalOpen("meshcore://garbage"))
        assertEquals(AppTab.NODES, links.selectedTab)
    }

    @Test @OriginalCase("ChatLinkRouterTests::routeExternalOpen stages a pending hashtag and switches to the Chats tab()")
    fun `routeExternalOpen stages a pending hashtag and switches to the Chats tab`() = scenario {
        val (links, router) = router()
        links.selectedTab = AppTab.NODES
        assertTrue(router.routeExternalOpen("meshcoreone://hashtag/general"))
        assertEquals(AppTab.CHATS, links.selectedTab)
        settle()
        assertEquals("#general", links.pendingHashtag)
    }

    // Android-only: a failed hashtag lookup stages the join request instead of dropping the tap.
    @Test
    fun `hashtag lookup failure stages the join request`() = scenario {
        val links = FakeLinks().apply { failChannelFetch = IllegalStateException("boom") }
        ChatLinkRouter(links, scope).route("meshcoreone://hashtag/general")
        settle()
        assertEquals("#general", links.pendingHashtag)
    }

    // Android-only: an existing hashtag channel is navigated to, case-insensitively, not re-staged.
    @Test
    fun `hashtag tap navigates to the existing channel`() = scenario {
        val existing = Fixtures.channel("#General", index = 4u)
        val links = FakeLinks().apply { storedChannels = listOf(existing) }
        ChatLinkRouter(links, scope).route("meshcoreone://hashtag/general")
        settle()
        assertEquals(existing.id, links.navigatedChannel?.id)
        assertNull(links.pendingHashtag)
    }
}

// AndroidOnly: WP-314 Production graph-publication boundary before servicesVersion/Compose retirement.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.PersistenceKeys
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.event.TraceInfo
import com.meshcoreone.android.core.runtime.ProcessConnectionPreferences
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.runtime.RuntimePreferenceSnapshot
import com.meshcoreone.android.core.runtime.RuntimePreferenceValue
import com.meshcoreone.android.feature.tools.trace.RecentHopsStorage
import com.meshcoreone.android.feature.tools.trace.SavedPathsDependencies
import com.meshcoreone.android.feature.tools.trace.SavedPathsStateHolder
import com.meshcoreone.android.feature.tools.trace.SavedPathsStrings
import com.meshcoreone.android.feature.tools.trace.SystemTraceTimeSource
import com.meshcoreone.android.feature.tools.trace.TraceDiagnostics
import com.meshcoreone.android.feature.tools.trace.TracePathFeatureDependencies
import com.meshcoreone.android.feature.tools.trace.TracePathStateHolder
import com.meshcoreone.android.feature.tools.trace.TracePathStrings
import com.meshcoreone.android.feature.tools.trace.TraceSender
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TraceSessionLifetimeTest : RoomProcessTest() {
    @Test
    fun sameRadioMutationAcceptedBeforePublicationMayFinishAgainstItsOriginalKey() =
        mutationAcceptanceBoundary(crossRadio = false)

    @Test
    fun saveAcceptedBeforeDifferentRadioPublicationNeverUsesTheReplacementRadio() =
        mutationAcceptanceBoundary(crossRadio = true)

    private fun mutationAcceptanceBoundary(crossRadio: Boolean) = runTest {
        val memory = MemoryConnectionPreferences()
        val holdPublication = AtomicBoolean()
        val publicationReached = CompletableDeferred<Unit>()
        val releasePublication = CompletableDeferred<Unit>()
        val preferences = object : ProcessConnectionPreferences by memory {
            override suspend fun update(
                transform: (MutableMap<String, RuntimePreferenceValue>) -> Unit,
            ): RuntimePreferenceSnapshot {
                val next = memory.read().values.toMutableMap()
                var writesLastConnection = false
                val tracking = object : MutableMap<String, RuntimePreferenceValue> by next {
                    override fun put(key: String, value: RuntimePreferenceValue): RuntimePreferenceValue? {
                        if (key == PersistenceKeys.LAST_CONNECTED_DEVICE_NAME) writesLastConnection = true
                        return next.put(key, value)
                    }
                }
                transform(tracking)
                if (writesLastConnection && holdPublication.compareAndSet(true, false)) {
                    publicationReached.complete(Unit)
                    releasePublication.await()
                }
                return memory.update { it.clear(); it.putAll(next) }
            }
        }
        var replacing = false
        val h = ContainerHarness(this, store, identity = { if (replacing && crossRadio) key(9) else key(7) },
            preferencesOverride = preferences)
        val releaseTokenRead = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val holderScope = CoroutineScope(SupervisorJob() + worker)
        try {
            val target = h.target()
            h.manager.connect(target)
            h.settle()
            h.assertReady()
            val recents = object : RecentHopsStorage {
                override fun stringList(key: String): List<String>? = null
                override fun setStringList(key: String, value: List<String>) = Unit
            }
            val actual = h.container.createToolsFeatureDependencies(
                recents, h.container.createToolsDiagnosticsDependencies(),
            ).trace
            val oldToken = assertNotNull(actual.sessionToken())
            val version = h.appState.servicesVersion.value
            val holdTokenRead = AtomicBoolean()
            val tokenRead = CompletableDeferred<Unit>()
            var tag = 0u
            val feature = object : TracePathFeatureDependencies by actual {
                override fun sessionToken(): com.meshcoreone.android.core.contracts.domain.SessionToken? {
                    val captured = actual.sessionToken()
                    if (holdTokenRead.compareAndSet(true, false)) {
                        tokenRead.complete(Unit)
                        check(releaseTokenRead.await(30, TimeUnit.SECONDS)) { "Publication control did not release token read" }
                    }
                    return captured
                }
                override fun traceSender() = TraceSender { sentTag, _, _, _ ->
                    tag = sentTag
                    MessageSentInfo(0u, Bytes.EMPTY, 0u)
                }
            }
            val path = store.createSavedTracePath(oldToken.radioId, "Accepted A mutation", Bytes.of(0xAA), 1, null)
            val untouched = store.createSavedTracePath(oldToken.radioId, "Stale A callback", Bytes.of(0xBB), 1, null)
            val diagnostics = TraceDiagnostics { _, error -> throw AssertionError(error) }
            val saved = SavedPathsStateHolder(SavedCopy, diagnostics)
            val trace = TracePathStateHolder(holderScope, SystemTraceTimeSource, Copy, recents, diagnostics)
            withContext(worker) {
                saved.configure(object : SavedPathsDependencies {
                    override fun sessionToken() = feature.sessionToken()
                    override fun connectedDevice() = feature.connectedDevice()
                    override fun savedPaths() = feature.savedPaths()
                })
                saved.loadSavedPaths()
                trace.configure(feature)
                trace.bindConnection(oldToken.radioId, version)
                trace.loadSavedPath(path)
                trace.runTrace()
                trace.handleTraceResponse(TraceInfo(tag, 0u, 0u, 0u, emptyList()), oldToken.radioId)
                assertTrue(assertNotNull(trace.state.value.result).success)
            }
            holdTokenRead.set(true)
            val accepted = backgroundScope.async(worker) {
                if (crossRadio) trace.savePath("Accepted A result") else saved.deletePath(path)
            }
            h.eventually("A action token read") { tokenRead.isCompleted }
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            h.settle()
            replacing = true
            holdPublication.set(true)
            val replacementTarget = if (crossRadio) h.target() else target
            val reconnect = backgroundScope.async { h.manager.connect(replacementTarget) }
            h.eventually("B device published before A action continues") { publicationReached.isCompleted }
            val newToken = assertNotNull(actual.sessionToken())
            assertNotEquals(oldToken, newToken)
            assertEquals(version, h.appState.servicesVersion.value)
            assertEquals(newToken.radioId, actual.connectedDevice()?.radioId)
            assertEquals(crossRadio, oldToken.radioId != newToken.radioId)
            releaseTokenRead.countDown()
            h.eventually("accepted A action finished") { accepted.isCompleted }
            assertTrue(accepted.await())
            if (crossRadio) {
                assertEquals(1, store.fetchSavedTracePaths(oldToken.radioId).count { it.name == "Accepted A result" })
                assertFalse(store.fetchSavedTracePaths(newToken.radioId).any { it.name == "Accepted A result" })
            } else {
                assertNull(store.fetchSavedTracePath(EntityKey(oldToken.radioId, path.id)))
            }
            withContext(worker) {
                assertFalse(saved.deletePath(untouched), "A callback first invoked after B publication is not accepted")
                trace.loadSavedPath(untouched)
                assertNull(trace.state.value.activeSavedPath)
                assertFalse(trace.savePath("Stale B result"))
            }
            assertNotNull(store.fetchSavedTracePath(EntityKey(oldToken.radioId, untouched.id)))
            releasePublication.complete(Unit)
            h.eventually("new connection ready") { reconnect.isCompleted }
            reconnect.await()
            h.settle()
            h.assertReady()
        } finally {
            releaseTokenRead.countDown()
            releasePublication.complete(Unit)
            holderScope.cancel()
            worker.close()
            h.container.close()
        }
    }

    @Test
    fun sameRadioRegistryPublicationRejectsOldCallbacksBeforeServicesVersionChanges() = runTest {
        val memory = MemoryConnectionPreferences()
        val armed = AtomicBoolean()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val preferences = object : ProcessConnectionPreferences by memory {
            override suspend fun update(
                transform: (MutableMap<String, RuntimePreferenceValue>) -> Unit,
            ): RuntimePreferenceSnapshot {
                val next = memory.read().values.toMutableMap()
                var writesLastConnection = false
                val tracking = object : MutableMap<String, RuntimePreferenceValue> by next {
                    override fun put(key: String, value: RuntimePreferenceValue): RuntimePreferenceValue? {
                        if (key == PersistenceKeys.LAST_CONNECTED_DEVICE_NAME) writesLastConnection = true
                        return next.put(key, value)
                    }
                }
                transform(tracking)
                if (writesLastConnection && armed.compareAndSet(true, false)) {
                    reached.complete(Unit)
                    release.await()
                }
                return memory.update { it.clear(); it.putAll(next) }
            }
        }
        val h = ContainerHarness(this, store, preferencesOverride = preferences)
        try {
            val target = h.target()
            h.manager.connect(target)
            h.settle()
            h.assertReady()
            val recents = object : RecentHopsStorage {
                private val values = mutableMapOf<String, List<String>>()
                override fun stringList(key: String) = values[key]
                override fun setStringList(key: String, value: List<String>) { values[key] = value }
            }
            val feature = h.container.createToolsFeatureDependencies(
                recents, h.container.createToolsDiagnosticsDependencies(),
            ).trace
            val token = assertNotNull(feature.sessionToken())
            val version = h.appState.servicesVersion.value
            val path = store.createSavedTracePath(token.radioId, "Retained route", Bytes.of(0xAA), 1, null)
            val controlPath = store.createSavedTracePath(token.radioId, "Radio-only control", Bytes.of(0xBB), 1, null)
            val diagnostics = TraceDiagnostics { _, error -> throw AssertionError(error) }
            fun savedHolder(withToken: Boolean) = SavedPathsStateHolder(SavedCopy, diagnostics).apply {
                configure(object : SavedPathsDependencies {
                    override fun sessionToken() = if (withToken) feature.sessionToken() else null
                    override fun connectedDevice() = feature.connectedDevice()
                    override fun savedPaths() = feature.savedPaths()
                })
            }
            val saved = savedHolder(true)
            val radioOnly = savedHolder(false)
            saved.loadSavedPaths()
            radioOnly.loadSavedPaths()
            val trace = TracePathStateHolder(backgroundScope, SystemTraceTimeSource, Copy, recents, diagnostics).apply {
                configure(feature)
                bindConnection(token.radioId, version)
                loadSavedPath(path)
            }
            assertEquals(path.id, trace.state.value.activeSavedPath?.id)
            val retainedSelect = { if (saved.isCurrentPath(path)) trace.loadSavedPath(path) }
            val retainedDelete: suspend () -> Boolean = {
                if (saved.isCurrentPath(path)) saved.deletePath(path) else false
            }
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            h.settle()
            assertNull(h.sessions.current)
            assertFalse(saved.isCurrentPath(path), "A null graph invalidates a retained row before recomposition")
            armed.set(true)
            val reconnect = backgroundScope.async { h.manager.connect(target) }
            h.eventually("new graph held in lastConnection.persist", virtualBudgetMillis = 1_000) {
                reached.isCompleted
            }
            val current = assertNotNull(h.sessions.current)
            assertNotEquals(token, current.token)
            assertEquals(token.radioId, feature.connectedDevice()?.radioId)
            assertEquals(current.token, feature.sessionToken())
            assertEquals(version, h.appState.servicesVersion.value, "The UI publication must still be held")
            assertTrue(radioOnly.isCurrentPath(controlPath), "The old radio-only predicate is eligible on the new graph")
            assertTrue(radioOnly.deletePath(controlPath), "Discriminating control reproduces the old live-store mutation")
            assertNull(store.fetchSavedTracePath(EntityKey(token.radioId, controlPath.id)))
            retainedSelect()
            assertFalse(retainedDelete())
            trace.runTrace()
            assertNull(trace.state.value.activeSavedPath)
            assertFalse(trace.state.value.isRunning)
            assertNotNull(store.fetchSavedTracePath(EntityKey(token.radioId, path.id)))
            assertTrue(saved.state.value.savedPaths.any { it.id == path.id }, "No Compose disposal was used to pass the guard")
            release.complete(Unit)
            h.eventually("reconnected graph", virtualBudgetMillis = 1_000) { reconnect.isCompleted }
            reconnect.await()
            h.settle()
            h.assertReady()
            assertNotEquals(version, h.appState.servicesVersion.value)
            val fresh = savedHolder(true)
            fresh.loadSavedPaths()
            assertTrue(fresh.isCurrentPath(path))
            trace.bindConnection(token.radioId, h.appState.servicesVersion.value)
            trace.loadSavedPath(path)
            assertEquals(path.id, trace.state.value.activeSavedPath?.id)
            assertTrue(fresh.deletePath(path))
            assertNull(store.fetchSavedTracePath(EntityKey(token.radioId, path.id)))
        } finally {
            release.complete(Unit)
            h.container.close()
        }
    }

    private object SavedCopy : SavedPathsStrings {
        override val loadFailed = "Load failed"
        override val renameFailed = "Rename failed"
        override val deleteFailed = "Delete failed"
    }

    private object Copy : TracePathStrings {
        override val myDevice = "My device"
        override val sendFailed = "Send failed"
        override val noResponse = "No response"
        override val defaultMapPathName = "Path"
        override fun allFailed(count: Int) = "Failed $count"
        override fun codeInvalidFormat(codes: String) = codes
        override fun codeNotFound(codes: String) = codes
        override fun codeAlreadyInPath(codes: String) = codes
        override fun pathNamePrefix(prefix: String) = prefix
        override fun pathNameTwoEndpoints(first: String, last: String) = "$first $last"
        override fun pathNameMultipleEndpoints(first: String, last: String) = "$first $last"
    }
}

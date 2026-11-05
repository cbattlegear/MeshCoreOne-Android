// AndroidOnly: WP-314 Production graph-publication boundary before servicesVersion/Compose retirement.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.PersistenceKeys
import com.meshcoreone.android.core.protocol.bytes.Bytes
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TraceSessionLifetimeTest : RoomProcessTest() {
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

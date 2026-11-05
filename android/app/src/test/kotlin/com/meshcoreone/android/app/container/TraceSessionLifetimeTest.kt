// AndroidOnly: WP-314 Production graph-publication boundary before servicesVersion/Compose retirement.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.contracts.domain.TracePathPersisting
import com.meshcoreone.android.core.model.PersistenceKeys
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SavedTracePathDTO
import com.meshcoreone.android.core.model.TracePathRunDTO
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.event.TraceInfo
import com.meshcoreone.android.core.protocol.model.ContactType
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
import com.meshcoreone.android.feature.tools.trace.TracePathScreen
import com.meshcoreone.android.feature.tools.trace.TracePathStrings
import com.meshcoreone.android.feature.tools.trace.TraceSender
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
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
    fun originLookupRejectsAWithdrawnGraphBeforeContainerWatchersStart() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val version = h.appState.servicesVersion.value
            val token = assertNotNull(h.sessions.current).token
            val recents = object : RecentHopsStorage {
                override fun stringList(key: String): List<String>? = null
                override fun setStringList(key: String, value: List<String>) = Unit
            }
            val actual = h.container.createToolsFeatureDependencies(
                recents, h.container.createToolsDiagnosticsDependencies(),
            ).trace
            assertEquals(token, actual.sessionToken())
            assertEquals(token, h.appState.sessionTokenForServicesVersion(version))
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            assertNull(h.sessions.current, "Actual registry withdrawal, not UI counter timing")
            assertNull(actual.sessionToken(), "The real feature observes registry withdrawal without a watcher")
            assertNull(h.manager.connectedDevice)
            assertNull(h.appState.sessionTokenForServicesVersion(version), "Cached origin cannot outlive the actual graph")
        } finally {
            h.container.close()
        }
    }

    @Test
    fun sameTokenReloadCannotAuthorizeOldVersionActionsAgainstAReplacementRow() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val recents = object : RecentHopsStorage {
                override fun stringList(key: String): List<String>? = null
                override fun setStringList(key: String, value: List<String>) = Unit
            }
            val diagnostics = TraceDiagnostics { _, error -> throw AssertionError(error) }
            val actual = h.container.createToolsFeatureDependencies(
                recents, h.container.createToolsDiagnosticsDependencies(),
            ).trace
            val token = assertNotNull(actual.sessionToken())
            var tag = 0u
            var sends = 0
            val feature = object : TracePathFeatureDependencies by actual {
                override fun traceSender() = TraceSender { sentTag, _, _, _ ->
                    sends += 1
                    tag = sentTag
                    MessageSentInfo(0u, Bytes.EMPTY, 0u)
                }
            }
            val versionA = h.appState.servicesVersion.value
            val captured = TracePathScreen.Dependencies(feature, recents, diagnostics, token.radioId, versionA)
            val path = store.createSavedTracePath(token.radioId, "Before restore", Bytes.of(0xAA), 1, null)
            val dependencies = object : SavedPathsDependencies {
                override fun sessionToken() = actual.sessionToken()
                override fun isOriginatingSessionCurrent(generation: Int, token: SessionToken?) =
                    actual.isOriginatingSessionCurrent(generation, token)
                override fun connectedDevice() = actual.connectedDevice()
                override fun savedPaths() = actual.savedPaths()
            }
            val saved = SavedPathsStateHolder(SavedCopy, diagnostics).apply {
                configure(dependencies, captured.originatingToken, captured.generation)
            }
            saved.loadSavedPaths()
            assertTrue(saved.isCurrentPath(path))
            val saving = TracePathStateHolder(backgroundScope, SystemTraceTimeSource, Copy, recents, diagnostics)
            saving.configure(feature)
            saving.bindConnection(captured.radioId, captured.generation, captured.originatingToken)
            saving.loadSavedPath(path)
            saving.runTrace()
            saving.handleTraceResponse(TraceInfo(tag, 0u, 0u, 0u, emptyList()), token.radioId)
            assertTrue(assertNotNull(saving.state.value.result).success)
            h.eventually("accepted A run committed before row replacement") { saving.state.value.activeSavedPath?.runs?.size == 1 }
            val replacement = path.copy(name = "Restored replacement", pathBytes = Bytes.of(0xBB))
            store.deleteSavedTracePath(EntityKey(path.radioId, path.id))
            db.tracePaths().upsert(replacement.toEntity())
            h.appState.bumpServicesVersion()
            val versionB = h.appState.servicesVersion.value
            assertNotEquals(versionA, versionB)
            assertEquals(token, actual.sessionToken(), "Reload deliberately keeps the radio session")
            assertEquals(token, actual.originatingSessionToken(versionB))
            assertFalse(saving.savePath("Stale restored result"))
            assertFalse(saved.isCurrentPath(path))
            saved.renamePath(path, "Stale rename")
            assertFalse(saved.deletePath(path), "Old callback cannot delete the replacement at the same key")
            val delayed = TracePathStateHolder(backgroundScope, SystemTraceTimeSource, Copy, recents, diagnostics)
            delayed.configure(feature)
            delayed.bindConnection(captured.radioId, captured.generation, captured.originatingToken)
            delayed.loadSavedPath(path)
            delayed.runTrace()
            assertNull(delayed.state.value.activeSavedPath)
            assertEquals(1, sends, "No new send from old v1")
            assertEquals(replacement, store.fetchSavedTracePath(EntityKey(path.radioId, path.id)))
            assertFalse(store.fetchSavedTracePaths(token.radioId).any { it.name == "Stale restored result" })
            val fresh = captured.copy(generation = versionB)
            val current = SavedPathsStateHolder(SavedCopy, diagnostics).apply {
                configure(dependencies, fresh.originatingToken, fresh.generation)
            }
            current.loadSavedPaths()
            assertTrue(current.isCurrentPath(replacement))
            delayed.bindConnection(fresh.radioId, fresh.generation, fresh.originatingToken)
            delayed.loadSavedPath(replacement)
            delayed.runTrace()
            assertEquals(2, sends, "Fresh v2 can explicitly run the restored route")
            assertTrue(current.deletePath(replacement))
        } finally {
            h.container.close()
        }
    }

    @Test
    fun delayedSameRadioUiBindingCannotAcquireTheReplacementGraph() = delayedUiBinding(crossRadio = false)

    @Test
    fun delayedDifferentRadioUiBindingCannotAcquireTheReplacementGraph() = delayedUiBinding(crossRadio = true)

    private fun delayedUiBinding(crossRadio: Boolean) = runTest {
        var replacing = false
        val h = ContainerHarness(this, store, identity = { if (replacing && crossRadio) key(9) else key(7) })
        try {
            h.container.start().join()
            h.settle()
            val target = h.target()
            h.manager.connect(target)
            h.settle()
            h.assertReady()
            val recentValues = mutableMapOf<String, List<String>>()
            val recentWrites = mutableListOf<String>()
            val recents = object : RecentHopsStorage {
                override fun stringList(key: String) = recentValues[key]
                override fun setStringList(key: String, value: List<String>) {
                    recentWrites += key
                    recentValues[key] = value.toList()
                }
            }
            val diagnostics = TraceDiagnostics { _, error -> throw AssertionError(error) }
            val actual = h.container.createToolsFeatureDependencies(
                recents, h.container.createToolsDiagnosticsDependencies(),
            ).trace
            val tokenA = assertNotNull(actual.sessionToken())
            var sends = 0
            val feature = object : TracePathFeatureDependencies by actual {
                override fun traceSender() = TraceSender { _, _, _, _ ->
                    sends += 1
                    MessageSentInfo(0u, Bytes.EMPTY, 0u)
                }
            }
            val oldVersion = h.appState.servicesVersion.value
            val captured = TracePathScreen.Dependencies(feature, recents, diagnostics, tokenA.radioId, oldVersion)
            assertEquals(tokenA, captured.originatingToken)
            val first = ContactDTO(radioId = tokenA.radioId, publicKey = key(0xAA), name = "First",
                typeRawValue = ContactType.REPEATER.rawValue, lastHeardTimestamp = null)
            val second = ContactDTO(radioId = tokenA.radioId, publicKey = key(0xBB), name = "Second",
                typeRawValue = ContactType.REPEATER.rawValue, lastHeardTimestamp = null)
            val third = ContactDTO(radioId = tokenA.radioId, publicKey = key(0xCC), name = "Third",
                typeRawValue = ContactType.REPEATER.rawValue, lastHeardTimestamp = null)
            store.saveContact(first)
            store.saveContact(second)
            store.saveContact(third)
            val editing = TracePathStateHolder(backgroundScope, SystemTraceTimeSource, Copy, recents, diagnostics)
            editing.configure(feature)
            editing.bindConnection(captured.radioId, captured.generation, captured.originatingToken)
            editing.loadContacts(tokenA.radioId)
            editing.appendHop(first)
            assertEquals(1, recentWrites.size, "Fresh A append really persists recents")
            val oldRoute = editing.state.value.outboundPath
            val oldRecents = recentValues.toMap()
            val retainedAppend = { editing.appendHop(second) }
            assertTrue(editing.classifyCodes("CC").single().status is com.meshcoreone.android.feature.tools.trace.HopCodeStatus.WillAdd)
            val retainedCodes = { editing.addCodes("CC") }
            val retainedRecent = { editing.recordRecent(key(0xDD)) }
            h.appState.bumpServicesVersion()
            val reloadVersion = h.appState.servicesVersion.value
            assertEquals(oldVersion + 1, reloadVersion)
            assertNull(actual.originatingSessionToken(oldVersion), "Old reload version cannot acquire a fresh origin")
            assertEquals(tokenA, actual.originatingSessionToken(reloadVersion))
            h.appState.wireServicesIfConnected()
            assertEquals(reloadVersion, h.appState.servicesVersion.value, "Same graph rewiring stays idempotent")
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            assertNull(h.sessions.current, "Direct disconnect withdrew A")
            assertNull(actual.sessionToken(), "The live graph token is already absent before watcher dispatch")
            assertNull(actual.originatingSessionToken(reloadVersion), "Synchronous origin lookup observes withdrawal")
            h.settle()
            assertNull(actual.originatingSessionToken(reloadVersion))
            replacing = true
            h.manager.connect(if (crossRadio) h.target() else target)
            h.settle()
            h.assertReady()
            val tokenB = assertNotNull(actual.sessionToken())
            assertNotEquals(tokenA, tokenB)
            assertEquals(crossRadio, tokenA.radioId != tokenB.radioId)
            assertNull(actual.originatingSessionToken(oldVersion))
            retainedAppend()
            val staleCodes = retainedCodes()
            retainedRecent()
            assertEquals(1, recentWrites.size, "Stale callbacks cannot persist A or B recent preferences")
            assertEquals(oldRoute, editing.state.value.outboundPath)
            assertEquals(oldRecents, recentValues)
            assertTrue(staleCodes.added.isEmpty())
            val pathB = store.createSavedTracePath(tokenB.radioId, "Replacement row", Bytes.of(0xBB), 1, null)
            val dependencies = object : SavedPathsDependencies {
                override fun sessionToken() = actual.sessionToken()
                override fun isOriginatingSessionCurrent(generation: Int, token: SessionToken?) =
                    actual.isOriginatingSessionCurrent(generation, token)
                override fun connectedDevice() = actual.connectedDevice()
                override fun savedPaths() = actual.savedPaths()
            }
            val liveCaptureControl = SavedPathsStateHolder(SavedCopy, diagnostics).apply { configure(dependencies) }
            liveCaptureControl.loadSavedPaths()
            assertTrue(liveCaptureControl.isCurrentPath(pathB), "A fresh live capture demonstrates the old delayed-configure eligibility")
            val saved = SavedPathsStateHolder(SavedCopy, diagnostics).apply {
                configure(dependencies, captured.originatingToken, captured.generation)
            }
            saved.loadSavedPaths()
            assertFalse(saved.isCurrentPath(pathB))
            assertFalse(saved.deletePath(pathB))
            saved.renamePath(pathB, "Stale UI")
            val trace = TracePathStateHolder(backgroundScope, SystemTraceTimeSource, Copy, recents, diagnostics)
            trace.configure(captured.feature)
            trace.bindConnection(captured.radioId, captured.generation, captured.originatingToken)
            trace.loadSavedPath(pathB)
            trace.runTrace()
            assertNull(trace.state.value.activeSavedPath)
            assertFalse(trace.state.value.isRunning)
            assertEquals(0, sends)
            assertEquals("Replacement row", store.fetchSavedTracePath(EntityKey(tokenB.radioId, pathB.id))?.name)
            val fresh = TracePathScreen.Dependencies(
                feature, recents, diagnostics, tokenB.radioId, h.appState.servicesVersion.value,
            )
            assertEquals(tokenB, fresh.originatingToken)
            editing.bindConnection(fresh.radioId, fresh.generation, fresh.originatingToken)
            editing.loadContacts(tokenB.radioId)
            editing.appendHop(second.copy(radioId = tokenB.radioId))
            assertEquals(2, recentWrites.size, "Fresh B append remains writable")
            store.saveContact(third.copy(radioId = tokenB.radioId))
            editing.loadContacts(tokenB.radioId)
            assertEquals(listOf("CC"), editing.addCodes("CC").added)
            assertEquals(3, recentWrites.size, "Fresh B code entry remains writable")
            assertEquals(
                com.meshcoreone.android.feature.tools.trace.RecentHopsStore.defaultsKey(tokenB.radioId),
                recentWrites.last(),
            )
            trace.bindConnection(fresh.radioId, fresh.generation, fresh.originatingToken)
            trace.loadSavedPath(pathB)
            trace.runTrace()
            assertEquals(1, sends, "Explicitly published B binding can run")
            val current = SavedPathsStateHolder(SavedCopy, diagnostics).apply {
                configure(dependencies, fresh.originatingToken, fresh.generation)
            }
            current.loadSavedPaths()
            assertTrue(current.deletePath(pathB))
        } finally {
            h.container.close()
        }
    }

    @Test
    fun reconciledDeviceCannotAuthorizeActionsAgainstThePreviousGraph() = runTest {
        val memory = MemoryConnectionPreferences()
        val armed = AtomicBoolean()
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rebuild = CompletableDeferred<Unit>()
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
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val recents = object : RecentHopsStorage {
                override fun stringList(key: String): List<String>? = null
                override fun setStringList(key: String, value: List<String>) = Unit
            }
            val actual = h.container.createToolsFeatureDependencies(
                recents, h.container.createToolsDiagnosticsDependencies(),
            ).trace
            val token = assertNotNull(actual.sessionToken())
            val version = h.appState.servicesVersion.value
            val device = assertNotNull(actual.connectedDevice())
            val radioB = RadioId(UUID.randomUUID())
            val pathA = store.createSavedTracePath(token.radioId, "A route", Bytes.of(0xAA), 1, null)
            val pathB = store.createSavedTracePath(radioB, "B route", Bytes.of(0xBB), 1, null)
            val diagnostics = TraceDiagnostics { _, error -> throw AssertionError(error) }
            var sends = 0
            var tag = 0u
            val feature = object : TracePathFeatureDependencies by actual {
                override fun traceSender() = TraceSender { sentTag, _, _, _ ->
                    sends += 1
                    tag = sentTag
                    MessageSentInfo(0u, Bytes.EMPTY, 0u)
                }
            }
            fun trace() = TracePathStateHolder(
                backgroundScope, SystemTraceTimeSource, Copy, recents, diagnostics,
            ).apply {
                configure(feature)
                bindConnection(token.radioId, version)
                loadSavedPath(pathA)
            }
            val saving = trace()
            saving.runTrace()
            saving.handleTraceResponse(TraceInfo(tag, 0u, 0u, 0u, emptyList()), token.radioId)
            assertTrue(assertNotNull(saving.state.value.result).success)
            val sending = trace()
            val selecting = trace()
            val sendsBefore = sends
            store.saveDevice(DeviceDTO(radioId = radioB, publicKey = device.publicKey, nodeName = "Ghost B", isActive = false))
            armed.set(true)
            val reconciliation = backgroundScope.async { h.manager.reconcileIdentity(token, rebuild) }
            h.eventually("reconciled live device held before graph rebuild") { reached.isCompleted }
            assertEquals(token, actual.sessionToken(), "Registry still owns A")
            assertEquals(radioB, actual.connectedDevice()?.radioId, "Manager already exposes B")
            assertEquals(version, h.appState.servicesVersion.value)
            assertEquals(radioB, store.fetchDevice(device.id)?.radioId)
            assertFalse(saving.savePath("Must not cross identities"))
            assertFalse(store.fetchSavedTracePaths(radioB).any { it.name == "Must not cross identities" })
            assertFalse(store.fetchSavedTracePaths(token.radioId).any { it.name == "Must not cross identities" })
            sending.runTrace()
            assertEquals(sendsBefore, sends, "No A sender may be correlated to B")
            selecting.loadSavedPath(pathB)
            assertNull(selecting.state.value.activeSavedPath)
            val saved = SavedPathsStateHolder(SavedCopy, diagnostics).apply {
                configure(object : SavedPathsDependencies {
                    override fun sessionToken() = actual.sessionToken()
                    override fun connectedDevice() = actual.connectedDevice()
                    override fun savedPaths() = actual.savedPaths()
                })
            }
            saved.loadSavedPaths()
            assertTrue(saved.state.value.savedPaths.isEmpty(), "New list cannot authorize B with A token")
            assertFalse(saved.isCurrentConnection(radioB))
            saved.renamePath(pathB, "Wrong graph")
            assertFalse(saved.deletePath(pathB))
            assertEquals("B route", store.fetchSavedTracePath(EntityKey(radioB, pathB.id))?.name)
            assertNotNull(store.fetchSavedTracePath(EntityKey(token.radioId, pathA.id)))
            release.complete(Unit)
            h.eventually("identity reconciliation returns") { reconciliation.isCompleted }
            assertEquals(radioB, reconciliation.await())
            assertEquals(token, actual.sessionToken(), "Import handoff still holds rebuild")
        } finally {
            release.complete(Unit)
            h.container.close()
            rebuild.complete(Unit)
        }
    }

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
        val releaseStore = CompletableDeferred<Unit>()
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val holderScope = CoroutineScope(SupervisorJob() + worker)
        try {
            h.container.start().join()
            h.settle()
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
            val holdMutation = AtomicBoolean()
            val mutationReached = CompletableDeferred<Pair<String, RadioId>>()
            val actualStore = assertNotNull(actual.savedPaths())
            val selectedStore = object : TracePathPersisting by actualStore {
                override suspend fun createSavedTracePath(
                    radioId: RadioId, name: String, pathBytes: Bytes, hashSize: Long, initialRun: TracePathRunDTO?,
                ): SavedTracePathDTO {
                    if (holdMutation.compareAndSet(true, false)) {
                        mutationReached.complete("create" to radioId)
                        releaseStore.await()
                    }
                    return actualStore.createSavedTracePath(radioId, name, pathBytes, hashSize, initialRun)
                }

                override suspend fun deleteSavedTracePath(key: EntityKey) {
                    if (holdMutation.compareAndSet(true, false)) {
                        mutationReached.complete("delete" to key.radioId)
                        releaseStore.await()
                    }
                    actualStore.deleteSavedTracePath(key)
                }
            }
            var tag = 0u
            val feature = object : TracePathFeatureDependencies by actual {
                override fun savedPaths() = selectedStore
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
                    override fun isOriginatingSessionCurrent(generation: Int, token: SessionToken?) =
                        feature.isOriginatingSessionCurrent(generation, token)
                    override fun connectedDevice() = feature.connectedDevice()
                    override fun savedPaths() = feature.savedPaths()
                }, oldToken, version)
                saved.loadSavedPaths()
                trace.configure(feature)
                trace.bindConnection(oldToken.radioId, version)
                trace.loadSavedPath(path)
                trace.runTrace()
                trace.handleTraceResponse(TraceInfo(tag, 0u, 0u, 0u, emptyList()), oldToken.radioId)
                assertTrue(assertNotNull(trace.state.value.result).success)
            }
            h.eventually("initial accepted run refresh completes before the persistence gate") {
                trace.state.value.activeSavedPath?.runs?.size == 1
            }
            val accepted = backgroundScope.async(worker) {
                holdMutation.set(true)
                if (crossRadio) trace.savePath("Accepted A result") else saved.deletePath(path)
            }
            h.eventually("A action reached its selected real store after all admission checks") { mutationReached.isCompleted }
            assertEquals(
                (if (crossRadio) "create" else "delete") to oldToken.radioId,
                mutationReached.await(),
                "The intended accepted action targets A's actual store/key",
            )
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
            releaseStore.complete(Unit)
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
            releaseStore.complete(Unit)
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

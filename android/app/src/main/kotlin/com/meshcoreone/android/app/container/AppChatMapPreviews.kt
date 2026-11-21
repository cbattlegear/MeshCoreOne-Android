// AndroidOnly: WP-307 Process-owned native snapshot and network integration; map failures never gate messaging.
package com.meshcoreone.android.app.container

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.PreferenceGuardResult
import com.meshcoreone.android.core.datastore.PreferenceStore
import com.meshcoreone.android.core.datastore.StorageFailure
import com.meshcoreone.android.core.maps.MapLibreSnapshotRenderer
import com.meshcoreone.android.core.maps.MapSnapshotRenderer
import com.meshcoreone.android.core.maps.MapSnapshotRequest
import com.meshcoreone.android.core.maps.MapSnapshotNoLongerWanted
import com.meshcoreone.android.core.maps.MapSnapshotAdmission
import com.meshcoreone.android.core.maps.MapSnapshotStore
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select

internal sealed interface ChatMapNetwork {
    data object Online : ChatMapNetwork
    data object Offline : ChatMapNetwork
    data class Failed(val cause: Exception) : ChatMapNetwork
}

internal enum class ChatMapPreferenceResult { STARTED, REJECTED, STALE }

internal interface ChatMapPreferenceGuard {
    val enabled: Boolean
    fun isCurrent(): Boolean
    fun startIfCurrent(start: () -> Unit): ChatMapPreferenceResult
}

internal suspend fun PreferenceStore.prepareMapThumbnailGuard(): ChatMapPreferenceGuard {
    val guard = prepareGuard(AppStorageKey.showMapPreviewThumbnails)
    return object : ChatMapPreferenceGuard {
        override val enabled = guard.value
        override fun isCurrent() = guard.isCurrent()
        override fun startIfCurrent(start: () -> Unit): ChatMapPreferenceResult =
            when (guard.runIfCurrent({ it }, start)) {
                PreferenceGuardResult.STARTED -> ChatMapPreferenceResult.STARTED
                PreferenceGuardResult.REJECTED -> ChatMapPreferenceResult.REJECTED
                PreferenceGuardResult.STALE -> ChatMapPreferenceResult.STALE
            }
    }
}

private object AlwaysEnabledMapPreference : ChatMapPreferenceGuard {
    override val enabled = true
    override fun isCurrent() = true
    override fun startIfCurrent(start: () -> Unit): ChatMapPreferenceResult {
        start()
        return ChatMapPreferenceResult.STARTED
    }
}

private class StaleMapPreference : CancellationException("Map thumbnail preference changed")
private class RejectedMapPreference : CancellationException("Map thumbnails are disabled")
private enum class MapAdmissionResult { STARTED, POLICY_REJECTED, PREFERENCE_DISABLED }

internal class AppChatMapPreviews(
    context: Context,
    scope: CoroutineScope,
    renderer: MapSnapshotRenderer<Bitmap> = nativeRenderer(context),
    private val online: () -> Boolean = {
        val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
        manager.activeNetwork?.let(manager::getNetworkCapabilities)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
    },
    private val preparePreference: suspend () -> ChatMapPreferenceGuard = { AlwaysEnabledMapPreference },
    val preferenceRevision: StateFlow<Long> = MutableStateFlow(0L),
) : AutoCloseable {
    private val logger = Logger.getLogger("com.meshcoreone.android.ChatMapPreviews")
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val mutableNetwork = MutableStateFlow<ChatMapNetwork>(ChatMapNetwork.Offline)
    val network: StateFlow<ChatMapNetwork> = mutableNetwork.asStateFlow()
    @Volatile private var closed = false
    private var registered = false

    val snapshots = MapSnapshotStore(object : MapSnapshotRenderer<Bitmap> {
        override suspend fun render(request: MapSnapshotRequest): Bitmap? = render(request) { true }

        override suspend fun render(request: MapSnapshotRequest, isWanted: () -> Boolean): Bitmap? =
            render(request, isWanted) { validate, start ->
                if (isWanted() && validate()) { start(); true } else false
            }

        override suspend fun render(
            request: MapSnapshotRequest,
            isWanted: () -> Boolean,
            admit: MapSnapshotAdmission,
        ): Bitmap? {
            try {
                while (true) {
                    try {
                        val revision = preferenceRevision.value
                        var preference = preparePreference()
                        while (!preference.isCurrent()) preference = preparePreference()
                        if (!preference.enabled) {
                            logger.fine("Chat map thumbnail request declined by privacy preference")
                            if (!isWanted()) throw MapSnapshotNoLongerWanted()
                            awaitPreferenceChange(revision)
                            continue
                        }
                        if (!isWanted()) throw MapSnapshotNoLongerWanted()
                        return renderWithDeadline(renderer, request, isWanted, admit).also {
                            if (it == null) logger.warning("Chat map snapshot unavailable or timed out")
                        }
                    } catch (failure: StorageFailure) {
                        reportPreferenceFailure(failure)
                        if (!isWanted()) throw MapSnapshotNoLongerWanted()
                        delay(1_000)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                logger.log(Level.WARNING, "Chat map snapshot failed", problem)
                return null
            } catch (problem: LinkageError) {
                logger.log(Level.WARNING, "Native chat map renderer is unavailable", problem)
                return null
            }
        }
    }, scope, { it.allocationByteCount.toLong() })

    private suspend fun renderWithDeadline(
        renderer: MapSnapshotRenderer<Bitmap>,
        request: MapSnapshotRequest,
        isWanted: () -> Boolean,
        admit: MapSnapshotAdmission,
    ): Bitmap? = coroutineScope {
        val started = CompletableDeferred<Unit>()
        val render = async {
            renderer.render(request, isWanted) { validate, start ->
                admitForRender(admit, isWanted, validate) {
                    start()
                    started.complete(Unit)
                }
            }
        }
        val deadline = async {
            started.await()
            delay(30_000)
        }
        try {
            select {
                render.onAwait { it }
                deadline.onAwait { null }
            }
        } finally {
            render.cancel()
            deadline.cancel()
        }
    }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNetwork()
        override fun onLost(network: Network) = refreshNetwork()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refreshNetwork()
    }

    init {
        refreshNetwork()
        try {
            requireNotNull(connectivity).registerDefaultNetworkCallback(callback)
            registered = true
        } catch (problem: Exception) {
            logger.log(Level.WARNING, "Chat map network observation failed", problem)
            mutableNetwork.value = ChatMapNetwork.Failed(problem)
        }
    }

    fun refreshNetwork() {
        if (closed) return
        val next = try {
            if (online()) ChatMapNetwork.Online else ChatMapNetwork.Offline
        } catch (problem: Exception) {
            logger.log(Level.WARNING, "Chat map network availability failed", problem)
            ChatMapNetwork.Failed(problem)
        }
        val previous = mutableNetwork.value
        mutableNetwork.value = next
        if (next == ChatMapNetwork.Online && previous != ChatMapNetwork.Online) snapshots.clearFailures()
    }

    fun request(request: MapSnapshotRequest) {
        check(!closed) { "Chat map previews are closed" }
        snapshots.request(request)
    }

    fun request(request: MapSnapshotRequest, owner: Any, isWanted: () -> Boolean) {
        check(!closed) { "Chat map previews are closed" }
        snapshots.request(request, owner, isWanted)
    }

    fun request(request: MapSnapshotRequest, owner: Any, isWanted: () -> Boolean, admit: MapSnapshotAdmission) {
        check(!closed) { "Chat map previews are closed" }
        snapshots.request(request, owner, isWanted, admit)
    }

    suspend fun admitIfEnabled(
        admission: MapSnapshotAdmission,
        isWanted: () -> Boolean,
        validate: suspend () -> Boolean,
        start: () -> Unit,
    ): Boolean = admitIfEnabledResult(admission, isWanted, validate, start) == MapAdmissionResult.STARTED

    private suspend fun admitForRender(
        admission: MapSnapshotAdmission,
        isWanted: () -> Boolean,
        validate: suspend () -> Boolean,
        start: () -> Unit,
    ): Boolean {
        while (isWanted()) {
            val revision = preferenceRevision.value
            when (admitIfEnabledResult(admission, isWanted, validate, start)) {
                MapAdmissionResult.STARTED -> return true
                MapAdmissionResult.POLICY_REJECTED -> return false
                MapAdmissionResult.PREFERENCE_DISABLED -> awaitPreferenceChange(revision)
            }
        }
        return false
    }

    private suspend fun awaitPreferenceChange(revision: Long) {
        withTimeoutOrNull(1_000) { preferenceRevision.first { it != revision } }
    }

    private suspend fun admitIfEnabledResult(
        admission: MapSnapshotAdmission,
        isWanted: () -> Boolean,
        validate: suspend () -> Boolean,
        start: () -> Unit,
    ): MapAdmissionResult {
        while (isWanted()) {
            val preference = preparePreference()
            if (!preference.enabled) {
                if (!preference.isCurrent()) continue
                return MapAdmissionResult.PREFERENCE_DISABLED
            }
            val admitted = try {
                admission({ preference.isCurrent() && validate() }) {
                    when (preference.startIfCurrent(start)) {
                        ChatMapPreferenceResult.STARTED -> Unit
                        ChatMapPreferenceResult.REJECTED -> throw RejectedMapPreference()
                        ChatMapPreferenceResult.STALE -> throw StaleMapPreference()
                    }
                }
            } catch (_: StaleMapPreference) {
                continue
            } catch (_: RejectedMapPreference) {
                return MapAdmissionResult.PREFERENCE_DISABLED
            }
            if (!admitted && !preference.isCurrent()) continue
            return if (admitted) MapAdmissionResult.STARTED else MapAdmissionResult.POLICY_REJECTED
        }
        return MapAdmissionResult.POLICY_REJECTED
    }

    fun reportPreferenceFailure(failure: StorageFailure) {
        logger.log(Level.WARNING, "Chat map thumbnail preference could not be read", failure)
    }

    fun release(owner: Any) = snapshots.release(owner)

    fun retry(request: MapSnapshotRequest) {
        check(!closed) { "Chat map previews are closed" }
        refreshNetwork()
        snapshots.retry(request)
        snapshots.request(request)
    }

    override fun close() {
        if (closed) return
        closed = true
        if (registered) {
            try {
                requireNotNull(connectivity).unregisterNetworkCallback(callback)
            } catch (problem: Exception) {
                logger.log(Level.WARNING, "Chat map network observation cleanup failed", problem)
            }
        }
        snapshots.close()
    }

    private companion object {
        fun nativeRenderer(context: Context): MapSnapshotRenderer<Bitmap> {
            val density = context.resources.displayMetrics.density
            val renderer = lazy {
                MapLibreSnapshotRenderer(context, (250 * density).roundToInt(), (150 * density).roundToInt())
            }
            return object : MapSnapshotRenderer<Bitmap> {
                override suspend fun render(request: MapSnapshotRequest): Bitmap? =
                    withContext(Dispatchers.Main.immediate) { renderer.value.render(request) }

                override suspend fun render(
                    request: MapSnapshotRequest,
                    isWanted: () -> Boolean,
                    admit: MapSnapshotAdmission,
                ): Bitmap? = withContext(Dispatchers.Main.immediate) {
                    renderer.value.render(request, isWanted, admit)
                }
            }
        }
    }
}

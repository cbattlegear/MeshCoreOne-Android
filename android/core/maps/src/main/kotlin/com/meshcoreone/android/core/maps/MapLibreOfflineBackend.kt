// AndroidOnly: WP-312 MapLibre OfflineManager region lifecycle for an admitted style endpoint.
package com.meshcoreone.android.core.maps

import android.content.Context
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

class MapLibreOfflineBackend internal constructor(
    private val styleUri: String,
    private val scope: CoroutineScope,
    private val manager: MapLibreOfflineRegions,
) : OfflineMapBackend {
    constructor(context: Context, styleUri: String, scope: CoroutineScope) :
        this(styleUri, scope, NativeMapLibreOfflineRegions(context))

    private val regions = mutableMapOf<UUID, MapLibreOfflineRegion>()
    private val _packs = MutableStateFlow<List<OfflinePack>>(emptyList())
    override val packs: StateFlow<List<OfflinePack>> = _packs.asStateFlow()
    override val downloadError: OfflineMapError? get() = OpenFreeMapOfflineSuspension.errorFor(styleUri)
    override fun resumeError(packId: UUID): OfflineMapError? {
        val region = synchronized(regions) { regions[packId] } ?: return downloadError
        return OpenFreeMapOfflineSuspension.errorFor(region.styleUri)
    }

    init {
        require(styleUri.startsWith("https://") || styleUri.startsWith("http://") || styleUri.startsWith("file://"))
        scope.launch { refresh() }
    }

    override suspend fun createAndStart(request: OfflinePackRequest) {
        downloadError?.let { throw it }
        val region = manager.create(request, styleUri, request.toMetadata())
        synchronized(regions) { regions[request.id] = region }
        observe(request, region)
        publish(request.toPack(OfflinePackState.DOWNLOADING, 0f))
        region.setActive(true)
    }

    override suspend fun delete(packId: UUID) {
        val region = synchronized(regions) { regions[packId] } ?: return
        region.delete()
        synchronized(regions) { regions.remove(packId) }
        _packs.update { current -> current.filterNot { it.id == packId } }
    }

    override suspend fun pause(packId: UUID) {
        val region = synchronized(regions) { regions[packId] } ?: return
        region.setActive(false)
        mutate(packId) { if (it.state == OfflinePackState.COMPLETE) it else it.copy(state = OfflinePackState.PAUSED) }
    }

    override suspend fun resume(packId: UUID) {
        resumeError(packId)?.let { throw it }
        val region = synchronized(regions) { regions[packId] } ?: return
        region.setActive(true)
        mutate(packId) { it.copy(state = OfflinePackState.DOWNLOADING) }
    }

    private suspend fun refresh() {
        val listed = manager.list()
        val decoded = listed.mapNotNull { region ->
            // Quarantine provider regions even if their app metadata is old or unreadable.
            if (OpenFreeMapOfflineSuspension.appliesTo(region.styleUri)) region.setActive(false)
            decodeRequest(region.metadata)?.also { request ->
                synchronized(regions) { regions[request.id] = region }
                observe(request, region)
            }?.let { request -> request to region }
        }
        val initial = decoded.map { (request, region) ->
            val status = region.status()
            request.toPack(status.toPackState(region), status.fraction)
        }
        _packs.value = initial.sortedBy { it.createdAt }
    }

    private fun observe(request: OfflinePackRequest, region: MapLibreOfflineRegion) {
        region.observe(
            onProgress = { status ->
                if (status.active && OpenFreeMapOfflineSuspension.appliesTo(region.styleUri)) region.setActive(false)
                publish(request.toPack(status.toPackState(region), status.fraction))
            },
            onFailure = {
                mutate(request.id) {
                    when {
                        it.state == OfflinePackState.COMPLETE -> it
                        OpenFreeMapOfflineSuspension.appliesTo(region.styleUri) -> it.copy(state = OfflinePackState.PAUSED)
                        else -> it.copy(state = OfflinePackState.FAILED)
                    }
                }
            },
        )
    }

    private fun publish(pack: OfflinePack) {
        _packs.update { current -> (current.filterNot { it.id == pack.id } + pack).sortedBy { it.createdAt } }
    }

    private fun mutate(id: UUID, transform: (OfflinePack) -> OfflinePack) {
        _packs.update { current -> current.map { if (it.id == id) transform(it) else it } }
    }

    private fun OfflineRegionProgress.toPackState(region: MapLibreOfflineRegion): OfflinePackState = when {
        complete -> OfflinePackState.COMPLETE
        OpenFreeMapOfflineSuspension.appliesTo(region.styleUri) -> OfflinePackState.PAUSED
        active -> OfflinePackState.DOWNLOADING
        else -> OfflinePackState.PAUSED
    }

    private fun OfflinePackRequest.toMetadata(): ByteArray = JSONObject()
        .put("schema", 1)
        .put("id", id.toString())
        .put("name", name)
        .put("layer", layer.name)
        .put("north", bounds.northeast.latitude)
        .put("east", bounds.northeast.longitude)
        .put("south", bounds.southwest.latitude)
        .put("west", bounds.southwest.longitude)
        .put("minZoom", minZoom)
        .put("maxZoom", maxZoom)
        .put("createdAt", createdAt.epochSecond)
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun OfflinePackRequest.toPack(state: OfflinePackState, fraction: Float) =
        OfflinePack(id, name, layer, bounds, minZoom, maxZoom, createdAt, state, fraction)

    private companion object {
        fun decodeRequest(bytes: ByteArray): OfflinePackRequest? = runCatching {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.getInt("schema") == 1)
            OfflinePackRequest(
                id = UUID.fromString(json.getString("id")),
                name = json.getString("name"),
                layer = OfflineLayer.valueOf(json.getString("layer")),
                bounds = GeoBounds(
                    southwest = GeoPoint(json.getDouble("south"), json.getDouble("west")),
                    northeast = GeoPoint(json.getDouble("north"), json.getDouble("east")),
                ),
                minZoom = json.getInt("minZoom"),
                maxZoom = json.getInt("maxZoom"),
                createdAt = Instant.ofEpochSecond(json.getLong("createdAt")),
            )
        }.getOrNull()
    }
}

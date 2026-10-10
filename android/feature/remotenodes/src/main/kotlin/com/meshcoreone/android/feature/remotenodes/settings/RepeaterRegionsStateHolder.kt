// PortedFrom: MC1/Views/RemoteNodes/Repeaters/RepeaterSettingsViewModel+Regions.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.settings

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.isAtLeast
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeFaultClassifier
import com.meshcoreone.android.feature.remotenodes.settings.RepeaterRegionParsing.DEFAULT_SCOPE_SET_REPLY_MARKER
import com.meshcoreone.android.feature.remotenodes.settings.RepeaterRegionParsing.FIRMWARE_NULL_TOKEN
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Region section state of the repeater settings screen. */
data class RepeaterRegionsState(
    val regions: List<RepeaterRegionEntry> = emptyList(),
    /** Last successfully parsed tree; null until loaded, and again after an unparseable refetch. */
    val originalRegions: List<RepeaterRegionEntry>? = null,
    val isLoadingRegions: Boolean = false,
    val regionsError: Boolean = false,
    val hasUnsavedRegionChanges: Boolean = false,
    val regionsSaveSuccess: Boolean = false,
    /** Null when unset. Scopes flood traffic this node originates, not which regions it repeats. */
    val defaultScopeName: String? = null,
    /** False until a `region default` reply parses; distinct from `defaultScopeName == null`. */
    val defaultScopeLoaded: Boolean = false,
    val isLoadingDefaultScope: Boolean = false,
    val isRegionsExpanded: Boolean = false,
) {
    val regionsLoaded: Boolean get() = originalRegions != null
}

/** `addRegion` rejected before or by the node (Swift `AddRegionError.rejected`). */
class AddRegionRejectedException : Exception("Region was not added")

/**
 * Region management for a repeater (Swift `RepeaterSettingsViewModel+Regions`), sharing the settings
 * helper's CLI transport and its `isApplying` / `errorMessage` state.
 */
class RepeaterRegionsStateHolder(
    private val helper: NodeSettingsStateHolder,
    private val faults: RemoteNodeFaultClassifier,
) {
    private val _state = MutableStateFlow(RepeaterRegionsState())
    val state: StateFlow<RepeaterRegionsState> = _state.asStateFlow()

    private fun update(transform: (RepeaterRegionsState) -> RepeaterRegionsState) = _state.update(transform)

    /** `region default` exists on MeshCore repeater v1.15.0+; an unknown version is unsupported. */
    val supportsRegionDefaultScope: Boolean
        get() = helper.state.value.firmwareVersion?.isAtLeast(REGION_DEFAULT_MIN_MAJOR, REGION_DEFAULT_MIN_MINOR) == true

    fun setRegions(regions: List<RepeaterRegionEntry>) = update { it.copy(regions = regions) }
    fun setDefaultScopeName(name: String?) = update { it.copy(defaultScopeName = name) }
    fun setRegionsExpanded(expanded: Boolean) = update { it.copy(isRegionsExpanded = expanded) }

    private val applying: Boolean get() = helper.state.value.isApplying
    private fun setApplying(value: Boolean) = helper.update { it.copy(isApplying = value) }
    private fun setError(text: RemoteNodesText?) = helper.update { it.copy(errorMessage = text) }

    suspend fun fetchRegions() {
        update { it.copy(isLoadingRegions = true, regionsError = false) }
        try {
            val parsed = RepeaterRegionParsing.parseRegionTree(helper.sendAndWait("region", REGION_TIMEOUT, rawMatching = true))
            if (parsed.isEmpty()) {
                // Unload but keep the visible rows and unsaved flag: a truncated dump must not wipe edits.
                update { it.copy(originalRegions = null, regionsError = true) }
            } else {
                update { it.copy(regions = parsed, originalRegions = parsed) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            update { it.copy(regionsError = true) }
            setError(RemoteNodesText.Failure(error))
        } finally {
            update { it.copy(isLoadingRegions = false) }
        }
    }

    suspend fun fetchDefaultScope() {
        if (!supportsRegionDefaultScope || _state.value.isLoadingDefaultScope) return
        update { it.copy(isLoadingDefaultScope = true) }
        try {
            val reply = helper.sendAndWait("region default", REGION_TIMEOUT, rawMatching = true)
            val parsed = RepeaterRegionParsing.parseDefaultScopeReply(reply)
            if (parsed != null) applyParsedDefaultScope(parsed)
            else setError(RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsFailedToLoad))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            setError(RemoteNodesText.Failure(error))
        } finally {
            update { it.copy(isLoadingDefaultScope = false) }
        }
    }

    private fun applyParsedDefaultScope(parsed: RepeaterRegionParsing.ParsedDefaultScope) = update {
        it.copy(
            defaultScopeName = (parsed as? RepeaterRegionParsing.ParsedDefaultScope.Named)?.name,
            defaultScopeLoaded = true,
        )
    }

    suspend fun toggleRegionFlood(name: String) {
        if (!_state.value.regionsLoaded || applying) return
        val entry = _state.value.regions.firstOrNull { it.name == name } ?: return
        val currentlyAllowed = entry.floodAllowed
        val command = if (currentlyAllowed) "region denyf $name" else "region allowf $name"
        setApplying(true)
        setError(null)
        helper.runApplying {
            if (helper.isOk(helper.sendAndWait(command))) {
                // Re-find by name: the list may have changed during the await.
                update { current ->
                    if (current.regions.none { it.name == name }) {
                        current
                    } else {
                        current.copy(
                            regions = current.regions.replaceFirst(name) { it.copy(floodAllowed = !currentlyAllowed) },
                            hasUnsavedRegionChanges = true,
                        )
                    }
                }
            } else {
                setError(RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsUnknownRegion))
            }
        }
        setApplying(false)
    }

    suspend fun setDefaultScope(name: String?) {
        if (!supportsRegionDefaultScope || !_state.value.regionsLoaded || applying) return
        if (name == RepeaterRegionEntry.UNSCOPED_NAME) return
        if (name == _state.value.defaultScopeName) return
        val command = "region default ${name ?: FIRMWARE_NULL_TOKEN}"
        setApplying(true)
        setError(null)
        helper.runApplying {
            if (helper.sendAndWait(command, rawMatching = true).contains(DEFAULT_SCOPE_SET_REPLY_MARKER)) {
                update { current ->
                    val regions = if (name != null) {
                        current.regions.replaceFirst(name) { it.copy(floodAllowed = true) }
                    } else {
                        current.regions
                    }
                    current.copy(defaultScopeName = name, defaultScopeLoaded = true, regions = regions)
                }
            } else {
                setError(RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsUnknownRegion))
            }
        }
        setApplying(false)
    }

    /** Adds a region; throws [AddRegionRejectedException] or the transport failure. */
    suspend fun addRegion(name: String, parent: RepeaterRegionEntry.Parent) {
        if (!_state.value.regionsLoaded || applying) throw AddRegionRejectedException()
        val trimmed = com.meshcoreone.android.feature.remotenodes.cli.RemoteSwiftText.trimWhitespaces(name)
        when (RegionNameValidator.validate(trimmed, _state.value.regions.map { it.name })) {
            null -> Unit
            RegionNameValidator.ValidationError.Empty -> throw AddRegionRejectedException()
            else -> {
                setError(addFailed())
                throw AddRegionRejectedException()
            }
        }
        val (command, parentName) = when (parent) {
            RepeaterRegionEntry.Parent.Unscoped -> "region put $trimmed" to RepeaterRegionEntry.UNSCOPED_NAME
            is RepeaterRegionEntry.Parent.Named -> {
                if (_state.value.regions.none { it.name == parent.name }) {
                    setError(addFailed())
                    throw AddRegionRejectedException()
                }
                "region put $trimmed ${parent.name}" to parent.name
            }
        }
        setApplying(true)
        setError(null)
        val response = try {
            helper.sendAndWait(command)
        } catch (error: CancellationException) {
            setApplying(false)
            throw error
        } catch (error: Exception) {
            setError(helper.userFacing(error))
            setApplying(false)
            throw error
        }
        if (helper.isOk(response)) {
            update { it.copy(regions = inserting(it.regions, trimmed, parentName), hasUnsavedRegionChanges = true) }
            setApplying(false)
            return
        }
        setError(addFailed())
        setApplying(false)
        throw AddRegionRejectedException()
    }

    /** Inserts after the live parent's subtree (re-found after the await), else appends at depth 1. */
    private fun inserting(regions: List<RepeaterRegionEntry>, name: String, parentName: String): List<RepeaterRegionEntry> {
        val parentIndex = regions.indexOfFirst { it.name == parentName }
        val depth = if (parentIndex >= 0) regions[parentIndex].depth + 1 else 1
        val entry = RepeaterRegionEntry(name, parentName, depth, floodAllowed = true, isHome = false)
        if (parentIndex < 0) return regions + entry
        val parentDepth = regions[parentIndex].depth
        var insertIndex = parentIndex + 1
        while (insertIndex < regions.size && regions[insertIndex].depth > parentDepth) insertIndex++
        return regions.subList(0, insertIndex) + entry + regions.subList(insertIndex, regions.size)
    }

    suspend fun removeRegion(name: String) {
        if (!_state.value.regionsLoaded || applying) return
        setApplying(true)
        setError(null)
        val wasDefault = _state.value.defaultScopeName == name
        helper.runApplying {
            val response = helper.sendAndWait("region remove $name")
            when {
                helper.isOk(response) -> {
                    update { current -> current.copy(regions = current.regions.filter { it.name != name }, hasUnsavedRegionChanges = true) }
                    if (wasDefault && supportsRegionDefaultScope) {
                        val clearReply = helper.sendAndWait("region default $FIRMWARE_NULL_TOKEN", rawMatching = true)
                        if (clearReply.contains(DEFAULT_SCOPE_SET_REPLY_MARKER)) {
                            update { it.copy(defaultScopeName = null) }
                        } else {
                            setError(RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsUnknownRegion))
                        }
                    }
                }
                response.contains("not empty") ->
                    setError(RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsNotEmpty))
                else -> setError(RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsRemoveFailed))
            }
        }
        setApplying(false)
    }

    suspend fun saveRegions() {
        if (applying) return
        setApplying(true)
        setError(null)
        val flashed = helper.runApplying {
            if (helper.isOk(helper.sendAndWait("region save"))) {
                update { it.copy(hasUnsavedRegionChanges = false) }
                helper.flashSuccess(::setApplying) { v -> update { it.copy(regionsSaveSuccess = v) } }
                true
            } else {
                setError(RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsSaveFailed))
                false
            }
        }
        if (flashed != true) setApplying(false)
    }

    private fun addFailed(): RemoteNodesText = RemoteNodesText.resource(AppRemoteNodesStrings.remoteNodesSettingsRegionsAddFailed)

    private fun List<RepeaterRegionEntry>.replaceFirst(
        name: String,
        transform: (RepeaterRegionEntry) -> RepeaterRegionEntry,
    ): List<RepeaterRegionEntry> {
        val index = indexOfFirst { it.name == name }
        if (index < 0) return this
        return mapIndexed { position, entry -> if (position == index) transform(entry) else entry }
    }

    private companion object {
        const val REGION_DEFAULT_MIN_MAJOR = 1L
        const val REGION_DEFAULT_MIN_MINOR = 15L
        val REGION_TIMEOUT = 10.seconds
    }
}

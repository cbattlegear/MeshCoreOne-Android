// PortedFrom: MC1/Views/PathEditing/PathManagementViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/PathEditing/PathEditingSheet.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.path

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RepeaterResolvable
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PathManagementState(
    val isDiscovering: Boolean = false,
    val isSettingPath: Boolean = false,
    val discoveryResult: PathDiscoveryResult? = null,
    val showDiscoveryResult: Boolean = false,
    val errorMessage: NodesMessage? = null,
    val showingPathEditor: Boolean = false,
    /** Drives the Add Hop picker presentation. */
    val insertionIntent: AddHopIntent? = null,
    val editablePath: List<PathHop> = emptyList(),
    /** LRU of recently inserted public keys, most recent first, radio-scoped. */
    val recentPublicKeys: List<Bytes> = emptyList(),
    val availableRepeaters: List<ContactDTO> = emptyList(),
    val availableRooms: List<ContactDTO> = emptyList(),
    /** All contacts, for name resolution. */
    val allContacts: List<ContactDTO> = emptyList(),
    val discoveredNodes: List<DiscoveredNodeDTO> = emptyList(),
    val discoverySecondsRemaining: Int? = null,
)

/**
 * Path discovery and editing for one contact (`PathManagementViewModel`). Main-confined; [scope]
 * hosts the discovery and countdown work. Providers are re-read at every use.
 */
class PathManagementStateHolder(
    private val dependencies: NodesFeatureDependencies,
    scope: CoroutineScope,
) : HopPickerSource {
    private val mutableState = MutableStateFlow(PathManagementState())
    val state: StateFlow<PathManagementState> = mutableState.asStateFlow()
    private val recents = RecentHopsStore(dependencies.preferences)
    private val discovery = PathDiscoveryRunner(dependencies, scope, mutableState) { onContactNeedsRefresh?.invoke() }

    /** Captured radio scope for recents persistence. */
    private var currentRadioId: RadioId? = null

    /** Called when the contact should be refreshed (discovery response, path saved or reset). */
    var onContactNeedsRefresh: (() -> Unit)? = null

    private val session get() = dependencies.session

    /** Test/preview seam for the Swift model's publicly settable properties. */
    internal fun seed(transform: (PathManagementState) -> PathManagementState) = mutableState.update(transform)

    fun setInsertionIntent(intent: AddHopIntent?) = mutableState.update { it.copy(insertionIntent = intent) }
    fun setShowingPathEditor(showing: Boolean) = mutableState.update { it.copy(showingPathEditor = showing) }
    fun dismissDiscoveryResult() = mutableState.update { it.copy(showDiscoveryResult = false) }
    fun clearError() = mutableState.update { it.copy(errorMessage = null) }

    /** Device hash size (1 to 3 bytes per hop); a reserved firmware mode is clamped. */
    val hashSize: Int get() = (session.connectedDevice()?.hashSize ?: 1L).coerceIn(1L, 3L).toInt()

    /** Hop cap under the current hash size; past it the firmware silently truncates. */
    val maxHopCount: Int get() = PathEditing.maxHopCount(hashSize)

    // MARK: Name resolution

    /** The unique contact match's name, else the unique discovered match's (only when no contact matched). */
    fun resolveHashToName(hashBytes: Bytes): String? = resolve(hashBytes)?.resolvableName

    /** The unique match's full public key, under the same rule as [resolveHashToName]. */
    fun resolveHashToPublicKey(hashBytes: Bytes): Bytes? = resolve(hashBytes)?.publicKey

    private fun resolve(hashBytes: Bytes): RepeaterResolvable? {
        val current = state.value
        val matches = current.allContacts.filter { it.publicKey.prefix(hashBytes.size) == hashBytes }
        if (matches.size == 1) return matches[0]
        // Ambiguity among contacts never falls through: an ambiguous contact could be the target.
        if (matches.isEmpty()) {
            val discovered = current.discoveredNodes.filter { it.publicKey.prefix(hashBytes.size) == hashBytes }
            if (discovered.size == 1) return discovered[0]
        }
        return null
    }

    fun createPathHop(hashBytes: Bytes): PathHop = PathHop(hashBytes, resolveHashToPublicKey(hashBytes), resolveHashToName(hashBytes))

    /** Loads contacts for resolution and the repeaters/rooms to add; always refreshes the radio's recents. */
    suspend fun loadContacts(radioId: RadioId, forceReload: Boolean = false) {
        val dataStore = session.servicesDataStore() ?: return
        loadRecentKeys(radioId)
        if (!forceReload && state.value.allContacts.isNotEmpty()) return
        try {
            val contacts = dataStore.fetchContacts(radioId)
            mutableState.update {
                it.copy(
                    allContacts = contacts,
                    availableRepeaters = contacts.filter { contact -> contact.type == ContactType.REPEATER },
                    availableRooms = contacts.filter { contact -> contact.type == ContactType.ROOM },
                )
            }
            val nodes = dataStore.fetchDiscoveredNodes(radioId)
            mutableState.update { it.copy(discoveredNodes = nodes) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update {
                it.copy(allContacts = emptyList(), availableRepeaters = emptyList(), availableRooms = emptyList(), discoveredNodes = emptyList())
            }
        }
    }

    /**
     * Initializes the editor from the contact's stored path, normalized to the device hash size, and
     * clears a stale insertion intent so a previous presentation cannot auto-open the picker.
     */
    fun initializeEditablePath(contact: ContactDTO) {
        val byteLength = contact.pathByteLength.toInt()
        val storedHashSize = contact.pathHashSize.toInt()
        val pathData = contact.outPath.prefix(byteLength)
        val target = hashSize
        val hops = (0 until pathData.size step storedHashSize).map { start ->
            val bytes = pathData.slice(start, minOf(start + storedHashSize, pathData.size))
            PathEditing.normalizeHop(createPathHop(bytes), target)
        }
        mutableState.update { it.copy(insertionIntent = null, editablePath = hops) }
    }

    /** Appends a node (no-op when full) and records it as recent; the picker stays open for multi-add. */
    fun insert(node: RepeaterResolvable, intent: AddHopIntent) {
        if (isPathFull) return
        val hop = PathHop(node.publicKey.prefix(hashSize), node.publicKey, node.resolvableName)
        when (intent) {
            AddHopIntent.APPEND -> mutableState.update { it.copy(editablePath = it.editablePath + hop) }
        }
        recordRecent(node.publicKey)
    }

    fun loadRecentKeys(radioId: RadioId) {
        currentRadioId = radioId
        mutableState.update { it.copy(recentPublicKeys = recents.load(radioId)) }
    }

    fun recordRecent(publicKey: Bytes) {
        val radioId = currentRadioId ?: return
        mutableState.update { it.copy(recentPublicKeys = recents.record(publicKey, it.recentPublicKeys, radioId)) }
    }

    fun removeRepeater(index: Int) = mutableState.update {
        if (index in it.editablePath.indices) it.copy(editablePath = it.editablePath.filterIndexed { position, _ -> position != index }) else it
    }

    fun moveRepeater(fromOffsets: Set<Int>, toOffset: Int) =
        mutableState.update { it.copy(editablePath = PathEditing.move(it.editablePath, fromOffsets, toOffset)) }

    /** Saves the edited path re-encoded at the current hash size, unless [PathEditing.saveRejection] refuses. */
    suspend fun saveEditedPath(contact: ContactDTO) {
        val contactService = session.contactService() ?: return
        mutableState.update { it.copy(errorMessage = null) }
        val target = hashSize
        val hops = state.value.editablePath
        PathEditing.saveRejection(hops, target, maxHopCount)?.let { rejection ->
            mutableState.update { it.copy(errorMessage = rejection) }
            return
        }
        settingPath(R.string.l10n_app_contacts_contacts_pathmanagement_error_savefailed) {
            val encoded = PathEditing.encodeEditablePath(hops, target)
            contactService.setPath(contact.radioId, contact.publicKey, encoded.path, encoded.length)
        }
    }

    /** Resets the path (force flood routing). */
    suspend fun resetPath(contact: ContactDTO) {
        val contactService = session.contactService() ?: return
        mutableState.update { it.copy(errorMessage = null) }
        settingPath(R.string.l10n_app_contacts_contacts_pathmanagement_error_resetfailed) {
            contactService.resetPath(contact.radioId, contact.publicKey)
        }
    }

    /** Sets a specific path. */
    suspend fun setPath(contact: ContactDTO, path: Bytes, pathLength: UByte) {
        val contactService = session.contactService() ?: return
        mutableState.update { it.copy(errorMessage = null) }
        settingPath(R.string.l10n_app_contacts_contacts_pathmanagement_error_setfailed) {
            contactService.setPath(contact.radioId, contact.publicKey, path, pathLength)
        }
    }

    /** Confirmed direct (zero-hop) routing from the editor; true when it succeeded and the sheet closes. */
    suspend fun confirmDirectRouting(contact: ContactDTO): Boolean {
        setPath(contact, Bytes.EMPTY, DIRECT_ROUTING_PATH_LENGTH)
        return state.value.errorMessage == null
    }

    /** Confirmed flood routing from the editor; true when it succeeded and the sheet closes. */
    suspend fun confirmFloodRouting(contact: ContactDTO): Boolean {
        resetPath(contact)
        return state.value.errorMessage == null
    }

    /** Save from the editor; true when it succeeded and the sheet closes. */
    suspend fun saveFromEditor(contact: ContactDTO): Boolean {
        saveEditedPath(contact)
        return state.value.errorMessage == null
    }

    private suspend fun settingPath(failureRes: Int, command: suspend () -> Unit) {
        mutableState.update { it.copy(isSettingPath = true) }
        try {
            command()
            onContactNeedsRefresh?.invoke()
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(isSettingPath = false) }
            throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(errorMessage = NodesMessage.res(failureRes, dependencies.messages.message(error))) }
        }
        mutableState.update { it.copy(isSettingPath = false) }
    }

    // MARK: Discovery

    /** Starts path discovery; a timeout is terminal and a late response never flips it to success. */
    fun discoverPath(contact: ContactDTO) {
        val contactService = session.contactService() ?: return
        discovery.start(contact, contactService)
    }

    fun cancelDiscovery() = discovery.cancel()

    /** Resolves an in-flight discovery from a push; ignored after timeout or cancel. Null hop count = no valid path. */
    fun handleDiscoveryResponse(hopCount: Int?) = discovery.handleResponse(hopCount)

    // MARK: HopPickerSource

    override val availableRepeaters: List<ContactDTO> get() = state.value.availableRepeaters
    override val availableRooms: List<ContactDTO> get() = state.value.availableRooms
    override val discoveredRepeaters: List<DiscoveredNodeDTO>
        get() = state.value.discoveredNodes.filter { it.nodeType == ContactType.REPEATER }
    override val recentPublicKeys: List<Bytes> get() = state.value.recentPublicKeys
    override val currentHopCount: Int get() = state.value.editablePath.size
    override val hopLimit: Int get() = maxHopCount

    /** Combined repeaters and rooms for resolution. */
    val availableNodes: List<ContactDTO> get() = availableRepeaters + availableRooms

    override fun appendHop(node: RepeaterResolvable) = insert(node, AddHopIntent.APPEND)

    override fun classifyCodes(input: String): List<HopCodeClassification> {
        val path = state.value.editablePath
        return HopCodeParser.classify(
            input, hashSize, path.mapTo(HashSet()) { it.hashBytes }, maxOf(0, maxHopCount - path.size),
        ) { hash -> resolveHashToPublicKey(hash)?.let { ResolvedHop(it, resolveHashToName(hash)) } }
    }

    /** Bulk-adds resolvable codes; codes past the cap are classified path-full and skipped. */
    override fun addCodes(input: String): CodeInputResult {
        var result = CodeInputResult()
        for (entry in classifyCodes(input)) {
            result = when (val status = entry.status) {
                is HopCodeStatus.WillAdd -> {
                    mutableState.update { it.copy(editablePath = it.editablePath + status.hop) }
                    status.hop.publicKey?.let(::recordRecent)
                    result.copy(added = result.added + entry.code)
                }
                HopCodeStatus.AlreadyInPath -> result.copy(alreadyInPath = result.alreadyInPath + entry.code)
                HopCodeStatus.NotFound -> result.copy(notFound = result.notFound + entry.code)
                HopCodeStatus.InvalidFormat -> result.copy(invalidFormat = result.invalidFormat + entry.code)
                HopCodeStatus.PathFull -> result
            }
        }
        return result
    }

    companion object {
        /** Direct routing: zero hops at hash size one. */
        const val DIRECT_ROUTING_PATH_LENGTH: UByte = 0x00u
    }
}

// PortedFrom: MC1/Views/PathEditing/PathManagementViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/PathEditing/CodeInputResult.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/PathEditing/HopCodeClassification.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.path

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import java.util.UUID

/** Drives the Add Hop picker presentation; only append exists today. */
enum class AddHopIntent { APPEND }

/** Sections the Add-Hop picker can narrow to; [ALL] shows every section. */
enum class AddHopFilter(val rawValue: String, val labelRes: Int) {
    ALL("all", R.string.l10n_app_contacts_contacts_pathedit_filter_all),
    FAVORITES("favorites", R.string.l10n_app_contacts_contacts_pathedit_filter_favorites),
    RECENT("recent", R.string.l10n_app_contacts_contacts_pathedit_filter_recent),
    DISCOVERED("discovered", R.string.l10n_app_contacts_contacts_pathedit_filter_discovered),
}

/**
 * One hop in an editable routing path with stable identity. Equality includes [id], like the
 * synthesized Swift `Equatable` over every stored property.
 */
data class PathHop(
    /** Public-key prefix bytes (1 to 3 depending on hash mode). */
    val hashBytes: Bytes,
    /** Full 32-byte key when known, for unambiguous matching. */
    val publicKey: Bytes?,
    /** Contact name when resolved. */
    val resolvedName: String?,
    val id: UUID = UUID.randomUUID(),
) {
    val hashHex: String get() = hashBytes.uppercaseHexString()
    val displayText: String get() = resolvedName?.let { "$it ($hashHex)" } ?: hashHex
}

/** Result of a path discovery operation. */
sealed interface PathDiscoveryResult {
    data class Success(val hopCount: Int) : PathDiscoveryResult
    data object NoPathFound : PathDiscoveryResult
    data class Failed(val message: String) : PathDiscoveryResult

    val description: NodesMessage
        get() = when (this) {
            is Success -> when (hopCount) {
                0 -> NodesMessage.res(R.string.l10n_app_contacts_contacts_pathdiscovery_direct)
                1 -> NodesMessage.res(R.string.l10n_app_contacts_contacts_pathdiscovery_hops_singular)
                else -> NodesMessage.res(R.string.l10n_app_contacts_contacts_pathdiscovery_hops_plural, hopCount)
            }
            NoPathFound -> NodesMessage.res(R.string.l10n_app_contacts_contacts_pathdiscovery_noresponse)
            is Failed -> NodesMessage.res(R.string.l10n_app_contacts_contacts_pathdiscovery_failed, message)
        }
}

/** Outcome of one hex code parsed from a bulk-add entry. */
sealed interface HopCodeStatus {
    /** Valid, resolves to a node and fits the hop cap; carries the prebuilt hop. */
    data class WillAdd(val hop: PathHop) : HopCodeStatus
    data object AlreadyInPath : HopCodeStatus
    /** Valid hex but no matching node. */
    data object NotFound : HopCodeStatus
    /** Wrong length or non-hex. */
    data object InvalidFormat : HopCodeStatus
    /** Valid and resolvable but past the hop cap. */
    data object PathFull : HopCodeStatus
}

/** One parsed code, uppercased and unique after de-duplication (also its stable id). */
data class HopCodeClassification(val code: String, val status: HopCodeStatus) {
    val id: String get() = code
    val willBeAdded: Boolean get() = status is HopCodeStatus.WillAdd
}

/** Result of parsing and adding repeater codes. */
data class CodeInputResult(
    val added: List<String> = emptyList(),
    val notFound: List<String> = emptyList(),
    val alreadyInPath: List<String> = emptyList(),
    val invalidFormat: List<String> = emptyList(),
) {
    val hasErrors: Boolean get() = notFound.isNotEmpty() || alreadyInPath.isNotEmpty() || invalidFormat.isNotEmpty()

    val errorMessage: NodesMessage?
        get() {
            if (!hasErrors) return null
            val parts = buildList {
                if (invalidFormat.isNotEmpty()) {
                    add(NodesMessage.res(R.string.l10n_app_contacts_contacts_codeinput_error_invalidformat, invalidFormat.joinToString(", ")))
                }
                if (notFound.isNotEmpty()) {
                    add(NodesMessage.res(R.string.l10n_app_contacts_contacts_codeinput_error_notfound, notFound.joinToString(", ")))
                }
                if (alreadyInPath.isNotEmpty()) {
                    add(NodesMessage.res(R.string.l10n_app_contacts_contacts_codeinput_error_alreadyinpath, alreadyInPath.joinToString(", ")))
                }
            }
            return NodesMessage.Joined(parts, " \u00B7 ")
        }
}

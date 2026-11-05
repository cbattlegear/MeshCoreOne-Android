// PortedFrom: MC1/Views/Contacts/AddContactSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.nodes.add

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.applicationBytesFromHex
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.bytes.utf8Prefix
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.deps.NodesContactFailure
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import com.meshcoreone.android.feature.nodes.deps.ScannedContact
import com.meshcoreone.android.feature.nodes.deps.ensureContactConnection
import com.meshcoreone.android.feature.nodes.text.SwiftText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Validation feedback under the public-key field. */
sealed interface PublicKeyStatus {
    data object Empty : PublicKeyStatus
    data object Valid : PublicKeyStatus
    data class Count(val current: Int, val required: Int) : PublicKeyStatus
}

data class AddContactState(
    val selectedType: ContactType = ContactType.CHAT,
    val contactName: String = "",
    val publicKeyHex: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: NodesMessage? = null,
    /** The paste-URL row's invalid-link message is visible. */
    val showPasteError: Boolean = false,
) {
    val normalizedPublicKeyHex: String get() = SwiftText.lowercased(SwiftText.filterHexDigits(publicKeyHex))
    val isValidPublicKey: Boolean get() = SwiftText.characters(normalizedPublicKeyHex).size == PUBLIC_KEY_HEX_LENGTH
    val canAdd: Boolean get() = contactName.isNotEmpty() && isValidPublicKey && !isSubmitting

    val publicKeyStatus: PublicKeyStatus
        get() = when {
            publicKeyHex.isEmpty() -> PublicKeyStatus.Empty
            isValidPublicKey -> PublicKeyStatus.Valid
            else -> PublicKeyStatus.Count(SwiftText.characters(normalizedPublicKeyHex).size, PUBLIC_KEY_HEX_LENGTH)
        }

    companion object {
        const val PUBLIC_KEY_HEX_LENGTH = ProtocolLimits.PUBLIC_KEY_SIZE * 2
    }
}

/** Manual add-contact form (name, type, 64-hex key, pasted link) with an explicit Add confirmation. */
class AddContactStateHolder(private val dependencies: NodesFeatureDependencies) {
    private val mutableState = MutableStateFlow(AddContactState())
    val state: StateFlow<AddContactState> = mutableState.asStateFlow()

    fun selectType(type: ContactType) = mutableState.update { it.copy(selectedType = type) }

    /** Names are capped to the firmware's usable byte length on whole characters. */
    fun setContactName(name: String) = mutableState.update {
        val bytes = name.toByteArray(Charsets.UTF_8).size
        it.copy(contactName = if (bytes > ProtocolLimits.MAX_USABLE_NAME_BYTES) name.utf8Prefix(ProtocolLimits.MAX_USABLE_NAME_BYTES) else name)
    }

    /** The key field keeps hex digits only, lowercased. */
    fun setPublicKeyHex(text: String) = mutableState.update {
        it.copy(publicKeyHex = SwiftText.lowercased(SwiftText.filterHexDigits(text)))
    }

    /** Paste a `meshcore://contact/add` link from the clipboard; unparseable text shows the row error. */
    fun pasteContactUrl(clipboard: String?) {
        val parsed = clipboard?.let(dependencies.uriCodec::parseContactUri)
        if (parsed == null) {
            mutableState.update { it.copy(showPasteError = true) }
            return
        }
        mutableState.update { it.copy(showPasteError = false) }
        applyParsed(parsed)
    }

    private fun applyParsed(result: ScannedContact) {
        setContactName(result.name)
        setPublicKeyHex(result.publicKey.hexString)
        mutableState.update { it.copy(selectedType = result.contactType, errorMessage = null) }
    }

    /** Sends the contact to the radio. Returns true when the sheet should dismiss. */
    suspend fun add(): Boolean = addContact() != null

    /** Returns the stored identity for immediate admission by the owning list. */
    suspend fun addContact(
        connection: Any? = dependencies.session.connectionIdentity(),
        radioId: RadioId? = dependencies.session.currentRadioId(),
    ): ContactDTO? {
        if (state.value.isSubmitting) return null
        val session = dependencies.session
        session.ensureContactConnection(connection, radioId)
        val contactService = session.contactService()
        val device = session.connectedDevice()
        session.ensureContactConnection(connection, radioId)
        if (contactService == null || device == null) {
            fail(NodesMessage.res(R.string.l10n_app_contacts_contacts_add_error_notconnected), submitting = false)
            return null
        }
        val current = state.value
        val publicKey = applicationBytesFromHex(current.normalizedPublicKeyHex) ?: run {
            fail(NodesMessage.res(R.string.l10n_app_contacts_contacts_add_error_invalidformat), submitting = current.isSubmitting)
            return null
        }
        if (publicKey.size != ProtocolLimits.PUBLIC_KEY_SIZE) {
            val message = NodesMessage.res(
                R.string.l10n_app_contacts_contacts_add_error_invalidsize, ProtocolLimits.PUBLIC_KEY_SIZE, AddContactState.PUBLIC_KEY_HEX_LENGTH,
            )
            fail(message, submitting = current.isSubmitting)
            return null
        }
        mutableState.update { it.copy(isSubmitting = true, errorMessage = null) }
        return try {
            session.ensureContactConnection(connection, device.radioId)
            contactService.addOrUpdateContact(device.radioId, newContactFrame(publicKey, current.selectedType, current.contactName))
            session.ensureContactConnection(connection, device.radioId)
            val stored = contactService.getContact(device.radioId, publicKey)
            session.ensureContactConnection(connection, device.radioId)
            stored?.takeIf { it.radioId == device.radioId && it.publicKey == publicKey }
                ?: throw NodesContactFailure.ContactNotFound()
        } catch (_: NodesContactFailure.ContactTableFull) {
            session.ensureContactConnection(connection, device.radioId)
            fail(NodesMessage.res(R.string.l10n_app_contacts_contacts_add_error_nodelistfull, device.maxContacts.toInt()), submitting = false)
            null
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(isSubmitting = false) }
            throw cancelled
        } catch (error: Exception) {
            session.ensureContactConnection(connection, device.radioId)
            val message = NodesMessage.Joined(
                listOf(NodesMessage.res(R.string.l10n_app_contacts_contacts_common_error), NodesMessage.Text(dependencies.messages.message(error))),
                ": ",
            )
            fail(message, submitting = false)
            null
        } finally {
            mutableState.update { it.copy(isSubmitting = false) }
        }
    }

    private fun fail(message: NodesMessage, submitting: Boolean) =
        mutableState.update { it.copy(errorMessage = message, isSubmitting = submitting) }

    private fun newContactFrame(publicKey: Bytes, type: ContactType, name: String): ContactFrame = ContactFrame(
        publicKey = publicKey, type = type, flags = 0u, outPathLength = PacketBuilder.FLOOD_PATH_SENTINEL, outPath = Bytes.EMPTY,
        name = name, lastAdvertTimestamp = 0u, latitude = 0.0, longitude = 0.0,
        lastModified = dependencies.clock.wallNow.epochSecond.toUInt(),
    )
}

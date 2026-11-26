// PortedFrom: MC1/Views/Settings/Sections/RegenerateIdentityViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.ui.UiText
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A generated identity ready for review: hex for display and a comma-separated TalkBack reading. */
class GeneratedKey(val expandedKey: Bytes, val publicKeyHex: String, val privateKeyHex: String, val accessibilityLabel: String)

data class RegenerateIdentityState(
    val hexPrefix: String = "",
    val isGenerating: Boolean = false,
    val isImporting: Boolean = false,
    val generatedKey: GeneratedKey? = null,
    val showingReplaceAlert: Boolean = false,
    val errorMessage: UiText? = null,
    val prefixError: UiText? = null,
    val successTrigger: Int = 0,
) {
    val isBusy: Boolean get() = isGenerating || isImporting
}

/** Generates a vanity-prefix identity and, after a replace confirmation, imports it into the radio. */
class RegenerateIdentityStateHolder(
    private val env: SettingsEnvironment,
    private val keys: IdentityKeyPort,
    private val settingsService: () -> SettingsRadioPort?,
) {
    private val mutable = MutableStateFlow(RegenerateIdentityState())
    private var generateJob: Job? = null
    val state: StateFlow<RegenerateIdentityState> = mutable.asStateFlow()

    fun cancelGeneration() {
        generateJob?.cancel()
    }

    fun dismissError() = mutable.update { it.copy(errorMessage = null) }
    fun requestReplace() = mutable.update { it.copy(showingReplaceAlert = true) }
    fun dismissReplaceAlert() = mutable.update { it.copy(showingReplaceAlert = false) }

    /** Keeps hex digits only, at most [MAX_PREFIX_LENGTH]: uppercased first, then ASCII hex kept (so "ﬀ" becomes "FF"). */
    fun sanitizePrefix(newValue: String) {
        val filtered = newValue.uppercase(Locale.ROOT).filter { it in '0'..'9' || it in 'A'..'F' }.take(MAX_PREFIX_LENGTH)
        mutable.update { it.copy(hexPrefix = filtered, prefixError = null) }
    }

    fun generateKey() {
        mutable.update { it.copy(prefixError = null) }
        val upper = mutable.value.hexPrefix.uppercase(Locale.ROOT)
        if (upper.length >= 2 && RESERVED_PREFIXES.any { upper.startsWith(it) }) {
            mutable.update { it.copy(prefixError = UiText.Resource(AppSettingsStrings.regenerateIdentityPrefixErrorReserved)) }
            return
        }
        mutable.update { it.copy(isGenerating = true) }
        // ATOMIC so a cancel before the first dispatch still runs the `finally` that clears isGenerating (Swift's `defer`).
        generateJob = env.scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                val result = keys.generateIdentity(upper.ifEmpty { null })
                val generated = GeneratedKey(
                    expandedKey = result.expandedPrivateKey,
                    publicKeyHex = result.publicKey.hexUppercase(" "),
                    privateKeyHex = result.expandedPrivateKey.hexUppercase(" "),
                    accessibilityLabel = result.publicKey.hexUppercase(", "),
                )
                mutable.update { it.copy(generatedKey = generated) }
            } catch (cancelled: CancellationException) {
                // Sheet dismissed during generation.
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(errorMessage = env.describe(error)) }
            } finally {
                mutable.update { it.copy(isGenerating = false) }
            }
        }
    }

    /** Returns true when the new identity was imported and the sheet should dismiss. A missing service is a no-op. */
    suspend fun replaceIdentity(): Boolean {
        val key = mutable.value.generatedKey?.expandedKey ?: return false
        val service = settingsService() ?: return false
        mutable.update { it.copy(isImporting = true) }
        try {
            service.importPrivateKey(key)
            service.refreshDeviceInfo()
            mutable.update { it.copy(successTrigger = it.successTrigger + 1) }
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutable.update { it.copy(errorMessage = importMessage(error)) }
            return false
        } finally {
            mutable.update { it.copy(isImporting = false) }
        }
    }

    private fun importMessage(error: Exception): UiText {
        val session = (error as? SettingsServiceException)?.error as? SettingsServiceError.SessionError
        return when (session?.error) {
            is MeshCoreException.FeatureDisabled -> UiText.Resource(AppSettingsStrings.regenerateIdentityErrorFeatureDisabled)
            is MeshCoreException.DeviceError -> UiText.Resource(AppSettingsStrings.regenerateIdentityErrorDeviceRejected)
            else -> env.describe(error)
        }
    }

    private fun Bytes.hexUppercase(separator: String): String =
        toByteArray().joinToString(separator) { "%02X".format(Locale.ROOT, it.toInt() and 0xFF) }

    companion object {
        /** Maximum vanity-prefix length in hex digits (two key bytes). */
        const val MAX_PREFIX_LENGTH = 4

        /** Key prefixes the firmware reserves for special addressing. */
        val RESERVED_PREFIXES = listOf("00", "FF")
    }
}

// AndroidOnly: WP-314 Feature-owned dependency ports for trace path; WP-303 binds them to the live session graph.
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.contracts.domain.TracePathPersisting
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import kotlinx.coroutines.flow.Flow

/** Contacts and discovered nodes for name resolution (`ContactPersisting`/`DiscoveredNodePersisting` subsets). */
interface TraceNodeDirectory {
    suspend fun fetchContacts(radioId: RadioId): List<ContactDTO>
    suspend fun fetchDiscoveredNodes(radioId: RadioId): List<DiscoveredNodeDTO>
}

/** The session's trace command (`DiagnosticsSessionOps.sendTrace` subset). */
fun interface TraceSender {
    suspend fun sendTrace(tag: UInt, authCode: UInt, flags: UByte, path: Bytes): MessageSentInfo
}

/**
 * Live providers, re-read at every use so a disconnect or a session-graph rebuild mid-trace is
 * observed, never a stale snapshot. `null` mirrors a disconnected state: the call is a no-op.
 */
interface TracePathFeatureDependencies {
    /** Read synchronously from the graph registry, independently of Compose publication. */
    fun sessionToken(): SessionToken? = null
    /** Identity actually published for the UI's generation, not a later live graph. */
    fun originatingSessionToken(generation: Int): SessionToken? = sessionToken()
    fun connectedDevice(): DeviceDTO?
    fun bestAvailableLocation(): Coordinate?
    fun nodeDirectory(): TraceNodeDirectory?
    fun savedPaths(): TracePathPersisting?
    fun traceSender(): TraceSender?

    /** A new subscription to the current graph's trace responses; `null` while disconnected. */
    fun traceResponses(): Flow<TraceResponse>?

    companion object {
        /** Every provider absent, as an unconfigured source view model. */
        val Disconnected: TracePathFeatureDependencies = object : TracePathFeatureDependencies {
            override fun connectedDevice(): DeviceDTO? = null
            override fun bestAvailableLocation(): Coordinate? = null
            override fun nodeDirectory(): TraceNodeDirectory? = null
            override fun savedPaths(): TracePathPersisting? = null
            override fun traceSender(): TraceSender? = null
            override fun traceResponses(): Flow<TraceResponse>? = null
        }
    }
}

/** Localized copy the logic layer emits. The UI binds these to core:l10n resources. */
interface TracePathStrings {
    val myDevice: String
    val sendFailed: String
    val noResponse: String
    fun allFailed(count: Int): String
    fun codeInvalidFormat(codes: String): String
    fun codeNotFound(codes: String): String
    fun codeAlreadyInPath(codes: String): String
    fun pathNamePrefix(prefix: String): String
    fun pathNameTwoEndpoints(first: String, last: String): String
    fun pathNameMultipleEndpoints(first: String, last: String): String
    val defaultMapPathName: String
}

/** Clipboard write for "copy path" (the source wrote `UIPasteboard.general`). */
fun interface TraceClipboard {
    fun copy(text: String)
}

/**
 * Failures the source logged without surfacing (store/session errors on background paths).
 * WP-303 binds this to the app logger; it is required so no failure is silently dropped.
 */
fun interface TraceDiagnostics {
    fun failure(operation: String, error: Exception)
}

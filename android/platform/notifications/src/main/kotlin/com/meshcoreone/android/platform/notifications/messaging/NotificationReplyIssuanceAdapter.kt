// AndroidOnly: WP-401 Unactivated reserve-before-exposure reply capability adapter, not dispatch authority.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.Failure
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.IssuanceResult
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.IssuedAction
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.IssuedRecord
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface NotificationReplyPreparation {
    /** Issuance only: no reply bytes retained, notification posted or delivery/send acknowledged. */
    data class Prepared(val issuance: IssuedRecord, val pendingIntent: PendingIntent) : NotificationReplyPreparation
    data class NotIssued(val reason: Failure) : NotificationReplyPreparation
    /** Retry only the same proposal; this does not authorize exposing a token or minting a new one. */
    data class Indeterminate(val token: UUID) : NotificationReplyPreparation
    data class PlatformRejected(val issuance: IssuedRecord, val reason: PlatformFailure) : NotificationReplyPreparation
    enum class PlatformFailure { SECURITY_DENIED, INVALID_PENDING_INTENT }
}

sealed interface NotificationReplyDeliveryParsing {
    /**
     * Structural input only, NOT authenticated, retained or sendable. The receiver must call the
     * journal's atomic admitIssued(storeId, token, exactText), never lookup then stage a payload.
     */
    data class ReadyForAdmission(val storeId: UUID, val token: UUID, val exactText: String) :
        NotificationReplyDeliveryParsing
    data class Rejected(val reason: ParseFailure) : NotificationReplyDeliveryParsing
    enum class ParseFailure { MISSING_IDENTITY, MALFORMED_IDENTITY, STALE_INSTALLATION, MISSING_INPUT }
}

/**
 * Deliberately unused by notification posting and the receiver until real installation provisioning,
 * atomic admission and visible failure handling exist together. Does not authenticate human/RF origin.
 */
class NotificationReplyIssuanceAdapter internal constructor(
    context: Context,
    private val expectedStoreId: UUID,
    private val registerIssued: suspend (UUID, IssuedAction) -> IssuanceResult,
) {
    constructor(context: Context, expectedStoreId: UUID, store: NotificationReplyStagingStore) :
        this(context, expectedStoreId, store::registerIssued)

    private val context = context.applicationContext

    suspend fun prepare(proposal: IssuedAction): NotificationReplyPreparation {
        currentCoroutineContext().ensureActive()
        if (proposal.post.version != 1 || proposal.post.action != "REPLY") {
            return NotificationReplyPreparation.NotIssued(Failure.INVALID_IDENTITY)
        }
        if (proposal.logicalNotificationId.value.isEmpty() ||
            proposal.payload !is NotificationPayload.DirectMessage &&
            proposal.payload !is NotificationPayload.ChannelMessage
        ) return NotificationReplyPreparation.NotIssued(Failure.INVALID_PAYLOAD)

        val reserved = when (val result = registerIssued(expectedStoreId, proposal)) {
            is IssuanceResult.Reserved -> result.record
            is IssuanceResult.AlreadyReserved -> result.record
            is IssuanceResult.Rejected -> return NotificationReplyPreparation.NotIssued(result.reason)
            is IssuanceResult.Indeterminate -> return NotificationReplyPreparation.Indeterminate(result.token)
        }
        if (reserved.storeId != expectedStoreId || reserved.action != proposal) {
            return NotificationReplyPreparation.NotIssued(Failure.CORRUPT_STORAGE)
        }
        currentCoroutineContext().ensureActive()
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(ACTION)
            .setData(identity(reserved.storeId, proposal.token))
            .putExtra(EXTRA_STORE, reserved.storeId.toString())
            .putExtra(EXTRA_TOKEN, proposal.token.toString())
            // Base extras win over mutable fill-in extras. Prevent legacy payload-codec fallback
            // even if a token holder supplies a complete forged legacy payload in its fill-in.
            .putExtra(LEGACY_PAYLOAD_TYPE, ISSUED_TYPE)
        val pending = try {
            PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        } catch (_: SecurityException) {
            return NotificationReplyPreparation.PlatformRejected(
                reserved, NotificationReplyPreparation.PlatformFailure.SECURITY_DENIED,
            )
        } catch (_: IllegalArgumentException) {
            return NotificationReplyPreparation.PlatformRejected(
                reserved, NotificationReplyPreparation.PlatformFailure.INVALID_PENDING_INTENT,
            )
        }
        return NotificationReplyPreparation.Prepared(reserved, pending)
    }

    fun parseDelivery(intent: Intent): NotificationReplyDeliveryParsing = try {
        parseIdentityAndInput(intent)
    } catch (_: BadParcelableException) {
        rejected(NotificationReplyDeliveryParsing.ParseFailure.MALFORMED_IDENTITY)
    } catch (_: ClassCastException) {
        rejected(NotificationReplyDeliveryParsing.ParseFailure.MALFORMED_IDENTITY)
    }

    private fun parseIdentityAndInput(intent: Intent): NotificationReplyDeliveryParsing {
        if (intent.action != ACTION || intent.component?.className != NotificationActionReceiver::class.java.name ||
            intent.component?.packageName != context.packageName ||
            intent.getStringExtra(LEGACY_PAYLOAD_TYPE) != ISSUED_TYPE
        ) return rejected(NotificationReplyDeliveryParsing.ParseFailure.MALFORMED_IDENTITY)
        val rawStore = intent.getStringExtra(EXTRA_STORE)
            ?: return rejected(NotificationReplyDeliveryParsing.ParseFailure.MISSING_IDENTITY)
        val rawToken = intent.getStringExtra(EXTRA_TOKEN)
            ?: return rejected(NotificationReplyDeliveryParsing.ParseFailure.MISSING_IDENTITY)
        val store = parseUuid(rawStore) ?: return rejected(NotificationReplyDeliveryParsing.ParseFailure.MALFORMED_IDENTITY)
        val token = parseUuid(rawToken) ?: return rejected(NotificationReplyDeliveryParsing.ParseFailure.MALFORMED_IDENTITY)
        if (store != expectedStoreId) return rejected(NotificationReplyDeliveryParsing.ParseFailure.STALE_INSTALLATION)
        if (intent.data != identity(store, token)) return rejected(NotificationReplyDeliveryParsing.ParseFailure.MALFORMED_IDENTITY)
        val exactText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NotificationActionReceiver.REMOTE_INPUT_KEY)?.toString()
            ?: return rejected(NotificationReplyDeliveryParsing.ParseFailure.MISSING_INPUT)
        return NotificationReplyDeliveryParsing.ReadyForAdmission(store, token, exactText)
    }

    private fun rejected(reason: NotificationReplyDeliveryParsing.ParseFailure) =
        NotificationReplyDeliveryParsing.Rejected(reason)

    private fun parseUuid(raw: String): UUID? = try {
        UUID.fromString(raw).takeIf { it.toString() == raw }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun identity(storeId: UUID, token: UUID): Uri =
        Uri.Builder().scheme("meshcore-notification").authority("issued-reply")
            .appendPath("v1").appendPath(storeId.toString()).appendPath(token.toString()).build()

    companion object {
        const val ACTION = "com.meshcoreone.android.notification.ISSUED_REPLY_V1"
        private const val LEGACY_PAYLOAD_TYPE = "mc1.notification.type"
        private const val ISSUED_TYPE = "issued-reply-v1"
        private const val EXTRA_STORE = "mc1.notification.issued-reply.store"
        private const val EXTRA_TOKEN = "mc1.notification.issued-reply.token"
    }
}

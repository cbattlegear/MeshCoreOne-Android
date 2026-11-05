// AndroidOnly: WP-401 Real Intent controls for rejected RemoteInput recovery; no ingress.
package com.meshcoreone.android.platform.notifications.messaging

import android.app.Application
import android.app.RemoteInput
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyDeliveryParsing.InputStatus
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyDeliveryParsing.ParseFailure
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.Failure
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore.IssuanceResult
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class NotificationReplyRejectedInputRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val expectedStoreId = UUID(0, 100)
    private val staleStoreId = UUID(0, 999)
    private val token = UUID(0, 200)
    private val adapter = NotificationReplyIssuanceAdapter(context, expectedStoreId) { _, _ ->
        IssuanceResult.Rejected(Failure.INVALID_IDENTITY)
    }

    @Test
    fun rejectedIdentitiesExposeExactReadableTextButNeverBecomeAdmissionReady() {
        val exactText = "  reply\nexact \uD83D\uDE80  "
        val stale = adapter.parseDelivery(addInput(identity(staleStoreId), exactText))
        assertEquals(
            NotificationReplyDeliveryParsing.Rejected(ParseFailure.STALE_INSTALLATION, exactText, InputStatus.READABLE),
            stale,
        )
        assertFalse(stale is NotificationReplyDeliveryParsing.ReadyForAdmission)
        assertFalse(stale.toString().contains(exactText))

        val malformed = adapter.parseDelivery(
            addInput(identity(expectedStoreId).setAction("legacy"), exactText),
        )
        assertEquals(
            NotificationReplyDeliveryParsing.Rejected(ParseFailure.MALFORMED_IDENTITY, exactText, InputStatus.READABLE),
            malformed,
        )
        assertFalse(malformed is NotificationReplyDeliveryParsing.ReadyForAdmission)

        for ((intent, reason) in listOf(
            identity(expectedStoreId).apply { removeExtra(EXTRA_TOKEN) } to ParseFailure.MISSING_IDENTITY,
            identity(expectedStoreId).putExtra(EXTRA_TOKEN, "not-a-uuid") to ParseFailure.MALFORMED_IDENTITY,
            identity(expectedStoreId).setData(Uri.parse("meshcore-notification://wrong")) to
                ParseFailure.MALFORMED_IDENTITY,
        )) {
            assertEquals(
                NotificationReplyDeliveryParsing.Rejected(reason, exactText, InputStatus.READABLE),
                adapter.parseDelivery(addInput(intent, exactText)),
            )
        }

        assertEquals(
            NotificationReplyDeliveryParsing.ReadyForAdmission(expectedStoreId, token, exactText),
            adapter.parseDelivery(addInput(identity(expectedStoreId), exactText)),
        )
    }

    @Test
    fun missingAndEmptyRejectedInputNeverCreateRecoveryText() {
        assertEquals(
            NotificationReplyDeliveryParsing.Rejected(ParseFailure.STALE_INSTALLATION, null, InputStatus.MISSING),
            adapter.parseDelivery(identity(staleStoreId)),
        )
        assertEquals(
            NotificationReplyDeliveryParsing.Rejected(ParseFailure.STALE_INSTALLATION, null, InputStatus.EMPTY),
            adapter.parseDelivery(addInput(identity(staleStoreId), "")),
        )
        assertEquals(
            NotificationReplyDeliveryParsing.ReadyForAdmission(expectedStoreId, token, ""),
            adapter.parseDelivery(addInput(identity(expectedStoreId), "")),
        )
    }

    @Test
    fun malformedRemoteInputParcelableIsExplicitWithoutReplacingIdentityRejection() {
        val malformed = malformedParcelable(identity(expectedStoreId))
        assertEquals(
            NotificationReplyDeliveryParsing.Rejected(ParseFailure.MALFORMED_INPUT, null, InputStatus.MALFORMED),
            adapter.parseDelivery(malformed),
        )
        assertEquals(
            NotificationReplyDeliveryParsing.Rejected(ParseFailure.STALE_INSTALLATION, null, InputStatus.MALFORMED),
            adapter.parseDelivery(malformedParcelable(identity(staleStoreId))),
        )
    }

    private fun identity(storeId: UUID): Intent =
        Intent(context, UnactivatedIssuedReplyReceiver::class.java)
            .setAction(NotificationReplyIssuanceAdapter.ACTION)
            .setData(
                Uri.parse("meshcore-notification://issued-reply/v1/$storeId/$token"),
            )
            .putExtra(EXTRA_STORE, storeId.toString())
            .putExtra(EXTRA_TOKEN, token.toString())

    private fun addInput(intent: Intent, text: String): Intent =
        Intent(intent).also {
            RemoteInput.addResultsToIntent(
                arrayOf(RemoteInput.Builder(NotificationActionReceiver.REMOTE_INPUT_KEY).build()),
                it,
                Bundle().apply { putCharSequence(NotificationActionReceiver.REMOTE_INPUT_KEY, text) },
            )
        }

    private fun malformedParcelable(intent: Intent): Intent {
        val resultIntent = Intent().putExtra(
            REMOTE_INPUT_RESULTS_DATA,
            Bundle().apply {
                putParcelable(NotificationActionReceiver.REMOTE_INPUT_KEY, Intent("not-text"))
            },
        )
        return Intent(intent).apply {
            clipData = ClipData.newIntent(REMOTE_INPUT_RESULTS_LABEL, resultIntent)
        }
    }

    private companion object {
        const val EXTRA_STORE = "mc1.notification.issued-reply.store"
        const val EXTRA_TOKEN = "mc1.notification.issued-reply.token"
        const val REMOTE_INPUT_RESULTS_LABEL = "android.remoteinput.results"
        const val REMOTE_INPUT_RESULTS_DATA = "android.remoteinput.resultsData"
    }
}

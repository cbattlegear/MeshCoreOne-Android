// AndroidOnly: WP-005 Compiled recovery copy contract and deliberate locale fallback for WP-308.
package com.meshcoreone.android.core.l10n

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RecoveredDraftResourceTest {
    private val copy = mapOf(
        R.string.wp308_recovery_title to "Recovered drafts",
        R.string.wp308_recovery_explanation to
            "Outgoing text is kept here when its original chat is unknown or has been replaced. " +
            "Recovery does not prove whether a message was sent or delivered. " +
            "Choose a chat to copy the text into its draft. " +
            "Copying does not send anything or remove the recovered text.",
        R.string.wp308_recovery_empty to "No recovered drafts.",
        R.string.wp308_recovery_refresh to "Refresh recovered drafts",
        R.string.wp308_recovery_load_more to "Load more recovered drafts",
        R.string.wp308_recovery_choose_chat to "Choose chat",
        R.string.wp308_recovery_copy_to_selected_chat to "Copy to selected chat",
        R.string.wp308_recovery_status_unknown_origin to
            "Original chat unknown. Recovery does not prove whether this message was sent or delivered.",
        R.string.wp308_recovery_status_replaced_occupant to
            "Original chat replaced. Recovery does not prove whether this message was sent or delivered.",
        R.string.wp308_recovery_status_delivery_unknown to
            "Delivery status unknown. This message may already have been sent.",
        R.string.wp308_recovery_load_failed to "Could not load recovered drafts. Refresh to try again.",
        R.string.wp308_recovery_copy_failed to
            "Could not copy the text. Check the selected chat and its draft before trying again.",
        R.string.wp308_recovery_destination_conflict to
            "The selected chat already has draft text. Nothing was replaced. " +
            "Keep or clear that draft before copying.",
        R.string.wp308_recovery_copied to
            "Text copied to the selected chat draft. " +
            "This action did not send a message or remove the recovered text.",
    )

    @Test
    fun allRecoveryLabelsResolveThroughCompiledEnglishFallback() {
        assertEquals(14, copy.size)
        (sourceLocales + listOf("pt-BR", "ar", "ja")).forEach { locale ->
            val resources = resourcesForLocale(locale)
            copy.forEach { (id, expected) ->
                assertEquals(expected, resources.getString(id), "$locale/${resources.getResourceEntryName(id)}")
            }
        }
    }

    @Test
    fun recoveryCopyKeepsUnknownDeliverySeparateFromExplicitCopy() {
        val resources = resourcesForLocale("en")
        val explanation = resources.getString(R.string.wp308_recovery_explanation)
        assertTrue(explanation.contains("unknown or has been replaced"))
        assertTrue(explanation.contains("does not prove whether a message was sent or delivered"))
        assertTrue(explanation.contains("does not send anything or remove the recovered text"))
        assertEquals("Copy to selected chat", resources.getString(R.string.wp308_recovery_copy_to_selected_chat))
        assertTrue(resources.getString(R.string.wp308_recovery_status_delivery_unknown).contains("may already have been sent"))
        assertTrue(resources.getString(R.string.wp308_recovery_destination_conflict).contains("Nothing was replaced"))
        assertTrue(resources.getString(R.string.wp308_recovery_copied).contains("This action did not send a message"))
    }
}

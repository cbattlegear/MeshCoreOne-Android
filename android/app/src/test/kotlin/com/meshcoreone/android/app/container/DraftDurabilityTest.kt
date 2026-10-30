// PortedFrom: MC1Services/Tests/MC1ServicesTests/DraftStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.services.rendering.DraftStore
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class DraftDurabilityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val radio = RadioId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val other = RadioId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val direct = ChatConversationID.dm(radio, UUID.fromString("33333333-3333-3333-3333-333333333333"))
    private fun channel(index: UByte, id: RadioId = radio) = ChatConversationID.channel(id, index)
    private fun reopen() = DraftStore(SharedPreferencesDraftDefaults(context))

    @Before fun clearPreferences() {
        check(context.getSharedPreferences("mc1.drafts", Context.MODE_PRIVATE).edit().clear().commit())
    }

    @Test fun verbatimDraftSurvivesFreshAdapterAndStore() {
        val text = "  Guten Morgen\n\uD83D\uDC69\u200D\uD83D\uDCBB "
        reopen().setDraft(text, direct)
        assertEquals(text, reopen().draft(direct))
        assertNull(reopen().draft(channel(1u)))
    }

    @Test fun explicitDeletionSurvivesFreshAdapterAndStore() {
        val store = reopen()
        store.setDraft("remove", direct)
        store.setDraft("keep", channel(1u))
        store.clearDraft(direct)
        assertNull(reopen().draft(direct))
        assertEquals("keep", reopen().draft(channel(1u)))
    }

    @Test fun whitespaceDeletionSurvivesFreshAdapterAndStore() {
        val store = reopen()
        store.setDraft("remove", direct)
        store.setDraft(" \n\t ", direct)
        assertNull(reopen().draft(direct))
    }

    @Test fun batchDeletionPreservesOtherSlotsRadiosAndDirectDrafts() {
        val store = reopen()
        store.setDraft("dm", direct)
        for (id in listOf(radio, other)) {
            for (index in listOf<UByte>(1u, 2u, 3u)) store.setDraft("$id:$index", channel(index, id))
        }
        store.clearChannelDrafts(radio, setOf<UByte>(1u, 2u))
        val firstRestart = reopen()
        assertNull(firstRestart.draft(channel(1u)))
        assertNull(firstRestart.draft(channel(2u)))
        assertEquals("$radio:3", firstRestart.draft(channel(3u)))
        assertEquals("$other:1", firstRestart.draft(channel(1u, other)))
        firstRestart.clearChannelDrafts(mapOf(radio to setOf<UByte>(3u), other to setOf<UByte>(1u, 2u)))
        val secondRestart = reopen()
        for (index in listOf<UByte>(1u, 2u, 3u)) assertNull(secondRestart.draft(channel(index)))
        assertNull(secondRestart.draft(channel(1u, other)))
        assertNull(secondRestart.draft(channel(2u, other)))
        assertEquals("$other:3", secondRestart.draft(channel(3u, other)))
        assertEquals("dm", secondRestart.draft(direct))
    }
}

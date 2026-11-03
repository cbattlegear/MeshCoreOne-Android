// AndroidOnly: WP-209 Exercise the real exclusion/identity behavior of the channel service fixture.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ChannelIdentityMutex
import com.meshcoreone.android.core.contracts.domain.ChannelPersisting
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ChannelsInMemoryStoreIdentityTest {
    @Test
    fun authoritativeFixtureWritesPreserveRenameButRotateAbaAndInvalidateClear() = runTest {
        val store = ChannelsInMemoryStore(ChannelIdentityMutex())
        val radio = channelsRadioId()
        val first = ChannelInfo(1u, "old", channelsSecret(1))
        store.saveChannel(radio, first)
        val epoch = assertNotNull(store.fetchChannelOccupantID(radio, 1u))
        store.saveChannel(radio, first.copy(name = "rename"))
        assertEquals(epoch, store.fetchChannelOccupantID(radio, 1u))
        store.saveChannel(radio, first.copy(secret = channelsSecret(2)))
        assertNotEquals(epoch, store.fetchChannelOccupantID(radio, 1u))
        store.saveChannel(radio, first)
        assertNotEquals(epoch, store.fetchChannelOccupantID(radio, 1u))
        store.batchSaveChannels(radio, emptyList<ChannelInfo>().snapshot(), listOf<UByte>(1u).snapshot(), null)
        assertNull(store.fetchChannelOccupantSnapshot(radio, 1u))
    }

    @Test
    fun scopedFixtureCanWriteButRejectsChildrenAndEscapedWriters() = runTest {
        val store = ChannelsInMemoryStore(ChannelIdentityMutex())
        val radio = channelsRadioId()
        val info = ChannelInfo(1u, "one", channelsSecret(1))
        lateinit var leaked: ChannelPersisting
        store.withChannelOperation { scoped, _ ->
            leaked = scoped
            scoped.saveChannel(radio, info)
            async {
                assertFailsWith<IllegalStateException> { scoped.saveChannel(radio, info.copy(name = "child")) }
            }.await()
            assertFailsWith<IllegalStateException> { store.saveChannel(radio, info.copy(name = "recursive")) }
        }
        assertFailsWith<IllegalStateException> { leaked.saveChannel(radio, info.copy(name = "escaped")) }
        assertEquals("one", store.fetchChannel(radio, 1u)?.name)
    }

    @Test
    fun failedBatchDoesNotMintOrRotateFixtureAuthority() = runTest {
        val store = ChannelsInMemoryStore(ChannelIdentityMutex())
        val radio = channelsRadioId()
        val first = ChannelInfo(1u, "old", channelsSecret(1))
        store.saveChannel(radio, first)
        val epoch = store.fetchChannelOccupantID(radio, 1u)
        store.batchSaveFailure = IllegalStateException("before commit")
        assertFailsWith<IllegalStateException> {
            store.batchSaveChannels(radio, listOf(first.copy(secret = channelsSecret(2))).snapshot(), emptyList<UByte>().snapshot(), null)
        }
        assertEquals(epoch, store.fetchChannelOccupantID(radio, 1u))
        assertEquals(first.secret, store.fetchChannelOccupantSnapshot(radio, 1u)?.channel?.secret)
    }

    @Test
    fun unresolvedMarkerRequiresScopedBeginAndAuthoritativeReconciliation() = runTest {
        val store = ChannelsInMemoryStore(ChannelIdentityMutex())
        val radio = channelsRadioId()
        val info = ChannelInfo(1u, "old", channelsSecret(1))
        store.saveChannel(radio, info)
        val epoch = store.fetchChannelOccupantID(radio, 1u)
        val row = assertNotNull(store.fetchChannel(radio, 1u))
        assertFailsWith<IllegalStateException> { store.beginChannelMutation(radio, 1u) }
        store.withChannelOperation { scoped, _ -> scoped.beginChannelMutation(radio, 1u) }
        assertNull(store.fetchChannelOccupantSnapshot(radio, 1u))
        store.saveChannel(row.copy(name = "ordinary rename"))
        assertNull(store.fetchChannelOccupantID(radio, 1u))
        store.saveChannel(radio, info.copy(name = "authoritative rename"))
        assertEquals(epoch, store.fetchChannelOccupantID(radio, 1u))
    }
}

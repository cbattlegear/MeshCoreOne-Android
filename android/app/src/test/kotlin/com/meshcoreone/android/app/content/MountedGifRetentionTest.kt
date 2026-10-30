// AndroidOnly: WP-218 verify GIF byte retention through the actual AppChatContent image producer.
package com.meshcoreone.android.app.content

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.AppChatContent
import com.meshcoreone.android.app.container.ChatImage
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MountedGifRetentionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(directory: File): Context {
        val application = ApplicationProvider.getApplicationContext<Context>()
        return object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
    }

    @Test
    fun `mounted GIF retains its original share bytes across process memory trim`() = runTest {
        val noBackupDirectory = temporary.newFolder()
        val context = context(noBackupDirectory)
        val storage = MeshCoreStorage.get(context)
        val database = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries().build()
        val store = RoomPersistenceStore(database, backgroundScope)
        val lifetime = SupervisorJob(requireNotNull(backgroundScope.coroutineContext[Job]))
        val scope = CoroutineScope(backgroundScope.coroutineContext + lifetime)
        val url = "https://8.8.8.8/mounted.gif"
        val bytes = gif()
        var fetchCalls = 0
        val http = object : BoundedHttpFetching {
            override suspend fun fetch(requestUrl: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt {
                assertEquals(url, requestUrl)
                fetchCalls++
                return HttpFetchAttempt.Started(
                    statusCode = 200,
                    mimeType = "image/gif",
                    expectedContentLength = bytes.size.toLong(),
                    closeResponse = {},
                    chunks = { receive -> receive(bytes) },
                )
            }
        }
        var content: AppChatContent? = null
        try {
            storage.preferences.set(AppStorageKey.linkPreviewsEnabled, true)
            val preferences = DataStoreLinkPreviewPreferencesSource.create(storage.preferences, scope)
            val owner = AppChatContent(
                context = context,
                store = store,
                http = http,
                preferences = preferences,
                preferenceStore = storage.preferences,
                initialPreferences = storage.preferences.snapshot(),
                scope = scope,
                imageSafety = { true },
            )
            content = owner

            val mounted = assertIs<ChatImage.Decoded>(owner.inline(url))
            assertIs<GifDecodedImageHandle>(mounted.handle)
            val shared = assertIs<ChatImage.Decoded>(owner.inline(url))
            assertSame(mounted.encodedData, shared.encodedData)
            assertContentEquals(bytes, owner.shareData(mounted))

            owner.trimMemory()
            assertContentEquals(bytes, owner.shareData(mounted))
            assertEquals(1, fetchCalls)
        } finally {
            withContext(NonCancellable) {
                lifetime.cancelAndJoin()
                try {
                    content?.close()
                } finally {
                    try {
                        store.close()
                    } finally {
                        try {
                            database.close()
                        } finally {
                            storage.close()
                        }
                    }
                }
            }
        }
    }

    private fun gif(): ByteArray = "GIF89a".toByteArray(Charsets.US_ASCII) + byteArrayOf(
        1, 0, 1, 0, 0x80.toByte(), 0, 0, 0, 0, 0, 0xff.toByte(), 0, 0,
        0x21, 0xf9.toByte(), 4, 4, 1, 0, 0, 0,
        0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0,
        0x21, 0xf9.toByte(), 4, 4, 10, 0, 0, 0,
        0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x4c, 1, 0, 0x3b,
    )
}

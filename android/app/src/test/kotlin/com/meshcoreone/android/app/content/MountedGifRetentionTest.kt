// AndroidOnly: WP-218 verify GIF byte retention through the actual AppChatContent image producer.
package com.meshcoreone.android.app.content

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import com.meshcoreone.android.app.container.AppChatContent
import com.meshcoreone.android.app.container.ChatImage
import com.meshcoreone.android.app.navigation.AppMessageContent
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MountedGifRetentionTest {
    @get:Rule val temporary = TemporaryFolder()
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activityController: ActivityController<ComponentActivity>

    @Before
    fun createActivity() {
        activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    }

    @After
    fun destroyActivity() {
        activityController.pause().stop().destroy()
    }

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
        val lifetime = SupervisorJob()
        val scope = CoroutineScope(lifetime + Dispatchers.Main.immediate)
        val url = "https://8.8.8.8/mounted.gif"
        val bytes = gif()
        var fetchCalls = 0
        val http = object : BoundedHttpFetching {
            override suspend fun fetch(requestUrl: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt {
                if (requestUrl != url) return HttpFetchAttempt.Failed("Image test does not fetch malware feeds")
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
            storage.preferences.set(AppStorageKey.linkPreviewsAutoResolveDM, false)
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

            val cacheOnly = assertNotNull(owner.cachedInline(url))
            assertSame(mounted.handle, cacheOnly.handle)
            assertSame(mounted.encodedData, cacheOnly.encodedData)
            assertEquals(1, fetchCalls)

            val message = MessageDTO(
                radioId = RadioId(java.util.UUID.randomUUID()),
                text = url,
                timestamp = 1u,
            )
            val sharedBytes = CompletableDeferred<ByteArray>()
            compose.runOnUiThread {
                activityController.get().setContent {
                    MeshCoreTheme(motionScale = 0f) {
                        AppMessageContent(owner, message, { operation, error ->
                            throw AssertionError(operation, error)
                        }, onMap = {}, onShareImage = {
                            sharedBytes.complete(it)
                            Unit
                        })
                    }
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(url, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(url, useUnmergedTree = true).assertExists()

            owner.trimMemory()
            compose.onNodeWithContentDescription(url, useUnmergedTree = true).assertExists()
            compose.onNodeWithContentDescription(url, useUnmergedTree = true).performClick()
            compose.onNodeWithText(
                context.getString(AppChatsStrings.chatsImageViewerShare),
                useUnmergedTree = true,
            ).performClick()
            compose.waitUntil(10_000) {
                sharedBytes.isCompleted
            }
            assertContentEquals(bytes, sharedBytes.await())
            assertEquals(1, fetchCalls)
        } finally {
            withContext(NonCancellable) {
                compose.runOnUiThread { activityController.get().setContent {} }
                try {
                    try {
                        content?.close()
                    } finally {
                        lifetime.cancelAndJoin()
                    }
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

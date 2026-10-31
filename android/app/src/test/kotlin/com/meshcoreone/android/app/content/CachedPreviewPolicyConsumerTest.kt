// AndroidOnly: WP-218 verify persisted preview rehydration through the app policy consumer.
package com.meshcoreone.android.app.content

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.AppChatContent
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.LinkPreviewDataDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import com.meshcoreone.android.core.services.rendering.EnvInputs
import com.meshcoreone.android.core.services.rendering.PreviewLoadState
import com.meshcoreone.android.feature.chats.composer.LinkPreviewCardState
import com.meshcoreone.android.feature.chats.composer.LinkPreviewOutcome
import com.meshcoreone.android.feature.chats.composer.LinkPreviewStateHolder
import com.meshcoreone.android.feature.chats.composer.MalwareDomainPort
import com.meshcoreone.android.feature.chats.composer.UrlSafetyPort
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class CachedPreviewPolicyConsumerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `persisted previews rehydrate for both conversation types while automatic fetch is disabled`() = runTest {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val noBackupDirectory = temporary.newFolder()
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = noBackupDirectory
        }
        val storage = MeshCoreStorage.get(context)
        val database = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries().build()
        val store = RoomPersistenceStore(database, backgroundScope)
        var previewReads = 0
        val countedStore = object : PersistenceStoreProtocol by store {
            override suspend fun fetchLinkPreview(url: String): LinkPreviewDataDTO? {
                previewReads++
                return store.fetchLinkPreview(url)
            }
        }
        val lifetime = SupervisorJob(requireNotNull(backgroundScope.coroutineContext[Job]))
        val scope = CoroutineScope(backgroundScope.coroutineContext + lifetime)
        val url = "https://cached.example/article"
        val htmlPageUrl = "https://cached.example/landing.jpg"
        var fetchCalls = 0
        val http = object : BoundedHttpFetching {
            override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt {
                fetchCalls++
                return HttpFetchAttempt.Failed("unexpected network fetch")
            }
        }
        val contents = mutableListOf<AppChatContent>()
        try {
            storage.preferences.set(AppStorageKey.linkPreviewsEnabled, true)
            storage.preferences.set(AppStorageKey.linkPreviewsAutoResolveDM, false)
            storage.preferences.set(AppStorageKey.linkPreviewsAutoResolveChannels, false)
            store.saveLinkPreview(
                LinkPreviewDataDTO(
                    url,
                    title = "Persisted preview",
                    imageData = Bytes(NativeImageFixtures.png(2, 2)),
                    iconData = Bytes(NativeImageFixtures.png(2, 2)),
                ),
            )
            store.saveLinkPreview(LinkPreviewDataDTO(htmlPageUrl, title = "Persisted HTML page"))
            for (isChannel in listOf(false, true)) {
                val owner = newContent(context, countedStore, http, storage, scope).also(contents::add)
                val message = MessageDTO(
                    radioId = RadioId(java.util.UUID.randomUUID()),
                    channelIndex = if (isChannel) 1u.toUByte() else null,
                    text = url,
                    timestamp = 1u,
                )
                val rendered = owner.renderContent(message, EnvInputs.DEFAULT)
                assertEquals(PreviewLoadState.LOADED, rendered.state)
                assertEquals("Persisted preview", rendered.preview?.title)

                if (!isChannel) {
                    val missing = message.copy(text = "https://uncached.example/missing")
                    val readsBeforeMissing = previewReads
                    repeat(3) { owner.renderContent(missing, EnvInputs.DEFAULT) }
                    assertEquals(readsBeforeMissing + 1, previewReads)
                }

                val htmlPage = owner.renderContent(message.copy(text = htmlPageUrl), EnvInputs.DEFAULT)
                assertEquals(PreviewLoadState.LOADED, htmlPage.state)
                assertEquals("Persisted HTML page", htmlPage.preview?.title)
                assertFalse(htmlPage.inline)

                val holder = LinkPreviewStateHolder(
                    url,
                    isChannel,
                    owner.previews(isChannel),
                    UrlSafetyPort { false },
                    MalwareDomainPort { false },
                    backgroundScope,
                )
                holder.start().join()
                val loaded = assertIs<LinkPreviewCardState.Loaded>(holder.state.value)
                assertEquals(url, loaded.data.url)
                assertEquals("Persisted preview", loaded.data.title)
                holder.close()

                val port = owner.previews(isChannel)
                assertEquals(LinkPreviewOutcome.Disabled, port.preview("https://new.example/article", isChannel))
            }

            val imageOwner = newContent(context, countedStore, http, storage, scope).also(contents::add)
            assertNotNull(imageOwner.images.load("mc1-preview-hero:$url"))
            assertNotNull(imageOwner.images.load("mc1-preview-icon:$url"))

            storage.preferences.set(AppStorageKey.linkPreviewsEnabled, false)
            testScheduler.runCurrent()
            val disabledOwner = newContent(context, countedStore, http, storage, scope).also(contents::add)
            val readsBeforeDisabledRender = previewReads
            val disabled = disabledOwner.renderContent(
                MessageDTO(
                    radioId = RadioId(java.util.UUID.randomUUID()),
                    text = "https://uncached.example/disabled.jpg",
                    timestamp = 2u,
                ),
                EnvInputs.DEFAULT,
            )
            assertEquals(PreviewLoadState.DISABLED, disabled.state)
            assertEquals(readsBeforeDisabledRender, previewReads)
            assertEquals(0, fetchCalls)
        } finally {
            withContext(NonCancellable) {
                lifetime.cancelAndJoin()
                try {
                    contents.asReversed().forEach(AppChatContent::close)
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

    private suspend fun newContent(
        context: Context,
        store: PersistenceStoreProtocol,
        http: BoundedHttpFetching,
        storage: MeshCoreStorage,
        scope: CoroutineScope,
    ) = AppChatContent(
        context = context,
        store = store,
        http = http,
        preferences = DataStoreLinkPreviewPreferencesSource.create(storage.preferences, scope),
        preferenceStore = storage.preferences,
        initialPreferences = storage.preferences.snapshot(),
        scope = scope,
        imageSafety = { true },
    )
}

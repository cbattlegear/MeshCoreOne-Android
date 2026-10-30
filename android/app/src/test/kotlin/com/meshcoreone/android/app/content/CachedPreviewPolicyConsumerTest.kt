// AndroidOnly: WP-218 verify persisted preview rehydration through the app policy consumer.
package com.meshcoreone.android.app.content

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.AppChatContent
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.LinkPreviewDataDTO
import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import com.meshcoreone.android.feature.chats.composer.LinkPreviewCardState
import com.meshcoreone.android.feature.chats.composer.LinkPreviewOutcome
import com.meshcoreone.android.feature.chats.composer.LinkPreviewStateHolder
import com.meshcoreone.android.feature.chats.composer.MalwareDomainPort
import com.meshcoreone.android.feature.chats.composer.UrlSafetyPort
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
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
        val lifetime = SupervisorJob(requireNotNull(backgroundScope.coroutineContext[Job]))
        val scope = CoroutineScope(backgroundScope.coroutineContext + lifetime)
        val url = "https://cached.example/article"
        var fetchCalls = 0
        val http = object : BoundedHttpFetching {
            override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt {
                fetchCalls++
                return HttpFetchAttempt.Failed("unexpected network fetch")
            }
        }
        var content: AppChatContent? = null
        try {
            storage.preferences.set(AppStorageKey.linkPreviewsEnabled, true)
            storage.preferences.set(AppStorageKey.linkPreviewsAutoResolveDM, false)
            storage.preferences.set(AppStorageKey.linkPreviewsAutoResolveChannels, false)
            store.saveLinkPreview(LinkPreviewDataDTO(url, title = "Persisted preview"))
            val owner = AppChatContent(
                context = context,
                store = store,
                http = http,
                preferences = DataStoreLinkPreviewPreferencesSource.create(storage.preferences, scope),
                preferenceStore = storage.preferences,
                initialPreferences = storage.preferences.snapshot(),
                scope = scope,
                imageSafety = { true },
            )
            content = owner

            for (isChannel in listOf(false, true)) {
                val port = owner.previews(isChannel)
                val cached = requireNotNull(port.cachedPreview(url))
                assertEquals(url, cached.url)
                assertEquals("Persisted preview", cached.title)
                assertEquals(LinkPreviewOutcome.Disabled, port.preview("https://new.example/article", isChannel))

                val holder = LinkPreviewStateHolder(
                    url,
                    isChannel,
                    port,
                    UrlSafetyPort { false },
                    MalwareDomainPort { false },
                    backgroundScope,
                )
                holder.start()
                runCurrent()
                assertEquals(LinkPreviewCardState.Loaded(cached), holder.state.value)
                holder.close()
            }
            assertEquals(0, fetchCalls)
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
}

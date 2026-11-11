// AndroidOnly: WP-218 preview consent before DNS, manual production fetch and Room rehydration.
package com.meshcoreone.android.app.content

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.AppChatContent
import com.meshcoreone.android.app.navigation.AppMessageContent
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.datastore.PreferenceKey
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import com.meshcoreone.android.core.services.content.UrlSafetyChecker
import com.meshcoreone.android.feature.chats.composer.UrlSafetyPort
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MountedPreviewNetworkConsentTest {
    @get:Rule val temporary = TemporaryFolder()
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>

    @Before
    fun createActivity() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    }

    @After
    fun destroyActivity() {
        activity.pause().stop().destroy()
    }

    @Test
    fun `automatic off preserves manual load offline without a DNS lookup for either conversation type`() = runTest {
        for (isChannel in listOf(false, true)) withContent(isChannel) { fixture ->
            val url = "https://offline.example/article"
            val dns = AtomicInteger()
            val safety = UrlSafetyPort { requested ->
                assertEquals(url, requested)
                UrlSafetyChecker.isSafe(requested) {
                    dns.incrementAndGet()
                    emptyList()
                }
            }
            fixture.mount(url, safety)
            val tapLabel = AppChatsStrings.chatsPreviewTapAccessibility(fixture.context.resources, "offline.example")
            compose.waitUntil(10_000) {
                dns.get() > 0 || compose.onAllNodesWithContentDescription(tapLabel).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(0, dns.get(), "automatic network consent includes DNS, not just HTTP")
            compose.onNodeWithContentDescription(tapLabel).assertHasClickAction().performClick()
            compose.waitUntil(10_000) { dns.get() > 0 }
            assertEquals(1, dns.get())
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(tapLabel).fetchSemanticsNodes().isEmpty()
            }
            assertTrue(fixture.requests.isEmpty(), "manual loading still fails closed when DNS is unavailable")
            assertNull(fixture.store.fetchLinkPreview(url))
        }
    }

    @Test
    fun `manual producer persists and fresh mounted cache rehydrates with automatic off and master changes`() = runTest {
        for (isChannel in listOf(false, true)) withContent(isChannel) { fixture ->
            val url = "https://8.8.8.8/article"
            val safetyCalls = AtomicInteger()
            val safety = UrlSafetyPort { requested ->
                safetyCalls.incrementAndGet()
                UrlSafetyChecker.isSafe(requested) { error("A public IP literal needs no DNS") }
            }
            fixture.mount(url, safety)
            val tapLabel = AppChatsStrings.chatsPreviewTapAccessibility(fixture.context.resources, "8.8.8.8")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(tapLabel).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(0, safetyCalls.get())
            assertTrue(fixture.requests.isEmpty())
            compose.onNodeWithContentDescription(tapLabel).performClick()
            val loaded = AppChatsStrings.chatsLinkPreviewAccessibilityLabel(
                fixture.context.resources, "User approved preview", "8.8.8.8",
            )
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(loaded).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, safetyCalls.get())
            assertEquals(listOf(url), fixture.requests.toList())
            assertEquals("User approved preview", fixture.store.fetchLinkPreview(url)?.title)

            fixture.mount(url, safety, freshOwner = true)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(loaded).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(loaded).assertHasClickAction()
            assertEquals(1, safetyCalls.get(), "fresh Room rehydration must not revalidate DNS")
            assertEquals(listOf(url), fixture.requests.toList())

            fixture.storage.preferences.set(AppStorageKey.linkPreviewsEnabled, false)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(loaded).fetchSemanticsNodes().isEmpty()
            }
            fixture.storage.preferences.set(AppStorageKey.linkPreviewsEnabled, true)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription(loaded).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(1, safetyCalls.get())
            assertEquals(listOf(url), fixture.requests.toList())
        }
    }

    @Test
    fun `authorized automatic lookup still rejects a private DNS target before HTTP`() = runTest {
        for (isChannel in listOf(false, true)) withContent(isChannel) { fixture ->
            fixture.storage.preferences.set(
                if (isChannel) AppStorageKey.linkPreviewsAutoResolveChannels else AppStorageKey.linkPreviewsAutoResolveDM,
                true,
            )
            val url = "https://private.example/article"
            val dns = AtomicInteger()
            val owner = fixture.mount(url, UrlSafetyPort { requested ->
                UrlSafetyChecker.isSafe(requested) {
                    dns.incrementAndGet()
                    listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1)))
                }
            })
            assertEquals(listOf(url), owner.media(url).map { it.url })
            assertTrue(owner.rendering.value[
                if (isChannel) AppStorageKey.linkPreviewsAutoResolveChannels else AppStorageKey.linkPreviewsAutoResolveDM
            ], "automatic preference must be observed before mounting")
            val tap = AppChatsStrings.chatsPreviewTapAccessibility(fixture.context.resources, "private.example")
            compose.waitUntil(10_000) {
                dns.get() > 0 || compose.onAllNodesWithContentDescription(tap).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            assertEquals(1, dns.get(), "one authorized safety lookup for channel=$isChannel")
            compose.onNodeWithContentDescription(
                AppChatsStrings.chatsPreviewTapAccessibility(fixture.context.resources, "private.example"),
            ).assertDoesNotExist()
            assertTrue(fixture.requests.isEmpty())
            assertNull(fixture.store.fetchLinkPreview(url))
        }
    }

    private inner class Fixture(
        val context: Context,
        val storage: MeshCoreStorage,
        val store: RoomPersistenceStore,
        val scope: CoroutineScope,
        val isChannel: Boolean,
    ) {
        val requests = CopyOnWriteArrayList<String>()
        val owners = mutableListOf<AppChatContent>()
        private val http = object : BoundedHttpFetching {
            override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt {
                requests += url
                check(url == "https://8.8.8.8/article") { "Unexpected preview request: $url" }
                val body = """<meta property="og:title" content="User approved preview">""".toByteArray()
                return HttpFetchAttempt.Started(
                    statusCode = 200,
                    mimeType = "text/html",
                    expectedContentLength = body.size.toLong(),
                    closeResponse = {},
                    chunks = { receive -> receive(body) },
                )
            }
        }
        private suspend fun owner() = AppChatContent(
            context, store, http,
            DataStoreLinkPreviewPreferencesSource.create(storage.preferences, scope),
            storage.preferences, storage.preferences.snapshot(), scope,
        ).also(owners::add)

        suspend fun mount(url: String, safety: UrlSafetyPort, freshOwner: Boolean = false): AppChatContent {
            val content = if (freshOwner || owners.isEmpty()) owner() else owners.last()
            val message = MessageDTO(
                radioId = RadioId(UUID.randomUUID()),
                text = url,
                channelIndex = if (isChannel) 1u.toUByte() else null,
                timestamp = 1u,
            )
            assertEquals(isChannel, message.isChannelMessage)
            compose.runOnUiThread {
                activity.get().setContent {
                    MeshCoreTheme(motionScale = 0f) {
                        AppMessageContent(
                            content, message,
                            { operation, problem -> throw AssertionError(operation, problem) },
                            onMap = {},
                            malwareCheck = { false },
                            previewUrlSafety = safety,
                        )
                    }
                }
            }
            return content
        }
    }

    private suspend fun TestScope.withContent(isChannel: Boolean, block: suspend (Fixture) -> Unit) {
        val directory = temporary.newFolder()
        val application = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getFilesDir(): File = directory
        }
        val storage = MeshCoreStorage.get(context)
        storage.preferences.set(
            PreferenceKey.DecimalKey("MalwareDomainFilter.lastRefresh", 0.0),
            System.currentTimeMillis() / 1000.0,
        )
        val database = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries().build()
        val store = RoomPersistenceStore(database, backgroundScope)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val fixture = Fixture(context, storage, store, scope, isChannel)
        try {
            storage.preferences.set(AppStorageKey.linkPreviewsEnabled, true)
            storage.preferences.set(AppStorageKey.linkPreviewsAutoResolveDM, isChannel)
            storage.preferences.set(AppStorageKey.linkPreviewsAutoResolveChannels, !isChannel)
            block(fixture)
        } finally {
            compose.runOnUiThread { activity.get().setContent {} }
            compose.waitForIdle()
            fixture.owners.asReversed().forEach(AppChatContent::close)
            scope.cancel()
            store.close()
            database.close()
            storage.close()
        }
    }
}

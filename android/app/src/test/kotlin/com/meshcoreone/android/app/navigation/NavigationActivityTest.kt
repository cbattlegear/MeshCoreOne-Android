// AndroidOnly: WP-302 Actual preserved launcher identity, retained host recreation and private/public process-restoration assertions.
package com.meshcoreone.android.app.navigation

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.app.NavigationHostViewModel
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.platform.notifications.messaging.AndroidMessagingNotificationDelivery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "w834dp-h900dp-mdpi")
class NavigationActivityTest {
    @get:Rule val executionName = TestName()
    @Before fun bindActualInputs() = emitNavigationExecutionBinding(
        javaClass.name, executionName.methodName, Build.VERSION.SDK_INT.toString(),
    )
    @Test fun configurationRecreationRetainsTheActualHostAndPrivateSelectedDetail() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val before = ViewModelProvider(controller.get())[NavigationHostViewModel::class.java]
            val contact = ContactDTO(radioId = RadioId(UUID.randomUUID()), publicKey = Bytes(ByteArray(32)),
                name = "Synthetic private selection", lastHeardTimestamp = null)
            before.navigation.navigateToContactDetail(contact)
            controller.recreate()
            val after = ViewModelProvider(controller.get())[NavigationHostViewModel::class.java]
            assertSame(before, after); assertEquals(contact, after.navigation.state.value.selectedContact)
            assertEquals("com.meshcoreone.android.MainActivity", controller.get().javaClass.name)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun actualColdHostRestoresPublicStacksButReportsRedactedPrivateSelection() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val saved = Bundle()
        try {
            val n = ViewModelProvider(first.get())[NavigationHostViewModel::class.java].navigation
            n.navigateToContactDetail(ContactDTO(radioId = RadioId(UUID.randomUUID()), publicKey = Bytes(ByteArray(32)),
                name = "Synthetic private selection", lastHeardTimestamp = null))
            n.navigateToSetting(SettingsDetail.LANGUAGE)
            first.saveInstanceState(saved)
        } finally { first.pause().stop().destroy() }
        val second = Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume().visible()
        try {
            val state = ViewModelProvider(second.get())[NavigationHostViewModel::class.java].navigation.state.value
            assertEquals(AppTab.SETTINGS, state.selectedTab); assertEquals(SettingsDetail.LANGUAGE, state.selectedSetting)
            assertNull(state.selectedContact); assertEquals(NavigationFailure.PrivateSelectionNotRestored(1), state.failure)
            assertFalse(state.navigationReady)
        } finally { second.pause().stop().destroy() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Config(application = Application::class)
    @Test fun notificationContentIntentsSurviveHostRecreationWithoutReplayingAndNewTapsRemainDistinct() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val radio = RadioId(UUID.randomUUID())
        val contact = EntityKey(radio, UUID.randomUUID())
        val message = UUID.randomUUID()
        val response = NotificationResponse(NotificationPayload.DirectMessage(contact, message), null, null)
        val delivered = mutableListOf<NotificationResponse>()
        AndroidMessagingNotificationDelivery(context, scope) { delivered += it; true }
        val intent = Intent(context, MainActivity::class.java)
            .setAction("com.meshcoreone.android.notification.OPEN")
            .setData(Uri.parse("meshcore-notification://action/example/OPEN"))
            .putExtra("mc1.notification.type", "direct")
            .putExtra("mc1.notification.contact.radio", radio.value.toString())
            .putExtra("mc1.notification.contact.id", contact.id.toString())
            .putExtra("mc1.notification.message", message.toString())
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup().visible()
        try {
            testScheduler.runCurrent()
            assertEquals(listOf(response), delivered)
            controller.recreate()
            testScheduler.runCurrent()
            assertEquals(listOf(response), delivered)

            controller.newIntent(Intent(intent))
            testScheduler.runCurrent()
            assertEquals(listOf(response, response), delivered)
            controller.recreate()
            testScheduler.runCurrent()
            assertEquals(listOf(response, response), delivered)
        } finally {
            controller.pause().stop().destroy()
            scope.cancel()
            testScheduler.runCurrent()
        }
    }
}

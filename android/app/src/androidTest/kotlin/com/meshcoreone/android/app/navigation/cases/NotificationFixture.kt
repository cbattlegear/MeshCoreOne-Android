// AndroidOnly: WP-302 Deterministic real-policy callback host; navigation must never call platform delivery or create a connection.
package com.meshcoreone.android.app.navigation.cases

import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.app.navigation.NavigationLookup
import com.meshcoreone.android.app.navigation.NavigationOutcome
import com.meshcoreone.android.app.navigation.NotificationNavigationBinding
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.notifications.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.services.notifications.NotificationService
import com.meshcoreone.android.core.services.notifications.NotificationUnreadCounting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

class NotificationFixture(
    scope: CoroutineScope,
    radio: RadioId,
    coordinator: NavigationCoordinator,
    lookup: NavigationLookup,
    connectedDevice: () -> DeviceDTO? = { null },
) : AutoCloseable {
    val outcomes = mutableListOf<NavigationOutcome>()
    val service = NotificationService(
        radio, NoDelivery,
        object : NotificationPreferencesPort {
            override val preferences = MutableStateFlow(NotificationPreferences(
                false, false, false, false, false, false, false, false, false, false, false,
            ))
            override suspend fun update(preferences: NotificationPreferences): Unit = error("Unexpected preference write")
        },
        object : AppStateProvider {
            override suspend fun isInForeground(): Boolean = error("Unexpected foreground query")
        },
        NotificationUnreadCounting { error("Unexpected unread-count query") }, scope,
    )
    private val binding = NotificationNavigationBinding(service, coordinator, lookup, connectedDevice, outcomes::add)
    override fun close() = binding.close()

    private object NoDelivery : NotificationDeliveryPort {
        override suspend fun authorizationStatus(): NotificationAuthorizationStatus = error("Unexpected permission query")
        override suspend fun requestAuthorization(): Boolean = error("Unexpected permission request")
        override suspend fun registerCategories(categories: SnapshotList<NotificationCategoryDefinition>): Unit =
            error("Unexpected category registration")
        override suspend fun post(request: NotificationRequest): NotificationPostResult = error("Unexpected notification post")
        override suspend fun setBadgeCount(count: Long): Unit = error("Unexpected badge write")
        override suspend fun deliveredNotifications(): SnapshotList<DeliveredNotification> = error("Unexpected platform read")
        override suspend fun removeDelivered(ids: SnapshotList<NotificationId>): Unit = error("Unexpected platform removal")
        override suspend fun removeDeliveredSnapshot(
            selected: SnapshotList<DeliveredNotification>,
        ): SnapshotList<com.meshcoreone.android.core.contracts.notifications.SnapshotRemovalOutcome> =
            error("Unexpected platform snapshot removal")
    }
}

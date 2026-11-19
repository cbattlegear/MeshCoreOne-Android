// PortedFrom: MC1/State/NavigationCoordinator.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: Caller owns service/connection lifetimes and serializes registration/close; this adapter owns no scope.
package com.meshcoreone.android.app.navigation

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.services.notifications.NotificationService
import com.meshcoreone.android.platform.notifications.messaging.NotificationActionCommit
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

internal class NotificationNavigationReceipt : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NotificationNavigationReceipt>
    var callbackStarted: Boolean = false
    var outcome: NavigationOutcome? = null
}

class NotificationNavigationBinding(
    private val service: NotificationService,
    private val coordinator: NavigationCoordinator,
    lookup: NavigationLookup,
    connectedDevice: () -> DeviceDTO?,
    onOutcome: (NavigationOutcome) -> Unit,
    isCurrentSession: () -> Boolean = { true },
) : AutoCloseable {
    @Volatile private var active = true
    private val generation = coordinator.currentState.generation
    private val route: suspend (NotificationNavigationRequest) -> Unit = { request ->
        currentCoroutineContext()[NotificationNavigationReceipt]?.callbackStarted = true
        val outcome = coordinator.handleNotificationRequest(request, lookup, generation) { active && isCurrentSession() }
        val context = currentCoroutineContext()
        context[NotificationNavigationReceipt]?.outcome = outcome
        if (outcome == NavigationOutcome.Navigated || outcome is NavigationOutcome.Fallback ||
            outcome is NavigationOutcome.Failed && outcome.failure != NavigationFailure.StaleGeneration
        ) context[NotificationActionCommit]?.navigationCommitted()
        onOutcome(outcome)
    }
    private val direct: suspend (EntityKey) -> Unit = { route(NotificationNavigationRequest.Direct(it)) }
    private val newContact: suspend (EntityKey) -> Unit = {
        currentCoroutineContext()[NotificationNavigationReceipt]?.callbackStarted = true
        route(NotificationNavigationRequest.NewContact(it, connectedDevice()?.manualAddContacts == true))
    }
    private val channel: suspend (RadioId, UByte) -> Unit = { radio, index ->
        route(NotificationNavigationRequest.Channel(radio, index))
    }
    private val room: suspend (EntityKey) -> Unit = { route(NotificationNavigationRequest.Room(it)) }
    private val reaction: suspend (EntityKey?, UByte?, RadioId?, UUID) -> Unit = { contact, index, radio, message ->
        route(NotificationNavigationRequest.Reaction(contact, index, radio, message))
    }

    init {
        coordinator.retireBinding {
            service.onNotificationTapped = direct
            service.onNewContactNotificationTapped = newContact
            service.onChannelNotificationTapped = channel
            service.onRoomNotificationTapped = room
            service.onReactionNotificationTapped = reaction
        }
    }

    override fun close() {
        coordinator.retireBinding {
            active = false
            if (service.onNotificationTapped === direct) service.onNotificationTapped = null
            if (service.onNewContactNotificationTapped === newContact) service.onNewContactNotificationTapped = null
            if (service.onChannelNotificationTapped === channel) service.onChannelNotificationTapped = null
            if (service.onRoomNotificationTapped === room) service.onRoomNotificationTapped = null
            if (service.onReactionNotificationTapped === reaction) service.onReactionNotificationTapped = null
        }
    }
}

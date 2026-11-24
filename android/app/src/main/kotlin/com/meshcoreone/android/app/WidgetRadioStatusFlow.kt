// AndroidOnly: WP-403 Generation-bound metrics for the home-screen widget.
package com.meshcoreone.android.app

import com.meshcoreone.android.app.container.AppContainer
import com.meshcoreone.android.app.state.BatteryMonitor
import com.meshcoreone.android.app.state.BatteryServices
import com.meshcoreone.android.app.state.WidgetBatteryReading
import com.meshcoreone.android.core.contracts.domain.BleLinkPhase
import com.meshcoreone.android.core.contracts.domain.ConnectionIssue
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.platform.widgets.WidgetConnectionView
import com.meshcoreone.android.platform.widgets.WidgetRadioMetrics
import com.meshcoreone.android.platform.widgets.WidgetRadioStatus
import java.time.Duration
import java.time.Instant
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformLatest

@OptIn(ExperimentalCoroutinesApi::class)
internal fun AppContainer.widgetRadioStatus(): Flow<WidgetConnectionView> =
    combine(connectionManager.snapshot, sessions.currentSessions) { snapshot, session -> snapshot to session }
        .transformLatest { (snapshot, session) ->
            val base = WidgetConnectionView(
                status = when (snapshot.state) {
                    DeviceConnectionState.DISCONNECTED -> WidgetRadioStatus.DISCONNECTED
                    DeviceConnectionState.CONNECTING -> WidgetRadioStatus.CONNECTING
                    DeviceConnectionState.CONNECTED -> WidgetRadioStatus.CONNECTED
                    DeviceConnectionState.SYNCING -> WidgetRadioStatus.SYNCING
                    DeviceConnectionState.READY -> WidgetRadioStatus.READY
                },
                hasSavedDevice = connectionPort.lastConnectedDeviceId != null,
                reconnecting = snapshot.blePhase == BleLinkPhase.AUTO_RECONNECTING,
                permissionDenied = snapshot.issue is ConnectionIssue.PermissionDenied,
            )
            emit(base)
            if (base.status != WidgetRadioStatus.READY || base.permissionDenied || base.reconnecting ||
                session == null || session.token != snapshot.token || session.isTornDown ||
                session.transportType == TransportType.SIMULATOR
            ) return@transformLatest

            val unread = (processStore as? RoomPersistenceStore)
                ?.observeUnreadCounts(session.token.radioId)
                ?.map { counts -> Math.addExact(Math.addExact(counts.contacts, counts.channels), counts.rooms) }
            val unreadOrUnknown: Flow<Long?> = unread ?: flowOf(null)
            val availableUnread = unreadOrUnknown.availableWidgetUnread()

            combine(
                freshWidgetBattery(appState.batteryMonitor.widgetBatteryFlow, session.batteryServices),
                session.rxLogService.packetRates(),
                availableUnread,
                ::WidgetRadioMetrics,
            ).distinctUntilChanged().conflate().collect { metrics ->
                // Recheck synchronously before publication; an old sample may be in the conflated slot.
                val latest = connectionManager.snapshot.value
                if (latest.token == session.token && latest.state == DeviceConnectionState.READY &&
                    latest.issue !is ConnectionIssue.PermissionDenied &&
                    latest.blePhase != BleLinkPhase.AUTO_RECONNECTING &&
                    sessions.current === session && !session.isTornDown
                ) emit(base.copy(metrics = metrics))
                delay(WIDGET_UPDATE_INTERVAL_MILLIS)
            }
        }.buffer(0).distinctUntilChanged()

internal fun Flow<Long?>.availableWidgetUnread(): Flow<Long?> =
    onStart { emit(null) }
        .retryWhen { failure, _ ->
            if (failure !is PersistenceStoreException) return@retryWhen false
            widgetLog.log(Level.WARNING, "Widget unread count unavailable; retrying", failure)
            emit(null)
            delay(WIDGET_UPDATE_INTERVAL_MILLIS)
            true
        }.catch { failure ->
            if (failure !is ArithmeticException) throw failure
            widgetLog.log(Level.WARNING, "Widget unread count overflow", failure)
            emit(null)
        }.distinctUntilChanged()

@OptIn(ExperimentalCoroutinesApi::class)
internal fun freshWidgetBattery(
    readings: Flow<WidgetBatteryReading?>,
    source: BatteryServices,
    now: () -> Instant = Instant::now,
): Flow<Long?> = readings.transformLatest { reading ->
    val age = reading?.takeIf { it.source === source }?.let { Duration.between(it.measuredAt, now()) }
    val lifetime = Duration.ofSeconds(BatteryMonitor.BACKGROUND_BATTERY_INTERVAL_SECONDS)
    if (reading == null || age == null || age.isNegative || age >= lifetime) {
        emit(null)
    } else {
        emit(reading.percentage)
        delay(lifetime.minus(age).toMillis().coerceAtLeast(1L))
        emit(null)
    }
}.distinctUntilChanged()

private const val WIDGET_UPDATE_INTERVAL_MILLIS = 15_000L
private val widgetLog = Logger.getLogger("com.mc1.WidgetRadioStatusFlow")

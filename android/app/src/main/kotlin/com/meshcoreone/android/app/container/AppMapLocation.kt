// PortedFrom: MC1/Services/LocationService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.meshcoreone.android.app.content.LocationManagerLocationProducing
import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.services.content.LocationAuthorizationStatus
import com.meshcoreone.android.core.services.content.LocationService
import com.meshcoreone.android.feature.map.MapLocationAuthorization
import com.meshcoreone.android.feature.map.MapLocationPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal class AppMapLocation(
    private val producer: LocationManagerLocationProducing,
    scope: CoroutineScope,
) : MapLocationPort {
    private val service = LocationService(producer)
    override val authorization = producer.authorizationStatus.map(::mapAuthorization)
        .stateIn(scope, SharingStarted.Eagerly, mapAuthorization(producer.authorizationStatus.value))

    override fun refreshAuthorization(): MapLocationAuthorization {
        producer.refreshAuthorization()
        return mapAuthorization(producer.authorizationStatus.value)
    }

    @Composable
    override fun rememberPermissionRequester(onResult: (Boolean) -> Unit): () -> Unit {
        val latest by rememberUpdatedState(onResult)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val granted = grants.values.any { it }
            producer.reportPermissionResult(granted)
            latest(granted)
        }

        return remember(launcher) {
            { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
        }
    }

    private fun mapAuthorization(status: LocationAuthorizationStatus): MapLocationAuthorization = when (status) {
        LocationAuthorizationStatus.NOT_DETERMINED -> MapLocationAuthorization.NOT_DETERMINED
        LocationAuthorizationStatus.AUTHORIZED_ALWAYS, LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE ->
            MapLocationAuthorization.AUTHORIZED
        LocationAuthorizationStatus.DENIED, LocationAuthorizationStatus.RESTRICTED -> MapLocationAuthorization.DENIED
    }

    override suspend fun requestLocation(): GeoPoint {
        val coordinate = service.requestCurrentLocation()
        return GeoPoint(coordinate.latitude, coordinate.longitude)
    }
}

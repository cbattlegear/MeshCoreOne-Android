// AndroidOnly: WP-101 Prizm Mobile Observer privacy screen state: privacy zone form and the delete-my-data request.
package com.meshcoreone.android.feature.settings.prizm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PrizmZoneError { LATITUDE, LONGITUDE, RADIUS }

data class PrizmZoneForm(
    val latitude: String = "",
    val longitude: String = "",
    val radius: String = DEFAULT_RADIUS_TEXT,
    val errors: Set<PrizmZoneError> = emptySet(),
    val locationUnavailable: Boolean = false,
) {
    companion object { const val DEFAULT_RADIUS_TEXT = "300" }
}

sealed interface PrizmDeleteStatus {
    data object Idle : PrizmDeleteStatus
    data object Working : PrizmDeleteStatus
    data class Finished(val result: PrizmDeleteResult) : PrizmDeleteStatus
}

class PrizmPrivacyStateHolder(private val port: PrizmObserverPort, private val scope: CoroutineScope) {
    val state: StateFlow<PrizmObserverState> get() = port.state
    private val formState = MutableStateFlow(PrizmZoneForm())
    private val deleteState = MutableStateFlow<PrizmDeleteStatus>(PrizmDeleteStatus.Idle)
    val form: StateFlow<PrizmZoneForm> = formState.asStateFlow()
    val delete: StateFlow<PrizmDeleteStatus> = deleteState.asStateFlow()

    fun setLatitude(text: String) = formState.update { it.copy(latitude = text, errors = it.errors - PrizmZoneError.LATITUDE) }
    fun setLongitude(text: String) = formState.update { it.copy(longitude = text, errors = it.errors - PrizmZoneError.LONGITUDE) }
    fun setRadius(text: String) = formState.update { it.copy(radius = text, errors = it.errors - PrizmZoneError.RADIUS) }

    /** Fills latitude and longitude from the phone's current location (six decimals, the precision uploads use). */
    fun useCurrentLocation() {
        scope.launch {
            val here = try { port.currentLocation() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            formState.update {
                if (here == null) it.copy(locationUnavailable = true)
                else it.copy(
                    latitude = "%.6f".format(java.util.Locale.ROOT, here.latitude),
                    longitude = "%.6f".format(java.util.Locale.ROOT, here.longitude),
                    errors = it.errors - PrizmZoneError.LATITUDE - PrizmZoneError.LONGITUDE, locationUnavailable = false,
                )
            }
        }
    }

    /** Validates the form; on success adds the zone (radius defaults to 300 m when left empty) and clears the form. */
    fun addZone(): Boolean {
        val current = formState.value
        val lat = current.latitude.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 }
        val lon = current.longitude.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 }
        val radiusText = current.radius.trim().ifEmpty { PrizmZoneForm.DEFAULT_RADIUS_TEXT }
        val radius = radiusText.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        val errors = buildSet {
            if (lat == null) add(PrizmZoneError.LATITUDE)
            if (lon == null) add(PrizmZoneError.LONGITUDE)
            if (radius == null) add(PrizmZoneError.RADIUS)
        }
        if (errors.isNotEmpty() || lat == null || lon == null || radius == null) {
            formState.update { it.copy(errors = errors) }
            return false
        }
        port.addZone(lat, lon, radius)
        formState.value = PrizmZoneForm()
        return true
    }

    fun removeZone(id: String) = port.removeZone(id)

    fun deleteMyData() {
        if (deleteState.value == PrizmDeleteStatus.Working) return
        deleteState.value = PrizmDeleteStatus.Working
        scope.launch {
            val result = try {
                port.deleteMyData()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                PrizmDeleteResult.Failed(error.message ?: error.javaClass.simpleName)
            }
            deleteState.value = PrizmDeleteStatus.Finished(result)
        }
    }

    fun acknowledgeDelete() { deleteState.value = PrizmDeleteStatus.Idle }
}

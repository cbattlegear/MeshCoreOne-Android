// AndroidOnly: WP-315 App adapters binding the real elevation, RF and process-store services to feature-owned ports.
package com.meshcoreone.android.app.container

import android.content.Context
import android.util.Log
import com.meshcoreone.android.app.content.OpenMeteoElevationFetching
import com.meshcoreone.android.app.content.localizedDescription
import com.meshcoreone.android.app.state.AppState
import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.ElevationService
import com.meshcoreone.android.core.services.content.ElevationServiceError
import com.meshcoreone.android.core.services.content.GeoCoordinate
import com.meshcoreone.android.core.services.diagnostics.rf.RFCalculator
import com.meshcoreone.android.feature.tools.los.ClearanceStatus
import com.meshcoreone.android.feature.tools.los.ElevationSample
import com.meshcoreone.android.feature.tools.los.LineOfSightElevationSource
import com.meshcoreone.android.feature.tools.los.LineOfSightFeatureDependencies
import com.meshcoreone.android.feature.tools.los.LineOfSightFailureReporter
import com.meshcoreone.android.feature.tools.los.LineOfSightPathAnalyzer
import com.meshcoreone.android.feature.tools.los.ObstructionPoint
import com.meshcoreone.android.feature.tools.los.PathAnalysisResult
import com.meshcoreone.android.feature.tools.los.asLineOfSightContactSource
import kotlinx.coroutines.CancellationException

internal fun createLineOfSightFeatureDependencies(
    context: Context,
    http: BoundedHttpFetching,
    appState: () -> AppState?,
): LineOfSightFeatureDependencies {
    val elevation = AppLineOfSightElevationSource(context, ElevationService(OpenMeteoElevationFetching(http)))
    return LineOfSightFeatureDependencies(
        elevationSource = elevation,
        pathAnalyzer = AppLineOfSightPathAnalyzer,
        contactSource = { appState()?.offlineDataStore?.asLineOfSightContactSource() },
        radioId = { appState()?.currentRadioId },
        deviceFrequencyKHz = { appState()?.connectedDevice?.frequency },
        failureReporter = LineOfSightFailureReporter { operation, error ->
            Log.e("MeshCoreLineOfSight", "$operation failed", error)
        },
    )
}

private class AppLineOfSightElevationSource(
    private val context: Context,
    private val service: ElevationService,
) : LineOfSightElevationSource {
    override suspend fun fetchElevation(coordinate: com.meshcoreone.android.core.model.Coordinate): Double =
        translateElevationFailure { service.fetchElevation(coordinate.toService()) }

    override suspend fun fetchElevations(
        path: List<com.meshcoreone.android.core.model.Coordinate>,
    ): List<ElevationSample> = translateElevationFailure {
        service.fetchElevations(path.map { it.toService() }).map {
            ElevationSample(
                coordinate = com.meshcoreone.android.core.model.Coordinate(it.coordinate.latitude, it.coordinate.longitude),
                elevation = it.elevation,
                distanceFromAMeters = it.distanceFromAMeters,
            )
        }
    }

    override fun optimalSampleCount(distanceMeters: Double): Int = ElevationService.optimalSampleCount(distanceMeters)

    override fun sampleCoordinates(
        from: com.meshcoreone.android.core.model.Coordinate,
        to: com.meshcoreone.android.core.model.Coordinate,
        sampleCount: Int,
    ): List<com.meshcoreone.android.core.model.Coordinate> =
        ElevationService.sampleCoordinates(from.toService(), to.toService(), sampleCount).map {
            com.meshcoreone.android.core.model.Coordinate(it.latitude, it.longitude)
        }

    private suspend fun <T> translateElevationFailure(block: suspend () -> T): T = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: ElevationServiceError) {
        throw AppElevationFailure(error.localizedDescription(context), error)
    }

    private fun com.meshcoreone.android.core.model.Coordinate.toService() = GeoCoordinate(latitude, longitude)
}

private class AppElevationFailure(message: String, cause: Throwable) : Exception(message, cause)

private object AppLineOfSightPathAnalyzer : LineOfSightPathAnalyzer {
    override fun distanceMeters(
        from: com.meshcoreone.android.core.model.Coordinate,
        to: com.meshcoreone.android.core.model.Coordinate,
    ): Double = RFCalculator.distance(from, to)

    override fun analyzePath(
        elevationProfile: List<ElevationSample>,
        pointAHeightMeters: Double,
        pointBHeightMeters: Double,
        frequencyMHz: Double,
        refractionK: Double,
    ): PathAnalysisResult = RFCalculator.analyzePath(
        elevationProfile.map { it.toCore() },
        pointAHeightMeters,
        pointBHeightMeters,
        frequencyMHz,
        refractionK,
    ).toFeature()

    override fun analyzePathSegment(
        elevationProfile: List<ElevationSample>,
        startHeightMeters: Double,
        endHeightMeters: Double,
        frequencyMHz: Double,
        refractionK: Double,
    ): PathAnalysisResult = RFCalculator.analyzePathSegment(
        elevationProfile.map { it.toCore() },
        startHeightMeters,
        endHeightMeters,
        frequencyMHz,
        refractionK,
    ).toFeature()

    private fun ElevationSample.toCore() =
        com.meshcoreone.android.core.services.diagnostics.rf.ElevationSample(
            coordinate = coordinate,
            elevation = elevation,
            distanceFromAMeters = distanceFromAMeters,
        )

    private fun com.meshcoreone.android.core.services.diagnostics.rf.PathAnalysisResult.toFeature() =
        PathAnalysisResult(
            distanceMeters = distanceMeters,
            freeSpacePathLoss = freeSpacePathLoss,
            peakDiffractionLoss = peakDiffractionLoss,
            totalPathLoss = totalPathLoss,
            clearanceStatus = ClearanceStatus.entries.first { it.rawValue == clearanceStatus.rawValue },
            worstClearancePercent = worstClearancePercent,
            obstructionPoints = obstructionPoints.map {
                ObstructionPoint(it.distanceFromAMeters, it.obstructionHeightMeters, it.fresnelClearancePercent)
            },
            frequencyMHz = frequencyMHz,
            refractionK = refractionK,
        )
}

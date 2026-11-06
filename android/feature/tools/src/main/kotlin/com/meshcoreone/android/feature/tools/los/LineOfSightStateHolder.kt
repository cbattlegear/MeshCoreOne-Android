// PortedFrom: MC1/Views/Tools/LineOfSight/LineOfSightViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/LineOfSight/LineOfSightView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/LineOfSight/AddRepeaterRowView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/LineOfSight/AnalysisErrorView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.los

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.RadioId
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plain state holder for the line-of-sight tool (the Swift `@MainActor` view model).
 *
 * Every public function must be called on [scope]'s thread (the main thread in the app), and
 * [scope] must be single-threaded: background work is launched there and publishes into
 * [state], mirroring MainActor isolation. Only the pure path analysis runs on
 * [analysisContext] (the source's `Task.detached`). A superseded or cancelled task never
 * publishes results or errors; cancellation is always rethrown.
 */
class LineOfSightStateHolder(
    private val scope: CoroutineScope,
    private val elevationSource: LineOfSightElevationSource,
    private val pathAnalyzer: LineOfSightPathAnalyzer,
    private val analysisContext: CoroutineContext = Dispatchers.Default,
    private val failureReporter: LineOfSightFailureReporter = LineOfSightFailureReporter.NONE,
    preselectedContact: ContactDTO? = null,
    private val onPointAEdited: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow(LineOfSightState())
    val state: StateFlow<LineOfSightState> = mutableState.asStateFlow()

    private val current: LineOfSightState get() = mutableState.value

    private var analysisJob: Job? = null
    private var pointAElevationJob: Job? = null
    private var pointBElevationJob: Job? = null
    private var repeaterElevationJob: Job? = null

    private var contactSourceProvider: () -> LineOfSightContactSource? = { null }
    private var radioIdProvider: () -> RadioId? = { null }

    init {
        if (preselectedContact != null && preselectedContact.hasLocation) {
            assignPointA(Coordinate(preselectedContact.latitude, preselectedContact.longitude), preselectedContact)
        }
    }

    private inline fun mutate(transform: (LineOfSightState) -> LineOfSightState) {
        mutableState.value = transform(mutableState.value)
    }

    /** Cancels outstanding work (the source's isolated deinit). */
    fun dispose() {
        analysisJob?.cancel()
        pointAElevationJob?.cancel()
        pointBElevationJob?.cancel()
        repeaterElevationJob?.cancel()
    }

    // region Configuration and plain setters

    /**
     * Supplies the contact source and radio this tool reads; a provider returning null mirrors a
     * disconnected state. [deviceFrequencyKHz] seeds the analysis frequency once.
     */
    fun configure(
        contactSource: () -> LineOfSightContactSource?,
        radioId: () -> RadioId?,
        deviceFrequencyKHz: UInt? = null,
    ) {
        contactSourceProvider = contactSource
        radioIdProvider = radioId
        if (deviceFrequencyKHz != null) {
            mutate { it.copy(frequencyMHz = LineOfSightState.frequencyMHzFromDeviceKHz(deviceFrequencyKHz)) }
        }
    }

    /** Edits the frequency; call [commitFrequencyChange] to re-analyze. */
    fun setFrequencyMHz(frequencyMHz: Double) = mutate { it.copy(frequencyMHz = frequencyMHz) }

    /** Re-analyzes with the cached profile when the k-factor actually changes. */
    fun setRefractionK(refractionK: Double) {
        val oldValue = current.refractionK
        mutate { it.copy(refractionK = refractionK) }
        if (oldValue != refractionK) reanalyzeWithCachedProfileIfNeeded()
    }

    fun commitFrequencyChange() = reanalyzeWithCachedProfileIfNeeded()

    fun setRelocatingPoint(point: PointID?) = mutate { it.copy(relocatingPoint = point) }

    fun setShouldAutoZoomOnNextResult(value: Boolean) = mutate { it.copy(shouldAutoZoomOnNextResult = value) }

    /** Direct repeater assignment (the source exposes `repeaterPoint` as a settable property). */
    fun setRepeaterPoint(point: RepeaterPoint?) = mutate { it.copy(repeaterPoint = point) }

    fun parseFrequency(text: String): Double? = LineOfSightFrequencyText.parseFrequency(text)

    fun formatFrequencyForEditing(value: Double): String = LineOfSightFrequencyText.formatFrequencyForEditing(value)

    // endregion

    // region Repeaters and point selection

    suspend fun loadRepeaters() {
        val contactSource = contactSourceProvider()
        val radioId = radioIdProvider()
        if (contactSource == null || radioId == null) return
        try {
            val allContacts = contactSource.fetchContacts(radioId)
            mutate { it.copy(repeatersWithLocation = allContacts.filter(LineOfSightState::isLocatedRepeater)) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            failureReporter.report("loadRepeaters", error)
        }
    }

    /** Auto-assigns to A when empty, otherwise to B (replacing B when both are set). */
    fun selectPoint(coordinate: Coordinate, contact: ContactDTO? = null) {
        if (current.pointA == null) setPointA(coordinate, contact) else setPointB(coordinate, contact)
    }

    fun setPointA(coordinate: Coordinate, contact: ContactDTO? = null) {
        onPointAEdited()
        assignPointA(coordinate, contact)
    }

    private fun assignPointA(coordinate: Coordinate, contact: ContactDTO?) {
        pointAElevationJob?.cancel()
        pointAElevationJob = null
        invalidateAnalysis()
        mutate { it.copy(pointA = SelectedPoint(coordinate, contact)) }
        pointAElevationJob = scope.launch { fetchPointElevation(PointID.POINT_A) }
    }

    /** Ignored when [coordinate] equals point A's coordinate. */
    fun setPointB(coordinate: Coordinate, contact: ContactDTO? = null) {
        val pointA = current.pointA
        if (pointA != null && pointA.coordinate.latitude == coordinate.latitude &&
            pointA.coordinate.longitude == coordinate.longitude
        ) {
            return
        }
        pointBElevationJob?.cancel()
        pointBElevationJob = null
        invalidateAnalysis()
        mutate { it.copy(pointB = SelectedPoint(coordinate, contact)) }
        pointBElevationJob = scope.launch { fetchPointElevation(PointID.POINT_B) }
    }

    /** Clamps to zero; height changes invalidate the analysis (repeater heights route to [updateRepeaterHeight]). */
    fun updateAdditionalHeight(point: PointID, meters: Double) {
        val clampedHeight = swiftMax(0.0, meters)
        when (point) {
            PointID.POINT_A -> {
                if (current.pointA == null) return
                mutate { it.copy(pointA = it.pointA?.copy(additionalHeight = clampedHeight)) }
            }
            PointID.POINT_B -> {
                if (current.pointB == null) return
                mutate { it.copy(pointB = it.pointB?.copy(additionalHeight = clampedHeight)) }
            }
            PointID.REPEATER -> {
                updateRepeaterHeight(clampedHeight)
                return
            }
        }
        invalidateAnalysis()
    }

    /** Tapping a selected contact clears that point; otherwise it is auto-assigned. */
    fun toggleContact(contact: ContactDTO) {
        if (current.pointA?.contact?.id == contact.id) {
            clearPointA()
            return
        }
        if (current.pointB?.contact?.id == contact.id) {
            clearPointB()
            return
        }
        selectPoint(Coordinate(contact.latitude, contact.longitude), contact)
    }

    // endregion

    // region Clearing

    fun clear() {
        onPointAEdited()
        pointAElevationJob?.cancel()
        pointBElevationJob?.cancel()
        analysisJob?.cancel()
        repeaterElevationJob?.cancel()
        pointAElevationJob = null
        pointBElevationJob = null
        analysisJob = null
        repeaterElevationJob = null
        mutate {
            it.copy(
                pointA = null,
                pointB = null,
                repeaterPoint = null,
                elevationFetchFailed = false,
                isAnalyzing = false,
                analysisStatus = AnalysisStatus.Idle,
                elevationProfile = emptyList(),
                elevationProfileAR = emptyList(),
                elevationProfileRB = emptyList(),
            )
        }
    }

    fun clearPointA() {
        onPointAEdited()
        pointAElevationJob?.cancel()
        pointAElevationJob = null
        mutate { it.copy(pointA = null, repeaterPoint = null) }
        invalidateAnalysis()
    }

    fun clearPointB() {
        pointBElevationJob?.cancel()
        pointBElevationJob = null
        mutate { it.copy(pointB = null, repeaterPoint = null) }
        invalidateAnalysis()
    }

    /** Clears results without clearing points (does not cancel an in-flight task). */
    fun clearAnalysisResults() =
        mutate { it.copy(isAnalyzing = false, analysisStatus = AnalysisStatus.Idle, shouldAutoZoomOnNextResult = false) }

    // endregion

    // region Repeater

    /** Places an on-path repeater at the worst obstruction of the current direct result. */
    fun addRepeater() {
        val result = (current.analysisStatus as? AnalysisStatus.Result)?.result ?: return
        val worstPoint = result.worstObstructionPoint ?: return
        val pathFraction = worstPoint.distanceFromAMeters / result.distanceMeters
        val coordinate = current.coordinateAt(pathFraction) ?: return
        val elevation = current.elevationAt(pathFraction) ?: return
        setRepeaterPoint(RepeaterPoint(coordinate, elevation, RepeaterPoint.DEFAULT_REPEATER_HEIGHT_METERS, true, pathFraction))
    }

    /** Moves an on-path repeater; coordinate and elevation come from the cached profile at the raw fraction. */
    fun updateRepeaterPosition(pathFraction: Double) {
        val repeater = current.repeaterPoint ?: return
        if (!repeater.isOnPath) return
        val state = current
        setRepeaterPoint(
            repeater.copy(
                pathFraction = pathFraction,
                coordinate = state.coordinateAt(pathFraction) ?: repeater.coordinate,
                groundElevation = state.elevationAt(pathFraction) ?: repeater.groundElevation,
            ),
        )
    }

    fun updateRepeaterHeight(meters: Double) {
        if (current.repeaterPoint == null) return
        mutate { it.copy(repeaterPoint = it.repeaterPoint?.copy(additionalHeight = swiftMax(0.0, meters))) }
    }

    /** Relocates the repeater off-path, keeping its height and fetching the new ground elevation. */
    fun setRepeaterOffPath(coordinate: Coordinate) {
        val existingHeight = current.repeaterPoint?.additionalHeight ?: RepeaterPoint.DEFAULT_REPEATER_HEIGHT_METERS
        mutate {
            it.copy(
                repeaterPoint = RepeaterPoint(coordinate, null, existingHeight, isOnPath = false, pathFraction = 0.5),
                elevationProfileAR = emptyList(),
                elevationProfileRB = emptyList(),
            )
        }
        repeaterElevationJob?.cancel()
        repeaterElevationJob = scope.launch {
            try {
                val elevation = elevationSource.fetchElevation(coordinate)
                if (!isActive) return@launch
                mutate { it.copy(repeaterPoint = it.repeaterPoint?.copy(groundElevation = elevation)) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (!isActive) return@launch
                failureReporter.report("repeaterElevation", error)
            }
        }
    }

    /** Removes the repeater (cancelling its in-flight elevation fetch) and reverts to single-path analysis. */
    fun clearRepeater() {
        repeaterElevationJob?.cancel()
        repeaterElevationJob = null
        setRepeaterPoint(null)
        reanalyzeWithCachedProfileIfNeeded()
    }

    /** Relay analysis: on-path splits the cached profile; off-path reuses cached A->R/R->B or fetches them. */
    fun analyzeWithRepeater() {
        val state = current
        val repeater = state.repeaterPoint ?: return
        val pointA = state.pointA ?: return
        val pointB = state.pointB ?: return
        when {
            repeater.isOnPath -> analyzeWithRepeaterOnPath()
            state.elevationProfileAR.isNotEmpty() && state.elevationProfileRB.isNotEmpty() -> applyRelayAnalysis(
                RelayInputs(state.elevationProfileAR, state.elevationProfileRB, pointA.additionalHeight,
                    repeater.additionalHeight, pointB.additionalHeight, state.frequencyMHz, state.refractionK),
            )
            else -> {
                analysisJob?.cancel()
                analysisJob = scope.launch { analyzeWithRepeaterOffPath() }
            }
        }
    }

    private fun analyzeWithRepeaterOnPath() {
        val state = current
        val repeater = state.repeaterPoint ?: return
        val pointA = state.pointA ?: return
        val pointB = state.pointB ?: return
        val profile = state.elevationProfile
        if (profile.size < 2) return
        val splitIndex = (repeater.pathFraction * (profile.size - 1).toDouble()).toInt()
        if (splitIndex <= 0 || splitIndex >= profile.size - 1) return
        applyRelayAnalysis(
            RelayInputs(profile.subList(0, splitIndex + 1).toList(), profile.subList(splitIndex, profile.size).toList(),
                pointA.additionalHeight, repeater.additionalHeight, pointB.additionalHeight,
                state.frequencyMHz, state.refractionK),
        )
    }

    private suspend fun analyzeWithRepeaterOffPath() {
        val start = current
        val repeater = start.repeaterPoint ?: return
        val pointA = start.pointA ?: return
        val pointB = start.pointB ?: return
        mutate { it.copy(isAnalyzing = true) }
        try {
            val distanceAR = pathAnalyzer.distanceMeters(pointA.coordinate, repeater.coordinate)
            val sampleCoordsAR = elevationSource.sampleCoordinates(
                pointA.coordinate, repeater.coordinate, elevationSource.optimalSampleCount(distanceAR),
            )
            val distanceRB = pathAnalyzer.distanceMeters(repeater.coordinate, pointB.coordinate)
            val sampleCoordsRB = elevationSource.sampleCoordinates(
                repeater.coordinate, pointB.coordinate, elevationSource.optimalSampleCount(distanceRB),
            )
            val (profileAR, profileRB) = coroutineScope {
                val arTask = async { elevationSource.fetchElevations(sampleCoordsAR) }
                val rbTask = async { elevationSource.fetchElevations(sampleCoordsRB) }
                arTask.await() to rbTask.await()
            }
            // A superseded task must not overwrite newer results.
            if (!currentCoroutineContext().isActive) {
                mutate { it.copy(isAnalyzing = false) }
                return
            }
            // fetchElevations measures from the segment start; continue R->B from the A->R endpoint.
            val profileRBAdjusted = profileRB.map {
                ElevationSample(it.coordinate, it.elevation, it.distanceFromAMeters + distanceAR)
            }
            val settings = current
            applyRelayAnalysis(
                RelayInputs(profileAR, profileRBAdjusted, pointA.additionalHeight, repeater.additionalHeight,
                    pointB.additionalHeight, settings.frequencyMHz, settings.refractionK),
            )
            mutate { it.copy(elevationProfileAR = profileAR, elevationProfileRB = profileRBAdjusted, isAnalyzing = false) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            if (!currentCoroutineContext().isActive) return
            failureReporter.report("offPathAnalysis", error)
            mutate { it.copy(isAnalyzing = false, analysisStatus = AnalysisStatus.Error(errorMessage(error))) }
        }
    }

    private class RelayInputs(
        val profileAR: List<ElevationSample>,
        val profileRB: List<ElevationSample>,
        val pointAHeight: Double,
        val repeaterHeight: Double,
        val pointBHeight: Double,
        val frequencyMHz: Double,
        val refractionK: Double,
    )

    /** Analyzes both segments synchronously and publishes samples plus the relay status. */
    private fun applyRelayAnalysis(inputs: RelayInputs) {
        val arResult = pathAnalyzer.analyzePathSegment(
            inputs.profileAR, inputs.pointAHeight, inputs.repeaterHeight, inputs.frequencyMHz, inputs.refractionK,
        )
        val rbResult = pathAnalyzer.analyzePathSegment(
            inputs.profileRB, inputs.repeaterHeight, inputs.pointBHeight, inputs.frequencyMHz, inputs.refractionK,
        )
        val relayResult = RelayPathAnalysisResult(
            segmentAR = SegmentAnalysisResult("A", "R", arResult.clearanceStatus, arResult.distanceMeters, arResult.worstClearancePercent),
            segmentRB = SegmentAnalysisResult("R", "B", rbResult.clearanceStatus, rbResult.distanceMeters, rbResult.worstClearancePercent),
        )
        val samplesAR = FresnelZoneRenderer.buildProfileSamples(
            inputs.profileAR, inputs.pointAHeight, inputs.repeaterHeight, inputs.frequencyMHz, inputs.refractionK,
        )
        val samplesRB = FresnelZoneRenderer.buildProfileSamples(
            inputs.profileRB, inputs.repeaterHeight, inputs.pointBHeight, inputs.frequencyMHz, inputs.refractionK,
        )
        mutate {
            it.copy(profileSamples = samplesAR, profileSamplesRB = samplesRB, analysisStatus = AnalysisStatus.RelayResult(relayResult))
        }
    }

    // endregion

    // region Analysis

    /** Fetches the A->B profile and analyzes it; requires both point elevations. */
    fun analyze() {
        val state = current
        val pointA = state.pointA
        val pointB = state.pointB
        if (pointA?.groundElevation == null || pointB?.groundElevation == null) return
        analysisJob?.cancel()
        mutate { it.copy(isAnalyzing = true) }
        val inputs = DirectInputs(pointA.additionalHeight, pointB.additionalHeight, state.frequencyMHz, state.refractionK)
        analysisJob = scope.launch {
            try {
                val distance = pathAnalyzer.distanceMeters(pointA.coordinate, pointB.coordinate)
                val sampleCoordinates = elevationSource.sampleCoordinates(
                    pointA.coordinate, pointB.coordinate, elevationSource.optimalSampleCount(distance),
                )
                val profile = elevationSource.fetchElevations(sampleCoordinates)
                if (!isActive) return@launch
                val result = withContext(analysisContext) { analyzeDirect(profile, inputs) }
                if (!isActive) return@launch
                mutate {
                    it.copy(
                        elevationProfile = profile,
                        profileSamples = directSamples(profile, inputs),
                        profileSamplesRB = emptyList(),
                        isAnalyzing = false,
                        analysisStatus = AnalysisStatus.Result(result),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (!isActive) return@launch
                failureReporter.report("analysis", error)
                mutate { it.copy(isAnalyzing = false, analysisStatus = AnalysisStatus.Error(errorMessage(error))) }
            }
        }
    }

    private class DirectInputs(val pointAHeight: Double, val pointBHeight: Double, val frequencyMHz: Double, val refractionK: Double)

    private fun analyzeDirect(profile: List<ElevationSample>, inputs: DirectInputs): PathAnalysisResult =
        pathAnalyzer.analyzePath(profile, inputs.pointAHeight, inputs.pointBHeight, inputs.frequencyMHz, inputs.refractionK)

    private fun directSamples(profile: List<ElevationSample>, inputs: DirectInputs): List<ProfileSample> =
        FresnelZoneRenderer.buildProfileSamples(profile, inputs.pointAHeight, inputs.pointBHeight, inputs.frequencyMHz, inputs.refractionK)

    /** Invalidates results but keeps the cached profile (RF setting changes). */
    private fun invalidateAnalysisOnly() {
        analysisJob?.cancel()
        analysisJob = null
        mutate { it.copy(isAnalyzing = false, analysisStatus = AnalysisStatus.Idle, shouldAutoZoomOnNextResult = false) }
    }

    /** Invalidates results and every cached profile (point changes need new elevation data). */
    private fun invalidateAnalysis() {
        invalidateAnalysisOnly()
        repeaterElevationJob?.cancel()
        repeaterElevationJob = null
        mutate {
            it.copy(
                elevationProfile = emptyList(),
                elevationProfileAR = emptyList(),
                elevationProfileRB = emptyList(),
                profileSamples = emptyList(),
                profileSamplesRB = emptyList(),
                elevationFetchFailed = false,
                repeaterPoint = null,
            )
        }
    }

    /**
     * Re-runs analysis with the cached profile after an RF change, preserving relay mode. Clears
     * `isAnalyzing` on every bail path so a stale flag cannot strand the spinner.
     */
    private fun reanalyzeWithCachedProfileIfNeeded() {
        val state = current
        val pointA = state.pointA
        val pointB = state.pointB
        if (state.elevationProfile.isEmpty() || pointA?.groundElevation == null || pointB?.groundElevation == null) {
            mutate { it.copy(isAnalyzing = false) }
            return
        }
        if (state.repeaterPoint != null) {
            mutate { it.copy(isAnalyzing = false) }
            analyzeWithRepeater()
            return
        }
        analysisJob?.cancel()
        mutate { it.copy(isAnalyzing = true) }
        val profile = state.elevationProfile
        val inputs = DirectInputs(pointA.additionalHeight, pointB.additionalHeight, state.frequencyMHz, state.refractionK)
        analysisJob = scope.launch {
            try {
                val result = withContext(analysisContext) { analyzeDirect(profile, inputs) }
                if (!isActive) return@launch
                mutate {
                    it.copy(
                        profileSamples = directSamples(profile, inputs),
                        profileSamplesRB = emptyList(),
                        analysisStatus = AnalysisStatus.Result(result),
                    )
                }
            } finally {
                mutate { it.copy(isAnalyzing = false) }
            }
        }
    }

    private suspend fun fetchPointElevation(point: PointID) {
        val coordinate = current.coordinateOf(point) ?: return
        try {
            val elevation = elevationSource.fetchElevation(coordinate)
            if (!currentCoroutineContext().isActive) return
            mutate { it.withPointGroundElevation(point, elevation) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            if (!currentCoroutineContext().isActive) return
            failureReporter.report("pointElevation", error)
            // Sea-level fallback so analysis can proceed.
            mutate { it.withPointGroundElevation(point, 0.0).copy(elevationFetchFailed = true) }
        }
    }

    private fun LineOfSightState.withPointGroundElevation(point: PointID, elevation: Double): LineOfSightState =
        if (point == PointID.POINT_A) {
            copy(pointA = pointA?.copy(groundElevation = elevation))
        } else {
            copy(pointB = pointB?.copy(groundElevation = elevation))
        }

    private fun errorMessage(error: Throwable): String = error.localizedMessage ?: error.toString()

    // endregion

    // region Operator workflow (LineOfSightView and row actions; sheet/camera animation stays in the UI)

    /** Map tap: only completes an active relocation. */
    fun handleMapTap(coordinate: Coordinate) {
        val relocating = current.relocatingPoint ?: return
        relocate(coordinate, relocating)
    }

    /** Map long-press: completes an active relocation, otherwise selects a point. */
    fun handleMapLongPress(coordinate: Coordinate) {
        val relocating = current.relocatingPoint
        if (relocating != null) {
            relocate(coordinate, relocating)
            return
        }
        selectPoint(coordinate)
    }

    /** Moves [point] to [coordinate], clears results so Analyze shows, and ends relocation. */
    fun relocate(coordinate: Coordinate, point: PointID) {
        when (point) {
            PointID.POINT_A -> setPointA(coordinate, null)
            PointID.POINT_B -> setPointB(coordinate, null)
            PointID.REPEATER -> setRepeaterOffPath(coordinate)
        }
        clearAnalysisResults()
        setRelocatingPoint(null)
    }

    /** Relocate button: toggles relocation of [point]; returns true when relocation started. */
    fun toggleRelocation(point: PointID): Boolean {
        if (current.relocatingPoint == point) {
            setRelocatingPoint(null)
            return false
        }
        setRelocatingPoint(point)
        return true
    }

    /** Analyze button: requests auto-zoom on the next result, then runs the active analysis mode. */
    fun requestAnalysis() {
        setShouldAutoZoomOnNextResult(true)
        retryAnalysis()
    }

    /** Error-view retry (and the shared analyze routing): relay when a repeater exists, else direct. */
    fun retryAnalysis() {
        if (current.repeaterPoint != null) analyzeWithRepeater() else analyze()
    }

    /** "Add repeater" placeholder row. */
    fun addRepeaterAndAnalyze() {
        addRepeater()
        analyzeWithRepeater()
    }

    /** Terrain-profile drag: on-path repeaters only. */
    fun dragRepeater(pathFraction: Double) {
        if (!current.isRepeaterDragEnabled) return
        updateRepeaterPosition(pathFraction)
        analyzeWithRepeater()
    }

    /** Repeater height editor: store the height, then re-run relay analysis. */
    fun editRepeaterHeight(meters: Double) {
        updateRepeaterHeight(meters)
        analyzeWithRepeater()
    }

    /**
     * Frequency field commit: a valid value is stored and re-analyzed (returns null); invalid
     * input is rejected and the text to restore is returned.
     */
    fun commitFrequencyText(text: String): String? {
        val parsed = parseFrequency(text) ?: return formatFrequencyForEditing(current.frequencyMHz)
        setFrequencyMHz(parsed)
        commitFrequencyChange()
        return null
    }

    /**
     * Call when `analysisStatus` changes. For a (relay) result with a pending auto-zoom request,
     * consumes it and returns the A/B coordinates the camera should fit (null otherwise).
     */
    fun onAnalysisStatusChanged(): List<Coordinate>? {
        if (!current.hasAnalysisResult || !current.shouldAutoZoomOnNextResult) return null
        setShouldAutoZoomOnNextResult(false)
        val pointA = current.pointA ?: return null
        val pointB = current.pointB ?: return null
        return listOf(pointA.coordinate, pointB.coordinate)
    }

    // endregion

    // region Test seams (the source's DEBUG-only helpers)

    internal fun setAnalysisStatusForTesting(result: PathAnalysisResult) =
        mutate { it.copy(analysisStatus = AnalysisStatus.Result(result)) }

    internal fun setElevationProfileForTesting(profile: List<ElevationSample>) =
        mutate { it.copy(elevationProfile = profile) }

    // endregion
}

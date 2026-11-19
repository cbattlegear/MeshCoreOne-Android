// AndroidOnly: WP-312 User-directed temporary suspension of OpenFreeMap offline transfers.
package com.meshcoreone.android.core.maps

import java.net.URI
import org.maplibre.android.MapLibre
import org.maplibre.android.ModuleProvider
import org.maplibre.android.http.HttpRequest
import org.maplibre.android.http.HttpResponder

object OpenFreeMapOfflineSuspension {
    fun appliesTo(url: String?): Boolean {
        val host = url?.let { runCatching { URI(it).host }.getOrNull() }
            ?.trimEnd('.') ?: return false
        return host.equals("openfreemap.org", ignoreCase = true) ||
            host.endsWith(".openfreemap.org", ignoreCase = true)
    }

    fun errorFor(styleUri: String?): OfflineMapError? =
        OfflineMapError.OpenFreeMapDownloadsSuspended.takeIf { appliesTo(styleUri) }

    @Synchronized
    fun install() {
        val provider = MapLibre.getModuleProvider()
        if (provider !is SuspendedOfflineModuleProvider) {
            MapLibre.setModuleProvider(SuspendedOfflineModuleProvider(provider))
        }
    }
}

internal class SuspendedOfflineModuleProvider(
    private val delegate: ModuleProvider,
) : ModuleProvider by delegate {
    override fun createHttpRequest(): HttpRequest = SuspendedOfflineHttpRequest(delegate.createHttpRequest())
}

internal class SuspendedOfflineHttpRequest(
    private val delegate: HttpRequest,
) : HttpRequest {
    override fun executeRequest(
        responder: HttpResponder,
        nativePtr: Long,
        resourceUrl: String,
        dataRange: String,
        etag: String,
        modified: String,
        offlineUsage: Boolean,
    ) {
        if (offlineUsage && OpenFreeMapOfflineSuspension.appliesTo(resourceUrl)) {
            responder.handleFailure(HttpRequest.PERMANENT_ERROR, OfflineMapError.OpenFreeMapDownloadsSuspended.message)
            return
        }
        // Delegate unchanged: online requests and other providers keep the SDK's headers and transport.
        delegate.executeRequest(responder, nativePtr, resourceUrl, dataRange, etag, modified, offlineUsage)
    }

    override fun cancelRequest() = delegate.cancelRequest()
}

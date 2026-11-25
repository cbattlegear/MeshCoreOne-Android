// PortedFrom: MC1/Services/ElevationService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.content

import android.net.Uri
import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.ElevationFetchAttempt
import com.meshcoreone.android.core.services.content.ElevationFetching
import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

class OpenMeteoElevationFetching(private val http: BoundedHttpFetching) : ElevationFetching {
    override suspend fun fetchElevations(latitudes: String, longitudes: String): ElevationFetchAttempt {
        val url = Uri.parse("https://api.open-meteo.com/v1/elevation").buildUpon()
            .appendQueryParameter("latitude", latitudes)
            .appendQueryParameter("longitude", longitudes)
            .build().toString()
        val attempt = http.fetch(url, 10_000)
        if (attempt is HttpFetchAttempt.Failed) return ElevationFetchAttempt.NetworkError(attempt.reason)
        if (attempt !is HttpFetchAttempt.Started) return ElevationFetchAttempt.InvalidResponse
        return try {
            attempt.use {
                if (it.statusCode == 429) return@use ElevationFetchAttempt.RateLimited
                if (it.statusCode != 200) return@use ElevationFetchAttempt.HttpError(it.statusCode)
                val raw = it.readBounded(512 * 1024) ?: return@use ElevationFetchAttempt.InvalidResponse
                val values = JSONObject(raw.toString(Charsets.UTF_8)).getJSONArray("elevation")
                val elevations = (0 until values.length()).map { index ->
                    val value = values.get(index)
                    if (value !is Number || !value.toDouble().isFinite()) {
                        return@use ElevationFetchAttempt.InvalidResponse
                    }
                    value.toDouble()
                }
                ElevationFetchAttempt.Success(elevations)
            }
        } catch (_: JSONException) {
            ElevationFetchAttempt.InvalidResponse
        } catch (_: IOException) {
            ElevationFetchAttempt.NetworkError("Elevation HTTP stream failed")
        }
    }
}

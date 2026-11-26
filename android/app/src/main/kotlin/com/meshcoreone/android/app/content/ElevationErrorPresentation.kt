// PortedFrom: MC1/Services/ElevationService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.content

import android.content.Context
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.services.content.ElevationServiceError

fun ElevationServiceError.localizedDescription(context: Context): String = when (this) {
    is ElevationServiceError.NetworkError ->
        context.getString(R.string.l10n_app_localizable_common_error_networkerror, description)
    ElevationServiceError.InvalidResponse ->
        context.getString(R.string.l10n_app_localizable_common_error_invalidresponse)
    is ElevationServiceError.ApiError ->
        context.getString(R.string.l10n_app_localizable_common_error_apierror, apiMessage)
    ElevationServiceError.NoData ->
        context.getString(R.string.l10n_app_localizable_common_error_noelevationdata)
    ElevationServiceError.RateLimited ->
        context.getString(R.string.l10n_app_localizable_common_error_ratelimited)
}

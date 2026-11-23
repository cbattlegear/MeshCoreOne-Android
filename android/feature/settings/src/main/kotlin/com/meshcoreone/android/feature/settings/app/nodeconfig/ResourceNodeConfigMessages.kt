// PortedFrom: MC1Tests/ViewModels/NodeConfigImportViewModelTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/NodeConfig/NodeConfigImportViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.app.nodeconfig

import android.content.res.Resources
import com.meshcoreone.android.feature.settings.R
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.feature.settings.app.backup.BackupDocumentException

class ResourceNodeConfigMessages(
    private val currentResources: () -> Resources,
    private val isContactSaveFailure: (Throwable) -> Boolean,
    private val mapper: UiErrorMapper = UiErrorMapper(),
) : NodeConfigMessages {
    constructor(resources: Resources, isContactSaveFailure: (Throwable) -> Boolean, mapper: UiErrorMapper = UiErrorMapper()) :
        this({ resources }, isContactSaveFailure, mapper)

    override fun error(failure: Throwable): String = error(failure, currentResources())

    private fun error(failure: Throwable, resources: Resources): String = when {
        isContactSaveFailure(failure) -> resources.getString(R.string.node_config_contact_save_failed)
        failure is BackupDocumentException.PickerUnavailable -> resources.getString(R.string.node_config_picker_unavailable)
        failure is BackupDocumentException.UriUnavailable || failure is BackupDocumentException.PermissionRevoked ->
            resources.getString(R.string.node_config_document_unavailable)
        else -> mapper.message(failure).resolve(resources)
    }
    override fun cancelled(partial: Boolean) =
        currentResources().getString(if (partial) S.configImportCancelledPartial else S.configImportCancelled)
    override fun failed(failure: Throwable, partial: Boolean): String {
        val resources = currentResources()
        return error(failure, resources).let { if (partial) S.configImportFailedPartial(resources, it) else it }
    }
    override fun uncertain(failure: Throwable?): String {
        val resources = currentResources()
        val warning = resources.getString(R.string.node_config_write_outcome_unknown)
        return if (failure == null) warning else "${error(failure, resources)} $warning"
    }
}

// AndroidOnly: WP-305 Keeps the mounted device-menu tip claim and retry state process-owned.
package com.meshcoreone.android.app.container.onboarding

import com.meshcoreone.android.core.datastore.StorageFailure
import com.meshcoreone.android.core.ui.SharedTip
import com.meshcoreone.android.core.ui.ShowOnceTips
import com.meshcoreone.android.core.ui.TipHostState
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.core.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DeviceMenuTipPresentation(
    val visible: Boolean = false,
    val failure: UiText? = null,
)

internal class DeviceMenuTipPresenter(
    private val tips: ShowOnceTips,
    private val processScope: CoroutineScope,
) {
    private val claimLock = Mutex()
    private val latestHost = MutableStateFlow<TipHostState?>(null)
    private val mutablePresentation = MutableStateFlow(DeviceMenuTipPresentation())
    val presentation: StateFlow<DeviceMenuTipPresentation> = mutablePresentation.asStateFlow()

    fun consider(host: TipHostState): Job? {
        latestHost.value = host
        if (!host.eligible(SharedTip.DEVICE_MENU)) {
            mutablePresentation.value = mutablePresentation.value.copy(failure = null)
            return null
        }
        if (mutablePresentation.value.visible) return null

        return processScope.launch {
            claimLock.withLock {
                val current = latestHost.value ?: return@withLock
                if (!current.eligible(SharedTip.DEVICE_MENU) || mutablePresentation.value.visible) return@withLock
                mutablePresentation.value = mutablePresentation.value.copy(failure = null)
                try {
                    if (tips.claim(SharedTip.DEVICE_MENU) { latestHost.value ?: current }) {
                        mutablePresentation.value = DeviceMenuTipPresentation(visible = true)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: StorageFailure) {
                    mutablePresentation.value = mutablePresentation.value.copy(
                        failure = UiErrorMapper().message(failure),
                    )
                }
            }
        }
    }

    fun retry(): Job? = latestHost.value?.let(::consider)

    fun dismiss() {
        mutablePresentation.value = DeviceMenuTipPresentation()
    }
}

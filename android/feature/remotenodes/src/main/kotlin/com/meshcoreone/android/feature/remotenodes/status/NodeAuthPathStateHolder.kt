// PortedFrom: MC1/Views/RemoteNodes/NodeAuthPathViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodeHistoryStore
import com.meshcoreone.android.feature.remotenodes.resolver.NeighborNameResolver
import com.meshcoreone.android.feature.remotenodes.resolver.ResolvedPathHop
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The login sheet's stored route resolved to repeater names (Swift `NodeAuthPathViewModel`). */
class NodeAuthPathStateHolder {
    private val _hops = MutableStateFlow<List<ResolvedPathHop>>(emptyList())
    val hops: StateFlow<List<ResolvedPathHop>> = _hops.asStateFlow()

    /** Resolves [contact]'s path hops; no store (disconnected) or a store failure yields no hops. */
    suspend fun load(
        contact: ContactDTO,
        store: RemoteNodeHistoryStore?,
        radioId: RadioId,
        userLocation: Coordinate?,
        locale: Locale,
    ) {
        if (store == null) {
            _hops.value = emptyList()
            return
        }
        _hops.value = try {
            val contacts = store.fetchContacts(radioId)
            val discoveredNodes = store.fetchDiscoveredNodes(radioId)
            NeighborNameResolver.resolvePath(contact.pathHops, contacts, discoveredNodes, userLocation, locale)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            emptyList()
        }
    }
}

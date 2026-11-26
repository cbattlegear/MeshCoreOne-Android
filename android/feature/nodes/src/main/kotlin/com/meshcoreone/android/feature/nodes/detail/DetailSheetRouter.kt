// PortedFrom: MC1/Views/Contacts/ContactDetailView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.detail

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.model.ContactType

/** Sheets the contact detail presents (`ActiveSheet`). */
sealed interface DetailSheet {
    val id: String

    data object NodeAuth : DetailSheet {
        override val id get() = "auth"
    }

    data class RepeaterStatus(val session: RemoteNodeSessionDTO) : DetailSheet {
        override val id get() = "status-${session.id}"
    }

    data class RoomStatus(val session: RemoteNodeSessionDTO) : DetailSheet {
        override val id get() = "room-status-${session.id}"
    }

    data class NodeTelemetry(val contact: ContactDTO) : DetailSheet {
        override val id get() = "telemetry-${contact.id}"
    }

    data class AdminSettings(val session: RemoteNodeSessionDTO) : DetailSheet {
        override val id get() = "admin-settings-${session.id}"
    }
}

/**
 * Sheet sequencing: a follow-up sheet is queued and shown only after the presenting sheet finished
 * dismissing, so two sheets never animate at once.
 */
class DetailSheetRouter {
    var activeSheet: DetailSheet? = null
        private set
    private var pendingSheet: DetailSheet? = null
    private var adminSession: RemoteNodeSessionDTO? = null

    /** Telemetry: chats show telemetry directly; repeaters and rooms authenticate first. */
    fun showTelemetry(contact: ContactDTO) {
        activeSheet = if (contact.type == ContactType.CHAT) DetailSheet.NodeTelemetry(contact) else DetailSheet.NodeAuth
    }

    /** Telemetry-access authentication succeeded: queue the status sheet and dismiss the auth sheet. */
    fun onTelemetryAuthenticated(contact: ContactDTO, session: RemoteNodeSessionDTO) {
        pendingSheet = if (contact.type == ContactType.ROOM) DetailSheet.RoomStatus(session) else DetailSheet.RepeaterStatus(session)
        activeSheet = null
    }

    /** The active sheet finished dismissing: present the queued one, if any. */
    fun onSheetDismissed() {
        activeSheet = pendingSheet ?: return
        pendingSheet = null
    }

    fun dismissActiveSheet() {
        activeSheet = null
    }

    /** Management access starts a fresh admin authentication. */
    fun beginAdminAccess() {
        adminSession = null
    }

    fun onAdminAuthenticated(session: RemoteNodeSessionDTO) {
        adminSession = session
    }

    /** The admin auth sheet finished dismissing: route by the session's permission and role. */
    fun onAdminAuthDismissed() {
        val session = adminSession ?: return
        activeSheet = when {
            session.isAdmin -> DetailSheet.AdminSettings(session)
            session.isRoom -> DetailSheet.RoomStatus(session)
            else -> DetailSheet.RepeaterStatus(session)
        }
    }
}

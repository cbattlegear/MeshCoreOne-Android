// AndroidOnly: WP-101 Prizm Mobile Observer adapter from the live radio session to the observer's node link and signer.
package com.meshcoreone.android.app.prizm

import com.meshcoreone.android.core.observer.claim.NodeDeviceInfo
import com.meshcoreone.android.core.observer.claim.SessionNodeSigner
import com.meshcoreone.android.core.observer.health.DeviceStatsReader
import com.meshcoreone.android.core.observer.health.statsReader
import com.meshcoreone.android.core.observer.session.ObserverNodeLink
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import kotlinx.coroutines.flow.Flow

/** Wraps one connected [MeshCoreSession]; built only once self info and device capabilities are known. */
class SessionObserverNodeLink private constructor(
    private val session: MeshCoreSession,
    override val nodeKey: Bytes,
    override val deviceInfo: NodeDeviceInfo,
    private val maxChannels: Int,
    private val connected: () -> Boolean,
) : ObserverNodeLink {
    override fun events(): Flow<MeshEvent> = session.events()

    override suspend fun fetchChannels(): List<ChannelInfo> =
        session.getChannels((0 until maxChannels).map { it.toUByte() }).received.toList()

    override fun statsReader(): DeviceStatsReader = session.statsReader()
    override fun isConnected(): Boolean = connected()

    fun connectedNode(): PrizmConnectedNode = PrizmConnectedNode(this, SessionNodeSigner(session))

    companion object {
        /** Null while the radio handshake has not produced self info and capabilities yet. */
        fun create(session: MeshCoreSession, connected: () -> Boolean): SessionObserverNodeLink? {
            val self = session.currentSelfInfo ?: return null
            val capabilities = session.currentDeviceCapabilities ?: return null
            val channels = capabilities.maxChannels.coerceIn(0L, MAX_CHANNEL_SLOTS.toLong()).toInt()
            return SessionObserverNodeLink(session, self.publicKey, NodeDeviceInfo.from(self, capabilities), channels, connected)
        }

        private const val MAX_CHANNEL_SLOTS = 40
    }
}

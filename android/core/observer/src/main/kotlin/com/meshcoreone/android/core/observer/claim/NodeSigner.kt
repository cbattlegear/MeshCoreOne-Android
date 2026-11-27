// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.claim

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.session.ConfigurationSessionOps

/** Signs bytes with the node's own key. Throws `MeshCoreException` on any node failure. */
fun interface NodeSigner {
    suspend fun sign(message: Bytes): Bytes
}

/** Signs through CMD_SIGN_START/DATA/FINISH in firmware-safe chunks. */
class SessionNodeSigner(private val session: ConfigurationSessionOps) : NodeSigner {
    override suspend fun sign(message: Bytes): Bytes = session.signBounded(message)
}

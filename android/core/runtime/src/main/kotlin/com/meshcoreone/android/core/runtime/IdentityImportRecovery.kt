// AndroidOnly: WP-303 Process-scoped identity-import recovery bound to the original connection target.
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID

data class IdentityImportRecovery(
    val receiptId: UUID,
    val deviceId: UUID,
    val radioId: RadioId,
    val sourceName: String,
    val target: ConnectionTarget,
    val requestRevision: Long,
)

class IdentityImportRecoveryRequired(val recovery: IdentityImportRecovery) :
    Exception("The identity import outcome is unknown")

class IdentityImportPeerMismatch :
    Exception("The connected radio does not match the identity import recovery")

class IdentityImportPeerUnidentified :
    Exception("The radio cannot be identified while an identity import outcome is unknown")

class IdentityImportReadbackMismatch :
    Exception("The radio did not confirm the expected imported identity")

class IdentityImportKeyMismatch :
    Exception("The selected private key does not match the original identity import")

class IdentityImportIdentityUnverifiable :
    Exception("The imported identity cannot be verified for safe recovery")

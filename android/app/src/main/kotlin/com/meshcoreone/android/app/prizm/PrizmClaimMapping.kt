// AndroidOnly: WP-101 Prizm Mobile Observer mapping between core:observer claim types and the settings feature's types.
package com.meshcoreone.android.app.prizm

import com.meshcoreone.android.core.observer.claim.ClaimOutcome
import com.meshcoreone.android.core.observer.claim.NodeType
import com.meshcoreone.android.feature.settings.prizm.PrizmClaimResult
import com.meshcoreone.android.feature.settings.prizm.PrizmNodeType

internal object PrizmClaimMapping {
    fun nodeType(type: PrizmNodeType): NodeType = when (type) {
        PrizmNodeType.HANDHELD -> NodeType.HANDHELD
        PrizmNodeType.VEHICLE -> NodeType.VEHICLE
        PrizmNodeType.HOME_BASE -> NodeType.HOME_BASE
        PrizmNodeType.PORTABLE -> NodeType.PORTABLE
    }

    fun result(outcome: ClaimOutcome): PrizmClaimResult = when (outcome) {
        ClaimOutcome.Linked -> PrizmClaimResult.Linked
        ClaimOutcome.InfoUpdated -> PrizmClaimResult.InfoUpdated
        is ClaimOutcome.NeedsConfirmation -> PrizmClaimResult.NeedsConfirmation(outcome.ownerLabel)
        ClaimOutcome.NonceExpired -> PrizmClaimResult.Expired
        is ClaimOutcome.Rejected -> PrizmClaimResult.Rejected(outcome.reason)
        is ClaimOutcome.NodeSigningFailed -> PrizmClaimResult.SigningFailed
    }

    /** Only a completed claim is remembered; a refusal or a lost nonce leaves the node unclaimed. */
    fun isClaimed(result: PrizmClaimResult): Boolean = result == PrizmClaimResult.Linked || result == PrizmClaimResult.InfoUpdated
}

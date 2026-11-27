// AndroidOnly: WP-101 Prizm Mobile Observer platform seams so the runtime stays plain Kotlin.
package com.meshcoreone.android.app.prizm

import com.meshcoreone.android.core.observer.claim.NodeSigner
import com.meshcoreone.android.core.observer.session.ObserverNodeLink
import com.meshcoreone.android.feature.settings.prizm.PrizmCoordinate

/** The node connected right now: the link the observer reads, and the signer the claim flow uses. */
class PrizmConnectedNode(val link: ObserverNodeLink, val signer: NodeSigner) {
    val name: String get() = link.deviceInfo.name
    val model: String get() = link.deviceInfo.model
    val keyHex: String get() = link.nodeKey.hexString
}

/** Opens Prizm's authorization page in the browser. Returns false when nothing could handle it. */
fun interface PrizmAuthLauncher { fun open(url: String): Boolean }

/** Starts and stops the foreground service. [start] returns false when Android refused it. */
interface PrizmServiceControl {
    fun start(): Boolean
    fun stop()
}

fun interface PrizmLocationProvider { suspend fun currentLocation(): PrizmCoordinate? }

// AndroidOnly: WP-315 mounted location actions use the real unsupported-map path on the JVM.
package com.meshcoreone.android.feature.nodes.ui

import android.os.Process
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowProcess

@Implements(Process::class)
class UnsupportedNodesMapProcess : ShadowProcess() {
    companion object {
        @Implementation
        @JvmStatic
        fun is64Bit(): Boolean = false
    }
}

// PortedFrom: MC1Tests/Views/SyncingPillViewTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.designsystem.MeshSymbol
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS")
class SyncingPillTest : SourceCaseProof() {
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources
    @OriginalCase("SyncingPillViewTests::Connecting state shows correct text and icon()")
    @Test fun connecting() = prove {
        assertEquals("Connecting", StatusPillState.Connecting.text.resolve(resources))
        assertEquals(MeshSymbol.SYNC, StatusPillState.Connecting.symbol); assertFalse(StatusPillState.Connecting.isFailure)
        assertEquals(SharedStatusColorRole.PRIMARY, StatusPillState.Connecting.textColorRole)
        assertEquals(SharedStatusColorRole.PRIMARY, StatusPillState.Connecting.iconColorRole)
    }
    @OriginalCase("SyncingPillViewTests::Syncing state shows correct text and icon()")
    @Test fun syncing() = prove {
        assertEquals("Syncing", StatusPillState.Syncing.text.resolve(resources))
        assertEquals(MeshSymbol.SYNC, StatusPillState.Syncing.symbol); assertFalse(StatusPillState.Syncing.isFailure)
        assertEquals(SharedStatusColorRole.PRIMARY, StatusPillState.Syncing.textColorRole)
    }
    @OriginalCase("SyncingPillViewTests::Ready state shows correct text and icon()")
    @Test fun ready() = prove {
        assertEquals("Ready", StatusPillState.Ready.text.resolve(resources))
        assertEquals(MeshSymbol.READY, StatusPillState.Ready.symbol); assertFalse(StatusPillState.Ready.isFailure)
        assertEquals(SharedStatusColorRole.PRIMARY, StatusPillState.Ready.textColorRole)
        assertEquals(SharedStatusColorRole.GREEN, StatusPillState.Ready.iconColorRole)
    }
    @OriginalCase("SyncingPillViewTests::Disconnected state shows orange warning icon and text()")
    @NativeAdaptation("native-warning-foreground-and-readable-surface")
    @Test fun disconnected() = prove {
        assertEquals("Disconnected", StatusPillState.Disconnected.text.resolve(resources))
        assertEquals(MeshSymbol.WARNING, StatusPillState.Disconnected.symbol); assertFalse(StatusPillState.Disconnected.isFailure)
        assertEquals(SharedStatusColorRole.ORANGE, StatusPillState.Disconnected.textColorRole)
        assertEquals(SharedStatusColorRole.ORANGE, StatusPillState.Disconnected.iconColorRole)
    }
    @OriginalCase("SyncingPillViewTests::Disconnected with tap handler stores closure()")
    @Test fun retainsTap() = prove {
        var tapped = false
        val actions = SyncingPillActions { tapped = true }
        assertFalse(tapped); assertNotNull(actions.onDisconnectedTap).invoke(); assertTrue(tapped)
    }
    @OriginalCase("SyncingPillViewTests::Failed state shows red text and failure icon with custom message()")
    @NativeAdaptation("material-error-container-with-readable-foreground")
    @Test fun failed() = prove {
        val state = StatusPillState.Failed(UiText.Verbatim("Sync Failed"))
        assertEquals("Sync Failed", state.text.resolve(resources)); assertTrue(state.isFailure)
        assertEquals(MeshSymbol.ERROR, state.symbol)
        assertEquals(SharedStatusColorRole.RED, state.textColorRole)
        assertEquals(SharedStatusColorRole.RED, state.iconColorRole)
    }
    @OriginalCase("SyncingPillViewTests::Failed state preserves custom error message()")
    @Test fun preservesFailure() = prove {
        assertEquals("Custom Error", StatusPillState.Failed(UiText.Verbatim("Custom Error")).text.resolve(resources))
    }
    @OriginalCase("SyncingPillViewTests::Hidden state shows empty text and no icon()")
    @Test fun hidden() = prove {
        assertEquals("", StatusPillState.Hidden.text.resolve(resources)); assertNull(StatusPillState.Hidden.symbol)
        assertFalse(StatusPillState.Hidden.isFailure)
        assertEquals(SharedStatusColorRole.PRIMARY, StatusPillState.Hidden.textColorRole)
    }
}

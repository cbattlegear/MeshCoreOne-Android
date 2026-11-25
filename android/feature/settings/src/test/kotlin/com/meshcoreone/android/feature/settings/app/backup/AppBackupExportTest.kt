// PortedFrom: MC1Tests/ViewModels/AppBackupViewModelTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.app.backup

import com.meshcoreone.android.core.model.AppBackupError
import com.meshcoreone.android.core.model.AppBackupException
import com.meshcoreone.android.feature.settings.app.support.BackupRig
import com.meshcoreone.android.feature.settings.app.support.OriginalCase
import com.meshcoreone.android.feature.settings.app.support.scenario
import com.meshcoreone.android.feature.settings.app.support.settle
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class AppBackupExportTest {
    private fun holder(rig: BackupRig, scope: kotlinx.coroutines.CoroutineScope, clock: Clock = Clock.systemUTC()) =
        AppBackupStateHolder(rig.dependencies, scope, Dispatchers.Unconfined as CoroutineDispatcher, clock)

    @Test
    @OriginalCase("AppBackupViewModelExportTests::handleExportResult(.success) promotes pending to success()")
    fun `saved outcome promotes pending to success with filename size and manifest`() = scenario {
        val rig = BackupRig()
        val gate = CompletableDeferred<BackupSaveOutcome>()
        rig.documents.save = { _, _ -> gate.await() }
        val holder = holder(rig, scope)
        holder.performExport()
        settle()
        assertIs<ExportState.Pending>(holder.state.value.export)
        gate.complete(BackupSaveOutcome.Saved("MC1-backup-2026-04-19.mc1backup"))
        settle()
        val summary = assertNotNull(holder.state.value.exportSummary)
        assertEquals("MC1-backup-2026-04-19.mc1backup", summary.filename)
        assertEquals(128, summary.byteCount)
        assertEquals(3, summary.manifest.count(BackupModelKind.MESSAGES))
        assertEquals(2, summary.manifest.count(BackupModelKind.CONTACTS))
        assertNull(holder.state.value.errorMessage)
    }

    @Test
    @OriginalCase("AppBackupViewModelExportTests::handleExportResult(.failure(userCancelled)) returns to idle without errorMessage()")
    fun `cancelled picker returns to idle without an error`() = scenario {
        val rig = BackupRig()
        rig.documents.save = { _, _ -> BackupSaveOutcome.Cancelled }
        val holder = holder(rig, scope)
        holder.performExport()
        settle()
        assertEquals(ExportState.Idle, holder.state.value.export)
        assertNull(holder.state.value.exportSummary)
        assertNull(holder.state.value.errorMessage)
    }

    @Test
    @OriginalCase("AppBackupViewModelExportTests::handleExportResult(.failure(other)) surfaces errorMessage()")
    fun `low space while saving surfaces the platform message and returns to idle`() = scenario {
        val rig = BackupRig()
        rig.documents.save = { _, _ -> throw BackupDocumentException.InsufficientSpace(java.io.IOException("No space left on device")) }
        val holder = holder(rig, scope)
        holder.performExport()
        settle()
        assertEquals(ExportState.Idle, holder.state.value.export)
        assertEquals("generic:No space left on device", holder.state.value.errorMessage)
        assertTrue(rig.reports.contains("Export failed"))
    }

    @Test
    @OriginalCase("AppBackupViewModelExportTests::handleExportResult(.success) with no pending export is a no-op()")
    fun `a save outcome with no pending export is ignored`() = scenario {
        val holder = holder(BackupRig(), scope)
        holder.handleSaveOutcome(BackupSaveOutcome.Saved("x.mc1backup"))
        assertNull(holder.state.value.exportSummary)
        assertNull(holder.state.value.errorMessage)
        assertEquals(ExportState.Idle, holder.state.value.export)
    }

    @Test
    @OriginalCase("AppBackupViewModelExportTests::dismissExportSuccess clears the summary()")
    fun `dismissExportSuccess clears the summary`() = scenario {
        val holder = holder(BackupRig(), scope)
        holder.performExport()
        settle()
        assertNotNull(holder.state.value.exportSummary)
        holder.dismissExportSuccess()
        assertNull(holder.state.value.exportSummary)
        assertEquals(ExportState.Idle, holder.state.value.export)
    }

    @Test
    @OriginalCase("AppBackupViewModelTests::Export uses the active services store when one is available()", "platform-adaptation")
    fun `export builds from the engine and the saved bytes are the engine bytes`() = scenario {
        val rig = BackupRig()
        val holder = holder(rig, scope)
        holder.performExport()
        settle()
        assertEquals(listOf("export"), rig.engine.calls)
        assertEquals(1, rig.documents.saved.size)
        assertEquals(128, rig.documents.saved.single().second)
    }

    @Test
    fun `engine export failure shows the typed export-failed message and nothing is saved`() = scenario {
        val rig = BackupRig()
        rig.engine.export = { throw AppBackupException(AppBackupError.ExportFailed(IllegalStateException("db locked"))) }
        val holder = holder(rig, scope)
        holder.performExport()
        settle()
        assertEquals("exportFailed(generic:db locked)", holder.state.value.errorMessage)
        assertTrue(rig.documents.saved.isEmpty())
        assertEquals(ExportState.Idle, holder.state.value.export)
    }

    @Test
    fun `a second export request while one is running is ignored`() = scenario {
        val rig = BackupRig()
        val gate = CompletableDeferred<BackupSaveOutcome>()
        rig.documents.save = { _, _ -> gate.await() }
        val holder = holder(rig, scope)
        holder.performExport()
        assertNull(holder.performExport())
        gate.complete(BackupSaveOutcome.Cancelled)
        settle()
        assertEquals(1, rig.documents.saved.size)
    }

    @Test
    fun `default export filename uses a UTC timestamp`() = scenario {
        val clock = Clock.fixed(Instant.parse("2026-04-19T14:30:05Z"), ZoneOffset.ofHours(-5))
        val holder = holder(BackupRig(), scope, clock)
        assertEquals("MC1 Backup 2026-04-19 143005.mc1backup", holder.defaultExportFilename())
    }

    @Test
    fun `cancelling the scope mid-export resets to idle without an error`() = scenario {
        val rig = BackupRig()
        val gate = CompletableDeferred<BackupSaveOutcome>()
        rig.documents.save = { _, _ -> gate.await() }
        val holder = holder(rig, scope)
        val job = holder.performExport()
        settle()
        job?.cancel()
        settle()
        assertEquals(ExportState.Idle, holder.state.value.export)
        assertNull(holder.state.value.errorMessage)
    }
}

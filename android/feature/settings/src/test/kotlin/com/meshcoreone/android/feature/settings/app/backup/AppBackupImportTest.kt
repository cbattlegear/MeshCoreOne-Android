// PortedFrom: MC1Tests/ViewModels/AppBackupViewModelTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.app.backup

import com.meshcoreone.android.core.model.AppBackupError
import com.meshcoreone.android.core.model.AppBackupException
import com.meshcoreone.android.core.model.BackupContract
import com.meshcoreone.android.feature.settings.app.support.BackupRig
import com.meshcoreone.android.feature.settings.app.support.FakeParsed
import com.meshcoreone.android.feature.settings.app.support.FakeSource
import com.meshcoreone.android.feature.settings.app.support.OriginalCase
import com.meshcoreone.android.feature.settings.app.support.scenario
import com.meshcoreone.android.feature.settings.app.support.settle
import java.io.IOException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AppBackupImportTest {
    private fun holder(rig: BackupRig, scope: CoroutineScope) = AppBackupStateHolder(rig.dependencies, scope, Dispatchers.Unconfined)

    private suspend fun previewed(rig: BackupRig, holder: AppBackupStateHolder): FakeParsed {
        val parsed = FakeParsed(BackupManifest.of(BackupModelKind.CONTACTS to 1))
        rig.engine.parse = { parsed }
        holder.loadAndParse(FakeSource())
        settle()
        assertIs<ImportState.Preview>(holder.state.value.import)
        return parsed
    }

    @Test
    @OriginalCase("AppBackupViewModelTests::Import picker cancellation does not surface an error()")
    fun `a dismissed picker is not an error`() = scenario {
        val rig = BackupRig()
        val holder = holder(rig, scope)
        holder.selectFileToImport()
        settle()
        assertNull(holder.state.value.errorMessage)
        assertEquals(ImportState.Idle, holder.state.value.import)
    }

    @Test
    @OriginalCase("AppBackupViewModelTests::Readable backup URLs load even when no security scope is granted()", "platform-adaptation")
    fun `a picked readable document loads to a preview`() = scenario {
        val rig = BackupRig()
        val parsed = FakeParsed(BackupManifest())
        rig.engine.parse = { parsed }
        rig.documents.pick = { BackupPickOutcome.Picked(FakeSource(sizeBytes = 4)) }
        val holder = holder(rig, scope)
        holder.selectFileToImport()
        settle()
        val preview = assertIs<ImportState.Preview>(holder.state.value.import)
        assertSame(parsed, preview.backup)
        assertNull(holder.state.value.errorMessage)
        assertEquals(BackupManifest(), preview.backup.manifest)
    }

    @Test
    fun `revoked permission or vanished URI shows the invalid-file message before any sheet`() = scenario {
        for (failure in listOf(BackupDocumentException.PermissionRevoked(), BackupDocumentException.UriUnavailable(IOException("gone")))) {
            val rig = BackupRig()
            val holder = holder(rig, scope)
            holder.loadAndParse(FakeSource(failure = failure))
            settle()
            assertEquals("invalidFile", holder.state.value.errorMessage)
            assertEquals(ImportState.Idle, holder.state.value.import)
            assertFalse(holder.state.value.isImportSheetActive)
        }
    }

    @Test
    fun `picker failure surfaces a message`() = scenario {
        val rig = BackupRig()
        rig.documents.pick = { throw BackupDocumentException.PermissionRevoked() }
        val holder = holder(rig, scope)
        holder.selectFileToImport()
        settle()
        assertEquals("invalidFile", holder.state.value.errorMessage)
    }

    @Test
    fun `an oversize file is rejected from the reported size without reading it`() = scenario {
        val rig = BackupRig()
        val source = FakeSource(sizeBytes = BackupContract.MAX_COMPRESSED_BYTES + 5L * 1_048_576)
        val holder = holder(rig, scope)
        holder.loadAndParse(source)
        settle()
        assertEquals("fileTooLarge:55/50", holder.state.value.errorMessage)
        assertEquals(0, source.reads)
        assertTrue(rig.engine.calls.isEmpty())
    }

    @Test
    fun `parse errors map to typed messages and stay out of the sheet`() = scenario {
        val cases = listOf(
            AppBackupError.InvalidFile to "invalidFile",
            AppBackupError.CorruptedManifest to "corruptedManifest",
            AppBackupError.UnsupportedVersion(2, 1) to "unsupportedVersion:2/1",
            AppBackupError.DecompressedTooLarge(512L * 1_048_576) to "decompressedTooLarge:512",
        )
        for ((error, expected) in cases) {
            val rig = BackupRig()
            rig.engine.parse = { throw AppBackupException(error) }
            val holder = holder(rig, scope)
            holder.loadAndParse(FakeSource())
            settle()
            assertEquals(expected, holder.state.value.errorMessage)
            assertEquals(ImportState.Idle, holder.state.value.import)
        }
    }

    @Test
    fun `a newer parse supersedes a slower older one`() = scenario {
        val rig = BackupRig()
        val slow = CompletableDeferred<ParsedBackup>()
        val fast = FakeParsed(BackupManifest.of(BackupModelKind.MESSAGES to 9))
        var first = true
        rig.engine.parse = { if (first) { first = false; slow.await() } else fast }
        val holder = holder(rig, scope)
        holder.loadAndParse(FakeSource())
        holder.loadAndParse(FakeSource())
        settle()
        slow.complete(FakeParsed())
        settle()
        assertSame(fast, (holder.state.value.import as ImportState.Preview).backup)
    }

    @Test
    @OriginalCase("AppBackupViewModelTests::performImport aborts and dismisses when the radio is connected()")
    fun `performImport aborts and dismisses when the radio connected after the preview opened`() = scenario {
        val rig = BackupRig(connected = false)
        val holder = holder(rig, scope)
        previewed(rig, holder)
        rig.gate.isRadioConnected.value = true
        assertNull(holder.performImport())
        assertEquals(ImportState.Idle, holder.state.value.import)
        assertFalse(rig.engine.calls.contains("import"))
    }

    @Test
    @OriginalCase("AppBackupViewModelTests::performImport writes through the process persistence store()", "platform-adaptation")
    fun `performImport restores through the engine and reports the result`() = scenario {
        val rig = BackupRig()
        val holder = holder(rig, scope)
        val parsed = previewed(rig, holder)
        var received: ParsedBackup? = null
        rig.engine.import = { received = it; ImportResult().record(BackupModelKind.CONTACTS, inserted = 1) }
        holder.performImport()
        settle()
        assertSame(parsed, received)
        val success = assertIs<ImportState.Success>(holder.state.value.import)
        assertEquals(1, success.result.counts(BackupModelKind.CONTACTS).inserted)
        assertEquals(listOf("changed", "refreshBlocked"), rig.effects.events)
    }

    @Test
    fun `inserted merged skipped and dropped counts reach the success state`() = scenario {
        val rig = BackupRig()
        val holder = holder(rig, scope)
        previewed(rig, holder)
        rig.engine.import = {
            ImportResult().record(BackupModelKind.MESSAGES, inserted = 4, skipped = 2)
                .record(BackupModelKind.CONTACTS, merged = 3)
                .record(BackupModelKind.CHANNELS, dropped = 1)
        }
        holder.performImport()
        settle()
        val result = assertIs<ImportState.Success>(holder.state.value.import).result
        assertEquals(4, result.totalInserted)
        assertEquals(3, result.totalMerged)
        assertEquals(2, result.totalSkipped)
        assertEquals(1, result.totalDropped)
        assertTrue(result.hasRestoredChanges)
    }

    @Test
    fun `a no-op restore does not notify listeners but still clears affected draft slots`() = scenario {
        val rig = BackupRig()
        val holder = holder(rig, scope)
        previewed(rig, holder)
        val radio = UUID.randomUUID()
        rig.engine.import = { ImportResult(channelSlotsAffectedByImport = mapOf(radio to setOf(2, 3))).record(BackupModelKind.MESSAGES, skipped = 5) }
        holder.performImport()
        settle()
        assertEquals(listOf("slots"), rig.effects.events)
        assertEquals(mapOf(radio to setOf(2, 3)), rig.effects.slots)
        assertFalse(assertIs<ImportState.Success>(holder.state.value.import).result.hasRestoredChanges)
    }

    @Test
    fun `settings-only restore counts as restored changes`() = scenario {
        val rig = BackupRig()
        val holder = holder(rig, scope)
        previewed(rig, holder)
        rig.engine.import = { ImportResult(settingsRestored = true) }
        holder.performImport()
        settle()
        assertEquals(listOf("changed", "refreshBlocked"), rig.effects.events)
    }

    @Test
    fun `blocked-cache refresh failure is reported and does not fail the import`() = scenario {
        val rig = BackupRig()
        rig.effects.refreshFailure = IllegalStateException("services gone")
        val holder = holder(rig, scope)
        previewed(rig, holder)
        rig.engine.import = { ImportResult().record(BackupModelKind.CONTACTS, inserted = 1) }
        holder.performImport()
        settle()
        assertIs<ImportState.Success>(holder.state.value.import)
        assertTrue(rig.reports.any { it.startsWith("Blocked-contact cache refresh failed") })
    }

    @Test
    fun `cancelling an in-flight import latches the flag and ends in the cancelled state`() = scenario {
        val rig = BackupRig()
        val holder = holder(rig, scope)
        previewed(rig, holder)
        val gate = CompletableDeferred<ImportResult>()
        rig.engine.import = { gate.await() }
        holder.performImport()
        settle()
        assertTrue(holder.state.value.isImporting)
        holder.cancelImport()
        assertTrue(holder.state.value.isCancellingImport)
        settle()
        assertEquals(ImportState.Cancelled, holder.state.value.import)
        assertTrue(rig.effects.events.isEmpty())
    }

    @Test
    fun `cancelling before the import starts does not leave the sheet busy`() = runBlocking {
        val rig = BackupRig()
        val holder = holder(rig, this)
        previewed(rig, holder)
        rig.engine.import = { error("Cancelled import must not reach the engine") }
        val operation = requireNotNull(holder.performImport())
        holder.cancelImport()
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(ImportState.Cancelled, holder.state.value.import)
        assertFalse(holder.state.value.isCancellingImport)
    }

    @Test
    fun `low space during restore shows an in-sheet failure with the typed message`() = scenario {
        val rig = BackupRig()
        val holder = holder(rig, scope)
        previewed(rig, holder)
        rig.engine.import = { throw AppBackupException(AppBackupError.ImportFailed(IOException("No space left on device"))) }
        holder.performImport()
        settle()
        val failed = assertIs<ImportState.Failed>(holder.state.value.import)
        assertEquals("importFailed(generic:No space left on device)", failed.message)
        assertNull(holder.state.value.errorMessage)
        assertTrue(holder.state.value.isImportSheetActive)
    }

    @Test
    fun `dismissImportSheet resets import state, cancelling flag and error`() = scenario {
        val rig = BackupRig()
        val holder = holder(rig, scope)
        previewed(rig, holder)
        holder.cancelImport()
        holder.dismissImportSheet()
        assertEquals(ImportState.Idle, holder.state.value.import)
        assertFalse(holder.state.value.isCancellingImport)
        assertNull(holder.state.value.errorMessage)
    }

    @Test
    fun `performImport is ignored unless a preview is showing`() = scenario {
        val holder = holder(BackupRig(), scope)
        assertNull(holder.performImport())
        assertEquals(ImportState.Idle, holder.state.value.import)
    }
}

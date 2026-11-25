// PortedFrom: MC1Tests/Views/Settings/ImportSuccessDroppedFooterTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.app.backup

import com.meshcoreone.android.core.model.AppBackupError
import com.meshcoreone.android.core.model.AppBackupException
import com.meshcoreone.android.feature.settings.app.support.OriginalCase
import com.meshcoreone.android.feature.settings.app.support.TestStrings
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ImportResultPresenterTest {
    @Test
    @OriginalCase("ImportSuccessDroppedFooterTests::Dropped footer blames discover cap, not channel slots, when only discover nodes drop()")
    fun `dropped footer blames the discover cap when only discovered nodes drop`() {
        val result = ImportResult().record(BackupModelKind.DISCOVERED_NODES, dropped = 5)
        val footer = ImportResultPresenter.droppedFooter(result, TestStrings)
        assertEquals("footerDiscovered:1000", footer)
        assertFalse(footer.contains("channel", ignoreCase = true))
    }

    @Test
    @OriginalCase("ImportSuccessDroppedFooterTests::Dropped footer keeps channel copy when only channels drop()")
    fun `dropped footer keeps channel copy when only channels drop`() {
        assertEquals("footerChannels", ImportResultPresenter.droppedFooter(ImportResult().record(BackupModelKind.CHANNELS, dropped = 2), TestStrings))
    }

    @Test
    @OriginalCase("ImportSuccessDroppedFooterTests::Dropped footer uses mixed copy when channels and discover nodes both drop()")
    fun `dropped footer uses mixed copy when both drop`() {
        val result = ImportResult().record(BackupModelKind.CHANNELS, dropped = 1).record(BackupModelKind.DISCOVERED_NODES, dropped = 3)
        assertEquals("footerMixed:1000", ImportResultPresenter.droppedFooter(result, TestStrings))
    }

    @Test
    fun `an insert-led result shows success, added rows in kind order and no other sections`() {
        val p = ImportResultPresenter.present(
            ImportResult().record(BackupModelKind.CONTACTS, inserted = 2).record(BackupModelKind.MESSAGES, inserted = 7), TestStrings,
        )
        assertEquals("success", p.heroTitle)
        assertEquals("added:9", p.heroSubtitle)
        assertEquals(ImportHeroIcon.SUCCESS, p.heroIcon)
        assertEquals(listOf(BackupModelKind.MESSAGES, BackupModelKind.CONTACTS), p.addedRows.map { it.kind })
        assertTrue(p.skippedRows.isEmpty() && p.droppedRows.isEmpty())
        assertNull(p.alreadyHereSummary)
        assertNull(p.droppedFooter)
    }

    @Test
    fun `a merge-only result reports refreshed and uses the success hero`() {
        val p = ImportResultPresenter.present(ImportResult().record(BackupModelKind.CONTACTS, merged = 4), TestStrings)
        assertEquals("success", p.heroTitle)
        assertEquals("refreshed:4", p.heroSubtitle)
        assertTrue(p.addedRows.isEmpty())
    }

    @Test
    fun `skipped rows add the already-here summary and the refreshed note only when merged`() {
        val skippedOnly = ImportResultPresenter.present(ImportResult().record(BackupModelKind.MESSAGES, skipped = 3), TestStrings)
        assertEquals("alreadyHere:3", skippedOnly.alreadyHereSummary)
        assertNull(skippedOnly.alreadyHereRefreshed)
        assertEquals("nothing", skippedOnly.heroTitle)
        assertEquals("nothingSubtitle", skippedOnly.heroSubtitle)
        assertEquals(ImportHeroIcon.INFO, skippedOnly.heroIcon)
        val withMerge = ImportResultPresenter.present(
            ImportResult().record(BackupModelKind.MESSAGES, skipped = 3).record(BackupModelKind.CONTACTS, merged = 2), TestStrings,
        )
        assertEquals("alreadyHereRefreshed:2", withMerge.alreadyHereRefreshed)
    }

    @Test
    fun `an empty result is nothing to import and a settings-only result is a success`() {
        val empty = ImportResultPresenter.present(ImportResult(), TestStrings)
        assertEquals("nothing", empty.heroTitle)
        assertFalse(empty.hasRestoredChanges)
        val settings = ImportResultPresenter.present(ImportResult(settingsRestored = true), TestStrings)
        assertEquals("success", settings.heroTitle)
        assertEquals("nothingSubtitle", settings.heroSubtitle)
    }

    @Test
    fun `dropped rows carry the summary footer and announcement joins title and subtitle`() {
        val p = ImportResultPresenter.present(
            ImportResult().record(BackupModelKind.CHANNELS, inserted = 1, dropped = 2), TestStrings,
        )
        assertEquals("droppedSummary:2", p.droppedSummary)
        assertEquals("footerChannels", p.droppedFooter)
        assertEquals("success. added:1", p.announcement)
    }

    @Test
    fun `manifest rows skip zero counts and keep kind order`() {
        val rows = manifestRows(BackupManifest.of(BackupModelKind.DISCOVERED_NODES to 2, BackupModelKind.MESSAGES to 1, BackupModelKind.DEVICES to 0), TestStrings)
        assertEquals(listOf(BackupModelKind.MESSAGES, BackupModelKind.DISCOVERED_NODES), rows.map { it.kind })
        assertEquals(listOf("label:MESSAGES", "label:DISCOVERED_NODES"), rows.map { it.label })
    }

    @Test
    fun `import result updates return copies and totals add up`() {
        val base = ImportResult()
        val updated = base.record(BackupModelKind.CONTACTS, inserted = 1, merged = 2, skipped = 3, dropped = 4)
        assertEquals(0, base.totalInserted)
        assertEquals(10, updated.counts.values.sumOf { it.inserted + it.merged + it.skipped + it.dropped })
        assertEquals(3, updated.totalRestoredRecordCount)
        assertEquals(BackupModelKind.entries.size, base.counts.size)
    }

    @Test
    fun `backup error messages cover every typed error and nest the underlying cause`() {
        assertEquals("fileTooLarge:60/50", backupUserFacingMessage(AppBackupException(AppBackupError.FileTooLarge(60L * 1_048_576, 50L * 1_048_576)), TestStrings))
        assertEquals("exportFailed(invalidFile)", backupUserFacingMessage(AppBackupException(AppBackupError.ExportFailed(AppBackupException(AppBackupError.InvalidFile))), TestStrings))
        assertEquals("importFailed(corruptedManifest)", backupUserFacingMessage(AppBackupException(AppBackupError.ImportFailed(AppBackupException(AppBackupError.CorruptedManifest))), TestStrings))
        assertEquals("generic:boom", backupUserFacingMessage(IllegalStateException("boom"), TestStrings))
        assertEquals("generic:No space left", backupUserFacingMessage(BackupDocumentException.WriteFailed(java.io.IOException("No space left")), TestStrings))
    }
}

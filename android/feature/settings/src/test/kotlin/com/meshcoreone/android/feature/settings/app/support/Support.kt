// AndroidOnly: WP-318 JVM test support: original-case binding, fakes and a deterministic scenario runner.
package com.meshcoreone.android.feature.settings.app.support

import com.meshcoreone.android.feature.settings.app.backup.BackupConnectionGate
import com.meshcoreone.android.feature.settings.app.backup.BackupDiagnostics
import com.meshcoreone.android.feature.settings.app.backup.BackupDocumentPort
import com.meshcoreone.android.feature.settings.app.backup.BackupEngine
import com.meshcoreone.android.feature.settings.app.backup.BackupExport
import com.meshcoreone.android.feature.settings.app.backup.BackupFeatureDependencies
import com.meshcoreone.android.feature.settings.app.backup.BackupImportEffects
import com.meshcoreone.android.feature.settings.app.backup.BackupManifest
import com.meshcoreone.android.feature.settings.app.backup.BackupModelKind
import com.meshcoreone.android.feature.settings.app.backup.BackupPickOutcome
import com.meshcoreone.android.feature.settings.app.backup.BackupSaveOutcome
import com.meshcoreone.android.feature.settings.app.backup.BackupSource
import com.meshcoreone.android.feature.settings.app.backup.BackupStrings
import com.meshcoreone.android.feature.settings.app.backup.ImportResult
import com.meshcoreone.android.feature.settings.app.backup.ParsedBackup
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/** Original-case binding carried in source: the JUnit XML names the method; this binds it to the frozen Swift case id. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
internal annotation class OriginalCase(val value: String, val disposition: String = "source-behavior")

internal class Scenario(val scope: CoroutineScope)

/** Runs [block] with an eager (Unconfined) scope: fake suspend calls complete synchronously. */
internal fun scenario(block: suspend Scenario.() -> Unit) = runBlocking {
    val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    try {
        Scenario(scope).block()
    } finally {
        scope.cancel()
    }
}

internal suspend fun settle() = repeat(5) { yield() }

internal object TestStrings : BackupStrings {
    override fun importRequiresDisconnect() = "importRequiresDisconnect"
    override fun modelLabel(kind: BackupModelKind) = "label:${kind.name}"
    override fun invalidFile() = "invalidFile"
    override fun fileTooLarge(actualMegabytes: Long, maxMegabytes: Long) = "fileTooLarge:$actualMegabytes/$maxMegabytes"
    override fun decompressedTooLarge(maxMegabytes: Long) = "decompressedTooLarge:$maxMegabytes"
    override fun unsupportedVersion(found: Long, maxSupported: Long) = "unsupportedVersion:$found/$maxSupported"
    override fun corruptedManifest() = "corruptedManifest"
    override fun exportFailed(underlying: String) = "exportFailed($underlying)"
    override fun importFailed(underlying: String) = "importFailed($underlying)"
    override fun genericFailure(failure: Throwable) = "generic:${failure.message}"
    override fun defaultExportFilename(timestamp: String) = "MC1 Backup $timestamp.mc1backup"
    override fun importSuccessTitle() = "success"
    override fun nothingToImportTitle() = "nothing"
    override fun nothingToImportSubtitle() = "nothingSubtitle"
    override fun subtitleAdded(count: Int) = "added:$count"
    override fun subtitleRefreshed(count: Int) = "refreshed:$count"
    override fun alreadyHereSummary(count: Int) = "alreadyHere:$count"
    override fun alreadyHereRefreshed(count: Int) = "alreadyHereRefreshed:$count"
    override fun droppedSummary(count: Int) = "droppedSummary:$count"
    override fun droppedFooterChannels() = "footerChannels"
    override fun droppedFooterDiscoveredNodes(cap: Int) = "footerDiscovered:$cap"
    override fun droppedFooterMixed(cap: Int) = "footerMixed:$cap"
}

internal class FakeParsed(
    override val manifest: BackupManifest = BackupManifest(),
    override val exportDate: Instant = Instant.EPOCH,
    override val appVersion: String = "test",
    override val appBuild: String = "1",
) : ParsedBackup

internal class FakeSource(
    override val sizeBytes: Long? = null,
    private val bytes: ByteArray = ByteArray(4),
    private val failure: Throwable? = null,
) : BackupSource {
    var reads = 0
    override suspend fun readBytes(): ByteArray {
        reads++
        failure?.let { throw it }
        return bytes
    }
}

internal class FakeEngine : BackupEngine {
    var export: suspend () -> BackupExport = { BackupExport(ByteArray(128) { 0x7F }, BackupManifest.of(BackupModelKind.MESSAGES to 3, BackupModelKind.CONTACTS to 2)) }
    var parse: suspend (ByteArray) -> ParsedBackup = { FakeParsed() }
    var import: suspend (ParsedBackup) -> ImportResult = { ImportResult() }
    val calls = mutableListOf<String>()

    override suspend fun export(): BackupExport = export.invoke().also { calls += "export" }
    override suspend fun parse(data: ByteArray): ParsedBackup = parse.invoke(data).also { calls += "parse:${data.size}" }
    override suspend fun importBackup(backup: ParsedBackup): ImportResult {
        calls += "import"
        return import.invoke(backup)
    }
}

internal class FakeDocuments : BackupDocumentPort {
    var save: suspend (String, ByteArray) -> BackupSaveOutcome = { name, _ -> BackupSaveOutcome.Saved(name) }
    var pick: suspend () -> BackupPickOutcome = { BackupPickOutcome.Cancelled }
    val saved = mutableListOf<Pair<String, Int>>()

    override suspend fun save(suggestedName: String, data: ByteArray): BackupSaveOutcome {
        saved += suggestedName to data.size
        return save.invoke(suggestedName, data)
    }

    override suspend fun pick(): BackupPickOutcome = pick.invoke()
}

internal class FakeEffects : BackupImportEffects {
    val events = mutableListOf<String>()
    var slots: Map<UUID, Set<Int>>? = null
    var refreshFailure: Throwable? = null

    override fun restoredDataChanged() { events += "changed" }
    override fun channelSlotsAffected(slotsByRadio: Map<UUID, Set<Int>>) { events += "slots"; slots = slotsByRadio }
    override suspend fun refreshBlockedContactsCache() {
        events += "refreshBlocked"
        refreshFailure?.let { throw it }
    }
}

internal class FakeGate(connected: Boolean) : BackupConnectionGate {
    override val isRadioConnected = MutableStateFlow(connected)
    override suspend fun <T : Any> withOfflineImport(action: suspend () -> T): T? =
        if (isRadioConnected.value) null else action()
}

internal class BackupRig(connected: Boolean = false) {
    val engine = FakeEngine()
    val documents = FakeDocuments()
    val effects = FakeEffects()
    val gate = FakeGate(connected)
    val reports = mutableListOf<String>()
    val dependencies = BackupFeatureDependencies(
        engine, documents, gate, effects, BackupDiagnostics { message, _ -> reports += message }, TestStrings,
    )
}

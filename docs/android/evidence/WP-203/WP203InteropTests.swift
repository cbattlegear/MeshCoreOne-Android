// AndroidOnly: WP-203 Data-only interoperability harness around frozen production SwiftData/backup code.
// This file is staged into an isolated macOS test target; it never modifies the reference tree.
import Foundation
@testable import MC1Services
import SwiftData
import XCTest

final class WP203InteropTests: XCTestCase {
  func testRealKotlinExportRestoresIntoSwiftDataAndSwiftExportsForRoom() async throws {
    let environment = ProcessInfo.processInfo.environment
    let inputPath = try XCTUnwrap(environment["WP203_KOTLIN_INPUT"])
    let outputPath = try XCTUnwrap(environment["WP203_SWIFT_OUTPUT"])
    let output = URL(fileURLWithPath: outputPath, isDirectory: true)
    try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
    let input = try Data(contentsOf: URL(fileURLWithPath: inputPath))
    let envelope = try parseBackup(data: input)
    XCTAssertEqual(envelope.version, 1)
    XCTAssertTrue(envelope.manifest.validate(against: envelope))
    for kind in BackupModelKind.allCases {
      XCTAssertEqual(envelope.manifest.count(for: kind), 1)
    }
    XCTAssertEqual(envelope.messages[0].text, "Hi\u{4F60}\u{1F600}\u{05E9}\u{05DC}\u{05D5}\u{05DD}")
    XCTAssertEqual(envelope.messages[0].sortDate, Date(timeIntervalSince1970: -0.25))
    XCTAssertEqual(envelope.contacts[0].avatarImageData, Data([0x00, 0x80, 0xFF]))
    XCTAssertEqual(envelope.messages[0].ackCode, 0x8000_0000)
    XCTAssertEqual(envelope.messages[0].timestamp, UInt32.max)
    XCTAssertEqual(envelope.contacts[0].outPathLength, 255)
    XCTAssertEqual(envelope.contacts[0].outPath, Data())
    XCTAssertEqual(envelope.discoveredNodes[0].outPathLength, 255)

    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let suiteName = "WP203Interop.\(UUID().uuidString)"
    nonisolated(unsafe) let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let service = AppBackupService()
    let first = try await service.importBackup(envelope: envelope, into: store, defaults: defaults)
    XCTAssertEqual(first.totalInserted, 12)
    XCTAssertEqual(first.totalSkipped, 0)
    XCTAssertTrue(first.userDefaultsRestored)
    let restored = try await store.fetchBackupExportSnapshot()
    try compareRestored(snapshot: restored, envelope: envelope)
    let preferences = BackupUserDefaults.snapshot(from: defaults)
    XCTAssertEqual(preferences, envelope.userDefaults)
    let second = try await service.importBackup(envelope: envelope, into: store, defaults: defaults)
    XCTAssertEqual(second.totalInserted, 0)
    XCTAssertFalse(second.userDefaultsRestored)

    // The public exporter reads standard preferences. Populate only missing backup keys
    // through its real source write-if-missing function in this ephemeral test process.
    let previousStandard = BackupUserDefaults.snapshot(from: .standard)
    XCTAssertEqual(previousStandard, BackupUserDefaults())
    let addedStandard = preferences.restore(to: .standard)
    defer { BackupUserDefaults.removeKeys(addedStandard, from: .standard) }
    let exported = try await service.export(persistenceStore: store)
    let parsed = try parseBackup(data: exported.data)
    XCTAssertEqual(exported.manifest, parsed.manifest)
    XCTAssertTrue(parsed.manifest.validate(against: parsed))
    XCTAssertEqual(parsed.userDefaults, preferences)
    XCTAssertEqual(parsed.messages[0].sortDate, envelope.messages[0].sortDate)
    XCTAssertEqual(parsed.messages[0].radioID, envelope.messages[0].radioID)
    XCTAssertEqual(parsed.messages[0].id, envelope.messages[0].id)
    try exported.data.write(to: output.appendingPathComponent("swift-export.meshcoreone"))
    try makeBackupJSONEncoder().encode(parsed).write(to: output.appendingPathComponent("swift-export.json"))
    let proof: [String: Any] = [
      "schema_version": 1,
      "producer": "actual-frozen-SwiftData-restore-and-AppBackupService-export",
      "source_sha": "db14559b39d32322b06477c6ae676112f583db50",
      "source_arrays": BackupModelKind.allCases.map(\.rawValue),
      "inserted": first.totalInserted,
      "skipped": first.totalSkipped,
      "preferences_restored": first.userDefaultsRestored,
      "reimport_inserted": second.totalInserted,
      "message_id": parsed.messages[0].id.uuidString,
      "radio_id": parsed.devices[0].radioID.uuidString,
      "restore_semantics_compared": true
    ]
    try JSONSerialization.data(withJSONObject: proof, options: [.sortedKeys])
      .write(to: output.appendingPathComponent("swift-room-proof.json"))
  }

  private func compareRestored(snapshot: BackupExportSnapshot, envelope: AppBackupEnvelope) throws {
    XCTAssertEqual(snapshot.devices.count, 1)
    let restoredDevice = try XCTUnwrap(snapshot.devices.first)
    XCTAssertNotEqual(restoredDevice.id, envelope.devices[0].id)
    XCTAssertEqual(restoredDevice.radioID, envelope.devices[0].radioID)
    XCTAssertEqual(restoredDevice.publicKey, envelope.devices[0].publicKey)
    XCTAssertEqual(snapshot.contacts, envelope.contacts)
    XCTAssertEqual(snapshot.channels.count, 1)
    let restoredChannel = try XCTUnwrap(snapshot.channels.first)
    XCTAssertNotEqual(restoredChannel.id, envelope.channels[0].id)
    XCTAssertEqual(restoredChannel.radioID, envelope.channels[0].radioID)
    XCTAssertEqual(restoredChannel.secret, envelope.channels[0].secret)
    XCTAssertEqual(restoredChannel.floodScopeModeRawValue, envelope.channels[0].floodScopeModeRawValue)
    XCTAssertEqual(snapshot.messages, envelope.messages)
    XCTAssertEqual(snapshot.messageRepeats, envelope.messageRepeats)
    XCTAssertEqual(snapshot.reactions, envelope.reactions)
    XCTAssertEqual(snapshot.remoteNodeSessions, envelope.remoteNodeSessions)
    XCTAssertEqual(snapshot.roomMessages, envelope.roomMessages)
    XCTAssertEqual(snapshot.savedTracePaths, envelope.savedTracePaths)
    XCTAssertEqual(snapshot.blockedChannelSenders, envelope.blockedChannelSenders)
    XCTAssertEqual(snapshot.nodeStatusSnapshots, envelope.nodeStatusSnapshots)
    XCTAssertEqual(snapshot.discoveredNodes.count, 1)
    let discovered = try XCTUnwrap(snapshot.discoveredNodes.first)
    XCTAssertNotEqual(discovered.id, envelope.discoveredNodes[0].id)
    XCTAssertEqual(discovered.radioID, envelope.discoveredNodes[0].radioID)
    XCTAssertEqual(discovered.publicKey, envelope.discoveredNodes[0].publicKey)
    XCTAssertEqual(discovered.name, envelope.discoveredNodes[0].name)
    XCTAssertEqual(discovered.outPathLength, envelope.discoveredNodes[0].outPathLength)
    XCTAssertEqual(discovered.outPath, envelope.discoveredNodes[0].outPath)
  }
}

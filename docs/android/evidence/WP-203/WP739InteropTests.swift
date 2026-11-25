// AndroidOnly: WP-203 Real current-upstream SwiftData ownership interop on macOS.
import Foundation
@testable import MC1Services
import SwiftData
import XCTest

final class WP739InteropTests: XCTestCase {
  private let sourceRadio = UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!
  private let androidRadio = UUID(uuidString: "FEDCBA98-7654-3210-FEDC-BA9876543210")!
  private let swiftRadio = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
  private let selfID = UUID(uuidString: "00000000-0000-0000-0000-00000000000C")!
  private let remoteID = UUID(uuidString: "00000000-0000-0000-0000-00000000000D")!
  private let deviceKey = Data(repeating: 0xD1, count: 32)
  private let nodeKey = Data(repeating: 0xD2, count: 32)
  private let targetID = UUID(uuidString: "00000000-0000-0000-0000-0000000000C8")!
  private let reactionID = UUID(uuidString: "00000000-0000-0000-0000-0000000000C9")!
  private let androidTargetID = UUID(uuidString: "00000000-0000-0000-0000-0000000000CA")!

  func testActualSwiftDataInteroperability() async throws {
    let environment = ProcessInfo.processInfo.environment
    let output = URL(fileURLWithPath: try XCTUnwrap(environment["WP739_OUTPUT"]), isDirectory: true)
    try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
    switch try XCTUnwrap(environment["WP739_MODE"]) {
    case "produce":
      try await produce(output: output)
    case "consume":
      let input = URL(fileURLWithPath: try XCTUnwrap(environment["WP739_INPUT"]), isDirectory: true)
      try await consume(input: input)
    default:
      XCTFail("Unknown interoperability stage")
    }
  }

  private func produce(output: URL) async throws {
    let timestamp = Date(timeIntervalSince1970: 1_700_000_000)
    let device = DeviceDTO.testDevice(id: UUID(), radioID: sourceRadio, publicKey: deviceKey)
    let selfRow = NodeStatusSnapshotDTO.testSnapshot(
      id: selfID, timestamp: timestamp, nodePublicKey: nodeKey, selfRadioID: sourceRadio,
      rxAirtimeSeconds: 120, packetsSent: 42, packetsReceived: 43, receiveErrors: 2,
      sentDirect: 11, sentFlood: 31, receivedDirect: 12, receivedFlood: 31,
      directDuplicates: 3, floodDuplicates: 4,
      neighborSnapshots: [NeighborSnapshotEntry(publicKeyPrefix: Data([0x80, 0xFE]), snr: -1.5, secondsAgo: 42)],
      telemetryEntries: [TelemetrySnapshotEntry(channel: 1, type: "voltage", value: 4.1)],
      latitude: 47.6062, longitude: -122.3321, altitude: 12
    )
    let remote = NodeStatusSnapshotDTO.testSnapshot(
      id: remoteID, timestamp: timestamp, nodePublicKey: nodeKey,
      batteryMillivolts: 3700, uptimeSeconds: 60
    )
    let contacts = [
      ContactDTO.testContact(
        id: UUID(uuidString: "00000000-0000-0000-0000-000000000065")!,
        radioID: sourceRadio, publicKey: Data(repeating: 0xE1, count: 32),
        flags: 1, isFavorite: true, isPinned: false
      ),
      ContactDTO.testContact(
        id: UUID(uuidString: "00000000-0000-0000-0000-000000000066")!,
        radioID: sourceRadio, publicKey: Data(repeating: 0xE2, count: 32),
        isFavorite: false, isPinned: true
      )
    ]
    let channel = ChannelDTO.testChannel(radioID: sourceRadio, isPinned: true)
    let room = RemoteNodeSessionDTO.testSession(radioID: sourceRadio, isPinned: true)
    let target = MessageDTO.testChannelMessage(
      id: targetID, radioID: sourceRadio, channelIndex: channel.index, text: "Reaction target",
      timestamp: 1_700_000_000, createdAt: timestamp, direction: .incoming, senderNodeName: "Peer"
    )
    var reaction = MessageDTO.testChannelMessage(
      id: reactionID, radioID: sourceRadio, channelIndex: channel.index, text: "Reaction",
      timestamp: 1_700_000_001, createdAt: timestamp
    )
    reaction.reactionTargetMessageID = target.id
    reaction.reactionSenderName = "Nate"
    let source = AppBackupEnvelope.test(
      devices: [device], contacts: contacts, channels: [channel], messages: [target, reaction], remoteNodeSessions: [room],
      nodeStatusSnapshots: [selfRow, remote]
    )
    let store = PersistenceStore(modelContainer: try PersistenceStore.createContainer(inMemory: true))
    let service = AppBackupService()
    let result = try await service.importBackup(envelope: source, into: store)
    XCTAssertEqual(result.counts[.nodeStatusSnapshots]?.inserted, 2)
    let exported = try await service.export(persistenceStore: store)
    let parsed = try parseBackup(data: exported.data)
    XCTAssertEqual(parsed.version, 2)
    XCTAssertEqual(parsed.nodeStatusSnapshots.count, 2)
    XCTAssertEqual(parsed.contacts.count, 2)
    XCTAssertEqual(parsed.contacts.first { $0.publicKey == Data(repeating: 0xE1, count: 32) }?.isPinned, false)
    XCTAssertEqual(parsed.contacts.first { $0.publicKey == Data(repeating: 0xE2, count: 32) }?.isPinned, true)
    XCTAssertEqual(parsed.channels.first?.isPinned, true)
    XCTAssertEqual(parsed.remoteNodeSessions.first?.isPinned, true)
    XCTAssertEqual(parsed.messages.first { $0.id == reactionID }?.reactionTargetMessageID, targetID)
    XCTAssertEqual(parsed.messages.first { $0.id == reactionID }?.reactionSenderName, "Nate")
    XCTAssertEqual(parsed.nodeStatusSnapshots.first { $0.id == selfID }?.selfRadioID, sourceRadio)
    try exported.data.write(to: output.appendingPathComponent("swift-v2.meshcoreone"))
  }

  private func consume(input: URL) async throws {
    let service = AppBackupService()
    for version in [1, 2] {
      let data = try Data(contentsOf: input.appendingPathComponent("android-v\(version).meshcoreone"))
      let envelope = try parseBackup(data: data)
      XCTAssertEqual(envelope.version, version)
      XCTAssertTrue(envelope.manifest.validate(against: envelope))
      XCTAssertEqual(envelope.devices.count, 1)
      XCTAssertEqual(envelope.devices[0].radioID, androidRadio)
      XCTAssertEqual(envelope.devices[0].publicKey, deviceKey)
      let store = PersistenceStore(modelContainer: try PersistenceStore.createContainer(inMemory: true))
      try await store.saveDevice(DeviceDTO.testDevice(id: UUID(), radioID: swiftRadio, publicKey: deviceKey))
      let result = try await service.importBackup(envelope: envelope, into: store)
      let contacts = try await store.fetchContacts(radioID: swiftRadio)
      let radioFavorite = try XCTUnwrap(contacts.first { $0.publicKey == Data(repeating: 0xE1, count: 32) })
      let unpinned = try XCTUnwrap(contacts.first { $0.publicKey == Data(repeating: 0xE2, count: 32) })
      XCTAssertTrue(radioFavorite.isFavorite)
      XCTAssertTrue(radioFavorite.isPinned)
      XCTAssertEqual(radioFavorite.flags, 1)
      XCTAssertFalse(unpinned.isFavorite)
      XCTAssertFalse(unpinned.isPinned)
      let channels = try await store.fetchChannels(radioID: swiftRadio)
      let rooms = try await store.fetchRemoteNodeSessions(radioID: swiftRadio)
      XCTAssertEqual(channels.count, 1)
      XCTAssertEqual(rooms.count, 1)
      XCTAssertFalse(try XCTUnwrap(channels.first).isPinned)
      XCTAssertFalse(try XCTUnwrap(rooms.first).isPinned)
      let fetchedReaction = try await store.fetchMessage(id: reactionID)
      let reaction = try XCTUnwrap(fetchedReaction)
      XCTAssertEqual(reaction.radioID, swiftRadio)
      XCTAssertEqual(reaction.reactionTargetMessageID, androidTargetID)
      XCTAssertEqual(reaction.reactionSenderName, "Nate")
      let fetchedTarget = try await store.fetchMessage(id: androidTargetID)
      let target = try XCTUnwrap(fetchedTarget)
      XCTAssertEqual(target.radioID, swiftRadio)
      XCTAssertEqual(target.text, "Reaction target")
      let exportResult = try await service.export(persistenceStore: store)
      let exported = try parseBackup(data: exportResult.data)
      XCTAssertEqual(exported.messages.first { $0.id == reactionID }?.reactionTargetMessageID, androidTargetID)
      XCTAssertEqual(exported.messages.first { $0.id == reactionID }?.reactionSenderName, "Nate")
      XCTAssertEqual(result.counts[.nodeStatusSnapshots]?.inserted, version == 2 ? 2 : 1)
      let remote = try await store.fetchNodeStatusSnapshots(nodePublicKey: nodeKey, since: nil)
      XCTAssertEqual(remote.count, 1)
      XCTAssertEqual(remote.first?.id, remoteID)
      XCTAssertEqual(remote.first?.batteryMillivolts, 3700)
      XCTAssertEqual(remote.first?.uptimeSeconds, 60)
      try assertPayload(try XCTUnwrap(remote.first), matches: XCTUnwrap(envelope.nodeStatusSnapshots.first { $0.id == remoteID }))
      let selfRows = try await store.fetchSelfTelemetrySnapshots(radioID: swiftRadio, nodePublicKey: nodeKey)
      XCTAssertEqual(selfRows.count, version == 2 ? 1 : 0)
      if version == 2 {
        let row = try XCTUnwrap(selfRows.first)
        XCTAssertEqual(row.id, selfID)
        XCTAssertEqual(row.selfRadioID, swiftRadio)
        XCTAssertEqual(row.timestamp, remote[0].timestamp)
        XCTAssertEqual(row.latitude, 47.6062)
        XCTAssertEqual(row.longitude, -122.3321)
        XCTAssertEqual(row.altitude, 12)
        XCTAssertEqual(row.telemetryEntries?.first?.type, "voltage")
        XCTAssertEqual(row.telemetryEntries?.first?.value, 4.1)
        try assertPayload(row, matches: XCTUnwrap(envelope.nodeStatusSnapshots.first { $0.id == selfID }))
      }
      let repeated = try await service.importBackup(envelope: envelope, into: store)
      XCTAssertEqual(repeated.counts[.nodeStatusSnapshots]?.inserted, 0)
    }
  }

  private func assertPayload(_ actual: NodeStatusSnapshotDTO, matches expected: NodeStatusSnapshotDTO) {
    XCTAssertEqual(actual.id, expected.id)
    XCTAssertEqual(actual.timestamp, expected.timestamp)
    XCTAssertEqual(actual.nodePublicKey, expected.nodePublicKey)
    XCTAssertEqual(actual.batteryMillivolts, expected.batteryMillivolts)
    XCTAssertEqual(actual.lastSNR, expected.lastSNR)
    XCTAssertEqual(actual.lastRSSI, expected.lastRSSI)
    XCTAssertEqual(actual.noiseFloor, expected.noiseFloor)
    XCTAssertEqual(actual.uptimeSeconds, expected.uptimeSeconds)
    XCTAssertEqual(actual.rxAirtimeSeconds, expected.rxAirtimeSeconds)
    XCTAssertEqual(actual.packetsSent, expected.packetsSent)
    XCTAssertEqual(actual.packetsReceived, expected.packetsReceived)
    XCTAssertEqual(actual.receiveErrors, expected.receiveErrors)
    XCTAssertEqual(actual.sentDirect, expected.sentDirect)
    XCTAssertEqual(actual.sentFlood, expected.sentFlood)
    XCTAssertEqual(actual.receivedDirect, expected.receivedDirect)
    XCTAssertEqual(actual.receivedFlood, expected.receivedFlood)
    XCTAssertEqual(actual.directDuplicates, expected.directDuplicates)
    XCTAssertEqual(actual.floodDuplicates, expected.floodDuplicates)
    XCTAssertEqual(actual.postedCount, expected.postedCount)
    XCTAssertEqual(actual.postPushCount, expected.postPushCount)
    XCTAssertEqual(actual.neighborSnapshots, expected.neighborSnapshots)
    XCTAssertEqual(actual.telemetryEntries, expected.telemetryEntries)
    XCTAssertEqual(actual.latitude, expected.latitude)
    XCTAssertEqual(actual.longitude, expected.longitude)
    XCTAssertEqual(actual.altitude, expected.altitude)
  }
}

// AndroidOnly: WP-004 Executed fixture harness around exact pinned production Swift codecs.
// GPLv3 application / MIT MeshCore source notices accompany the staged reference fragments.
import Foundation

struct OracleFailure: Error, CustomStringConvertible {
  let description: String
}

struct ExecutedCase: Codable {
  let name: String
  let assertions: Int
}

@MainActor
final class AssertionSuite {
  private(set) var tests: [ExecutedCase] = []
  private var assertions = 0

  func expect(_ condition: @autoclosure () throws -> Bool, _ message: String) throws {
    assertions += 1
    guard try condition() else { throw OracleFailure(description: message) }
  }

  func test(_ name: String, _ body: () throws -> Void) throws {
    let before = assertions
    try body()
    guard assertions > before else { throw OracleFailure(description: "Zero assertions in \(name)") }
    tests.append(ExecutedCase(name: name, assertions: assertions - before))
  }

  func expectBackupError(
    _ operation: () throws -> AppBackupEnvelope,
    matching predicate: (AppBackupError) -> Bool
  ) throws {
    do {
      _ = try operation()
      throw OracleFailure(description: "Expected a typed production backup error")
    } catch let error as AppBackupError {
      try expect(predicate(error), "Wrong typed production backup error: \(error)")
    }
  }
}

func uuid(_ value: Int) throws -> UUID {
  guard let result = UUID(uuidString: String(format: "00000000-0000-0000-0000-%012X", value)) else {
    throw OracleFailure(description: "Invalid controlled fixture UUID")
  }
  return result
}

func fixtureEnvelope() throws -> AppBackupEnvelope {
  guard let radioID = UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE") else {
    throw OracleFailure(description: "Invalid controlled radio ID")
  }
  let messageDate = Date(timeIntervalSince1970: 1_700_000_501.1234567)
  var device = try DeviceDTO.testDevice(
    id: uuid(1), radioID: radioID, publicKey: Data(repeating: 0xAB, count: 32),
    lastConnected: Date(timeIntervalSince1970: 0.5)
  )
  device.connectionMethods = try [
    .bluetooth(peripheralUUID: uuid(14), displayName: nil),
    .wifi(host: "radio.local", port: 5000, displayName: "Reference")
  ]
  var message = try MessageDTO.testDirectMessage(
    id: uuid(4), radioID: radioID, contactID: uuid(2),
    text: "Hi\u{4F60}\u{1F600}\u{05E9}\u{05DC}\u{05D5}\u{05DD}",
    timestamp: UInt32.max, createdAt: messageDate, direction: .incoming,
    status: .delivered, textType: .signedPlain, ackCode: 0x8000_0000,
    pathNodes: Data([0x80, 0xFF]), senderKeyPrefix: Data([0xAB, 0xCD])
  )
  message.failureSeen = true
  message.routeType = .tcDirect
  message.regionScopeMatches = ["US", "CA"]
  var preferences = BackupUserDefaults()
  preferences.hasCompletedOnboarding = true
  preferences.showInlineImages = false
  preferences.selectedThemeID = "default"
  preferences.regionSelection = RegionSelection(countryCode: "US", source: .manual)
  return try AppBackupEnvelope.test(
    version: AppBackupEnvelope.legacyVersion,
    exportDate: Date(timeIntervalSince1970: 1_700_000_500.9876542),
    appVersion: "reference-oracle", appBuild: "004",
    devices: [device],
    contacts: [.testContact(
      id: uuid(2), radioID: radioID, lastHeardTimestamp: UInt32.max,
      avatarImageData: Data([0x00, 0x80, 0xFF])
    )],
    channels: [.testChannel(
      id: uuid(3), radioID: radioID, index: 2, secret: Data(repeating: 0x80, count: 16),
      notificationLevel: .mentionsOnly, floodScope: .region("US")
    )],
    messages: [message],
    messageRepeats: [.testRepeat(id: uuid(5), messageID: uuid(4), receivedAt: messageDate)],
    reactions: [ReactionDTO(
      id: uuid(6), messageID: uuid(4), emoji: "\u{1F600}", senderName: "Reference",
      messageHash: "source-input", rawText: "controlled reaction", receivedAt: messageDate,
      contactID: uuid(2), radioID: radioID
    )],
    roomMessages: [.testRoomMessage(
      id: uuid(7), sessionID: uuid(8), createdAt: messageDate, failureSeen: true
    )],
    remoteNodeSessions: [RemoteNodeSessionDTO(
      id: uuid(8), radioID: radioID, publicKey: Data(repeating: 0xCD, count: 32),
      name: "Reference room", role: .roomServer, permissionLevel: .admin,
      lastMessageDate: messageDate
    )],
    savedTracePaths: [.testPath(
      id: uuid(9), radioID: radioID, createdDate: messageDate,
      runs: [.testRun(id: uuid(10), date: messageDate)]
    )],
    blockedChannelSenders: [BlockedChannelSenderDTO(
      id: uuid(11), name: "Blocked fixture", radioID: radioID, dateBlocked: messageDate
    )],
    nodeStatusSnapshots: [.testSnapshot(
      id: uuid(12), timestamp: Date(timeIntervalSince1970: 1_700_000_502.7654321),
      neighborSnapshots: [NeighborSnapshotEntry(publicKeyPrefix: Data([0x80, 0xFF]), snr: -1.5, secondsAgo: 3)],
      telemetryEntries: [TelemetrySnapshotEntry(channel: 1, type: "temperature", value: 25.5)]
    )],
    discoveredNodes: [DiscoveredNodeDTO(
      id: uuid(13), radioID: radioID, publicKey: Data(repeating: 0xAB, count: 32),
      name: "Discovered fixture", typeRawValue: 2, lastHeard: messageDate,
      lastAdvertTimestamp: UInt32.max, latitude: 37.3349, longitude: -122.009,
      outPathLength: 3, outPath: Data([0x80, 0xFF, 0x00]),
      inboundHopCount: 2, inboundHopAdvertTimestamp: 99
    )],
    userDefaults: preferences
  )
}

func object(_ data: Data) throws -> [String: Any] {
  guard let result = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
    throw OracleFailure(description: "Expected a JSON envelope object")
  }
  return result
}

func array(_ value: [String: Any], _ key: String) throws -> [[String: Any]] {
  guard let result = value[key] as? [[String: Any]] else {
    throw OracleFailure(description: "Missing controlled JSON array: \(key)")
  }
  return result
}

func json(_ value: [String: Any]) throws -> Data {
  try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
}

func modified(
  _ encoded: Data,
  _ transform: (inout [String: Any]) throws -> Void
) throws -> Data {
  var value = try object(encoded)
  try transform(&value)
  return try json(value).zlibCompressed()
}

@MainActor
func executeCryptoTests(suite: AssertionSuite) throws -> [String: Any] {
  let sourceSHA = "5e980a53e077c45a1270170612fdf5dae97792ff"
  let channelOracle = SourceChannelOracle()
  let normal = try channelOracle.packet(timestamp: 1_703_123_456, txtType: 0, message: "Alice: Hello mesh!")
  let utf8Text = "Hi\u{4F60}\u{1F600}"
  let highBit = try channelOracle.packet(timestamp: 0x8000_0000, txtType: 2, message: utf8Text)
  try suite.test("channel-crypto-normal") {
    guard case let .success(timestamp, type, text) = ChannelCrypto.decrypt(payload: normal, secret: channelOracle.secret) else {
      throw OracleFailure(description: "Pinned channel crypto rejected its independent Swift-test oracle")
    }
    try suite.expect(timestamp == 1_703_123_456 && type == 0 && text == "Alice: Hello mesh!", "Normal plaintext semantics")
  }
  try suite.test("channel-crypto-high-bit-utf8") {
    guard case let .success(timestamp, type, text) = ChannelCrypto.decrypt(payload: highBit, secret: channelOracle.secret) else {
      throw OracleFailure(description: "Pinned crypto rejected high-bit/UTF-8 input")
    }
    try suite.expect(timestamp == 0x8000_0000 && type == 2 && text == utf8Text, "Unsigned little-endian timestamp/UTF-8")
  }
  try suite.test("channel-crypto-corrupted-mac") {
    var corrupted = normal
    corrupted[0] ^= 0xFF
    if case .hmacFailed = ChannelCrypto.decrypt(payload: corrupted, secret: channelOracle.secret) {
      try suite.expect(true, "Source typed HMAC failure")
    } else { throw OracleFailure(description: "Wrong corrupted-MAC outcome") }
  }
  try suite.test("channel-crypto-wrong-key") {
    if case .hmacFailed = ChannelCrypto.decrypt(payload: normal, secret: Data(repeating: 0, count: 16)) {
      try suite.expect(true, "Source wrong-key failure")
    } else { throw OracleFailure(description: "Wrong incorrect-key outcome") }
  }
  try suite.test("channel-crypto-truncated") {
    if case .payloadTooShort = ChannelCrypto.decrypt(payload: Data([0x00, 0x01, 0x02, 0x03]), secret: channelOracle.secret) {
      try suite.expect(true, "Source short-packet failure")
    } else { throw OracleFailure(description: "Wrong truncated-crypto outcome") }
  }
  return [
    "source_sha": sourceSHA,
    "origin": "Actual compiled pinned Swift-test CommonCrypto/CryptoKit helpers; no candidate Kotlin",
    "vectors": [
      ["id": "normal", "packet_hex": normal.map { String(format: "%02x", $0) }.joined(), "byte_count": normal.count],
      ["id": "high-bit-utf8", "packet_hex": highBit.map { String(format: "%02x", $0) }.joined(), "byte_count": highBit.count]
    ]
  ]
}

@MainActor
func executeTests(output: URL) throws {
  let sourceSHA = "5e980a53e077c45a1270170612fdf5dae97792ff"
  let suite = AssertionSuite()
  let envelope = try fixtureEnvelope()
  let encoded = try makeBackupJSONEncoder().encode(envelope)
  let compressed = try encoded.zlibCompressed()
  let value = try object(encoded)

  try suite.test("version-and-unix-date") {
    try suite.expect(envelope.version == 1, "Envelope version")
    try suite.expect(value["exportDate"] as? Double == 1_700_000_500.9876542, "Unix fractional export date")
    try suite.expect(value["appBuild"] as? String == "004", "Build text")
  }
  try suite.test("all-arrays-and-manifest") {
    for kind in BackupModelKind.allCases {
      try suite.expect(envelope.manifest.count(for: kind) == 1, "Missing manifest family: \(kind)")
    }
    try suite.expect(envelope.manifest.validate(against: envelope), "Actual manifest validation")
  }
  try suite.test("binary-and-uuid") {
    let contact = try array(value, "contacts")[0]
    try suite.expect(contact["avatarImageData"] as? String == "AID/", "Codable Data must be base64")
    let device = try array(value, "devices")[0]
    try suite.expect(device["radioID"] as? String == "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", "Foundation UUID canonical text")
    let decoded = try makeBackupJSONDecoder().decode(AppBackupEnvelope.self, from: encoded)
    try suite.expect(decoded.contacts[0].avatarImageData == Data([0x00, 0x80, 0xFF]), "Binary high bits")
  }
  try suite.test("raw-and-associated-enums") {
    let message = try array(value, "messages")[0]
    try suite.expect(message["status"] as? Int == 3, "MessageStatus raw value")
    try suite.expect(message["direction"] as? Int == 0, "MessageDirection raw value")
    try suite.expect(message["textType"] as? Int == 2, "TextType raw value")
    let device = try array(value, "devices")[0]
    guard let methods = device["connectionMethods"] as? [[String: Any]] else {
      throw OracleFailure(description: "Missing Codable associated-value enums")
    }
    try suite.expect(methods.count == 2, "Connection methods count")
    try suite.expect(methods[0]["bluetooth"] is [String: Any], "Bluetooth enum case/key")
    try suite.expect(methods[1]["wifi"] is [String: Any], "WiFi enum case/key")
    let decoded = try makeBackupJSONDecoder().decode(AppBackupEnvelope.self, from: encoded)
    try suite.expect(decoded.devices[0].connectionMethods == envelope.devices[0].connectionMethods, "Associated payloads")
  }
  try suite.test("fractional-date-precision") {
    let decoded = try makeBackupJSONDecoder().decode(AppBackupEnvelope.self, from: encoded)
    try suite.expect(decoded.exportDate == envelope.exportDate, "Fractional export date")
    try suite.expect(decoded.messages[0].createdAt == envelope.messages[0].createdAt, "Fractional message date")
    try suite.expect(decoded.nodeStatusSnapshots[0].timestamp == envelope.nodeStatusSnapshots[0].timestamp, "Fractional snapshot date")
  }
  try suite.test("compressed-reference-decode") {
    let parsed = try parseBackup(data: compressed)
    try suite.expect(parsed.manifest == envelope.manifest, "Compressed source export")
    try suite.expect(parsed.messages == envelope.messages, "Exact decoded source message semantics")
    try suite.expect(parsed.discoveredNodes == envelope.discoveredNodes, "Exact discovered-node semantics")
  }
  try suite.test("legacy-discovered-defaults") {
    let data = try modified(encoded) {
      $0.removeValue(forKey: "discoveredNodes")
      guard var manifest = $0["manifest"] as? [String: Any] else { throw OracleFailure(description: "Missing manifest") }
      manifest.removeValue(forKey: "discoveredNodeCount")
      $0["manifest"] = manifest
    }
    let parsed = try parseBackup(data: data)
    try suite.expect(parsed.discoveredNodes.isEmpty, "Legacy discovered array default")
    try suite.expect(parsed.manifest.discoveredNodeCount == 0, "Legacy manifest default")
  }
  try suite.test("legacy-message-defaults") {
    let data = try modified(encoded) {
      var rows = try array($0, "messages")
      for key in ["sortDate", "failureSeen", "regionScopeMatches"] {
        rows[0].removeValue(forKey: key)
      }
      $0["messages"] = rows
    }
    let parsed = try parseBackup(data: data)
    try suite.expect(parsed.messages[0].sortDate == parsed.messages[0].createdAt, "Legacy sortDate")
    try suite.expect(!parsed.messages[0].failureSeen, "Legacy failureSeen")
    try suite.expect(parsed.messages[0].regionScopeMatches.isEmpty, "Do not invent scope matches")
  }
  for (name, region) in [("legacy-channel-specific", "US"), ("legacy-channel-inherit", "")] {
    try suite.test(name) {
      let data = try modified(encoded) {
        var rows = try array($0, "channels")
        for key in ["floodScopeModeRawValue", "unreadMentionCount", "notificationLevel", "isFavorite"] {
          rows[0].removeValue(forKey: key)
        }
        if region.isEmpty { rows[0].removeValue(forKey: "regionScope") }
        $0["channels"] = rows
      }
      let channel = try parseBackup(data: data).channels[0]
      try suite.expect(channel.floodScopeModeRawValue == (region.isEmpty ? "inherit" : "specific"), "Legacy flood scope")
      try suite.expect(channel.unreadMentionCount == 0, "Legacy mention count")
      try suite.expect(channel.notificationLevel == .all, "Legacy notification level")
      try suite.expect(!channel.isFavorite, "Legacy favorite")
    }
  }
  try suite.test("legacy-room-default") {
    let data = try modified(encoded) {
      var rows = try array($0, "roomMessages")
      rows[0].removeValue(forKey: "failureSeen")
      $0["roomMessages"] = rows
    }
    try suite.expect(try parseBackup(data: data).roomMessages[0].failureSeen == false, "Legacy room failureSeen")
  }
  try suite.test("invalid-compressed-input") {
    try suite.expectBackupError({ try parseBackup(data: Data([0x00, 0xFF, 0xAB, 0xCD])) }) {
      if case .invalidFile = $0 { return true }; return false
    }
  }
  try suite.test("truncated-compression") {
    try suite.expectBackupError({ try parseBackup(data: Data(compressed.prefix(max(compressed.count / 2, 4)))) }) {
      if case .invalidFile = $0 { return true }; return false
    }
  }
  try suite.test("compressed-size-cap") {
    try suite.expect(maxBackupCompressedBytes == 50 * 1_048_576, "50MiB source cap")
    try suite.expectBackupError({ try parseBackup(data: Data(count: maxBackupCompressedBytes + 1)) }) {
      if case let .fileTooLarge(actual, max) = $0 { return actual == 52_428_801 && max == 52_428_800 }
      return false
    }
  }
  try suite.test("expanded-size-cap") {
    try suite.expect(maxBackupUncompressedBytes == 512 * 1_048_576, "512MiB source cap")
    let bomb = try Data(count: 2 * 1_048_576).zlibCompressed()
    try suite.expectBackupError({ try parseBackup(data: bomb, maxUncompressedBytes: 1_048_576) }) {
      if case let .decompressedTooLarge(max) = $0 { return max == 1_048_576 }; return false
    }
  }
  try suite.test("future-version-rejected") {
    let data = try modified(encoded) { $0["version"] = 999 }
    try suite.expectBackupError({ try parseBackup(data: data) }) {
      if case let .unsupportedVersion(found, max) = $0 { return found == 999 && max == 2 }; return false
    }
  }
  try suite.test("manifest-mismatch") {
    let data = try modified(encoded) { $0["manifest"] = ["deviceCount": 99] }
    try suite.expectBackupError({ try parseBackup(data: data) }) {
      if case .invalidFile = $0 { return true }; return false
    }
    let mismatch = try modified(encoded) {
      guard var manifest = $0["manifest"] as? [String: Any] else { throw OracleFailure(description: "Missing manifest") }
      manifest["deviceCount"] = 99
      $0["manifest"] = manifest
    }
    try suite.expectBackupError({ try parseBackup(data: mismatch) }) {
      if case .corruptedManifest = $0 { return true }; return false
    }
  }
  let invalidInputs: [(String, (inout [String: Any]) -> Void)] = [
    ("required-field-missing", { (row: inout [String: Any]) in _ = row.removeValue(forKey: "text") }),
    ("invalid-base64", { (row: inout [String: Any]) in row["pathNodes"] = "not base64!" }),
    ("invalid-uuid", { (row: inout [String: Any]) in row["id"] = "not a uuid" }),
    ("unknown-raw-enum", { (row: inout [String: Any]) in row["status"] = 999 })
  ]
  for (name, transform) in invalidInputs {
    try suite.test(name) {
      let data = try modified(encoded) {
        var rows = try array($0, "messages")
        transform(&rows[0])
        $0["messages"] = rows
      }
      try suite.expectBackupError({ try parseBackup(data: data) }) {
        if case .invalidFile = $0 { return true }; return false
      }
    }
  }
  try suite.test("optional-null") {
    let data = try modified(encoded) {
      var rows = try array($0, "messages")
      rows[0]["linkPreviewURL"] = NSNull()
      $0["messages"] = rows
    }
    try suite.expect(try parseBackup(data: data).messages[0].linkPreviewURL == nil, "Null optional")
  }
  try suite.test("source-version-zero") {
    let data = try modified(encoded) { $0["version"] = 0 }
    try suite.expect(try parseBackup(data: data).version == 0, "Pinned source accepts version zero; do not silently fix it")
  }

  let crypto = try executeCryptoTests(suite: suite)
  try encoded.write(to: output.appendingPathComponent("reference-envelope.json"))
  try compressed.write(to: output.appendingPathComponent("reference-envelope.meshcoreone"))
  try json(crypto).write(to: output.appendingPathComponent("channel-crypto-oracle.json"))
  let report: [String: Any] = [
    "source_sha": sourceSHA,
    "tests": suite.tests.map { ["name": $0.name, "assertions": $0.assertions] },
    "discovered": suite.tests.count, "passed": suite.tests.count, "failed": 0, "skipped": 0,
    "assertions": suite.tests.reduce(0) { $0 + $1.assertions }
  ]
  try json(report).write(to: output.appendingPathComponent("test-results.json"))
  print("Executed \(suite.tests.count) real reference-codec cases; \(suite.tests.reduce(0) { $0 + $1.assertions }) assertions.")
}

@main
@MainActor
struct CodecOracle {
  static func main() throws {
    let arguments = CommandLine.arguments
    guard arguments.count >= 3 else {
      throw OracleFailure(description: "Usage: codec-oracle test OUTPUT | decode COMPRESSED_INPUT OUTPUT_JSON")
    }
    if arguments[1] == "test", arguments.count == 3 {
      try executeTests(output: URL(fileURLWithPath: arguments[2], isDirectory: true))
    } else if arguments[1] == "decode", arguments.count == 4 {
      let input = URL(fileURLWithPath: arguments[2])
      let attributes = try FileManager.default.attributesOfItem(atPath: input.path)
      guard let size = attributes[.size] as? NSNumber else { throw OracleFailure(description: "Missing input size") }
      guard size.int64Value <= Int64(maxBackupCompressedBytes) else {
        throw AppBackupError.fileTooLarge(actualBytes: size.intValue, maxBytes: maxBackupCompressedBytes)
      }
      let envelope = try parseBackup(data: Data(contentsOf: input))
      try makeBackupJSONEncoder().encode(envelope).write(to: URL(fileURLWithPath: arguments[3]))
    } else {
      throw OracleFailure(description: "Unknown/incomplete codec-oracle command")
    }
  }
}

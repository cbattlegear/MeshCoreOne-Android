"""AndroidOnly: WP-203 Stage an isolated interop test against the explicit upstream v2 revision."""

import shutil
import subprocess
import sys
from pathlib import Path

SOURCE = "5e980a53e077c45a1270170612fdf5dae97792ff"
ROOT = Path(__file__).resolve().parents[3]
DOCC = '.package(url: "https://github.com/apple/swift-docc-plugin", from: "1.4.0")'
OLD_TARGET = '''      ],
      resources: [
        .process("MECP")
      ]
    )'''
NEW_TARGET = '''      ],
      path: "Interop739"
    )'''
ROOM_SEND_START = '''      let sendLease = try await plainTextSendGate?.acquire()
      defer {
        if let sendLease {
          await plainTextSendGate?.stopIssuing(sendLease)
        }
      }
      let sentInfo = try await sendRoomMessage(id: id, roomPublicKey: room.publicKey)'''
ROOM_SEND_START_FIXED = '''      let sendLease = try await plainTextSendGate?.acquire()
      do {
        let sentInfo = try await sendRoomMessage(id: id, roomPublicKey: room.publicKey)'''
ROOM_SEND_END = '''      try? await dataStore.updateRoomActivity(room.id)
    } catch {'''
ROOM_SEND_END_FIXED = '''        try? await dataStore.updateRoomActivity(room.id)
      } catch {
        if let sendLease {
          await plainTextSendGate?.stopIssuing(sendLease)
        }
        throw error
      }
      if let sendLease {
        await plainTextSendGate?.stopIssuing(sendLease)
      }
    } catch {'''
SCROLL_CANCEL = '''    } onCancel: { [weak self] in
      Task { @MainActor in
        self?.finishScrollRenderWait(token: token)
      }
    }'''
SCROLL_CANCEL_FIXED = SCROLL_CANCEL.replace(
    "Task { @MainActor in", "Task { @MainActor [weak self] in"
)


def replace_once(path, old, new):
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise RuntimeError(f"Unexpected upstream source in {path}")
    path.write_text(text.replace(old, new), encoding="utf-8")


def disable_unrelated_send(path, start, end, signature_end):
    text = path.read_text(encoding="utf-8")
    if text.count(start) != 1 or text.count(end) != 1:
        raise RuntimeError(f"Unexpected upstream send method in {path}")
    begin = text.index(start)
    finish = text.index(end, begin)
    body = text.index(signature_end, begin, finish) + len(signature_end)
    if "defer { await" not in text[body:finish]:
        raise RuntimeError(f"Expected unsupported async defer in {path}")
    text = text[:body] + '\n    fatalError("Direct-message sending is unavailable in the backup oracle")' + text[finish:]
    path.write_text(text, encoding="utf-8")


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Expected the isolated upstream checkout path")
    upstream = Path(sys.argv[1]).resolve(strict=True)
    if upstream == ROOT or ROOT in upstream.parents:
        raise SystemExit("Upstream must be an isolated checkout outside the Android repository")
    commit = subprocess.check_output(["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True).strip()
    if commit != SOURCE:
        raise SystemExit(f"Unexpected upstream revision: {commit}")
    test_dir = upstream / "MC1Services" / "Interop739"
    test_dir.mkdir()
    for name in (
        "DeviceDTO+Testing.swift", "NodeStatusSnapshotDTO+Testing.swift", "AppBackupEnvelope+Testing.swift",
        "ContactDTO+Testing.swift", "ChannelDTO+Testing.swift", "RemoteNodeSessionDTO+Testing.swift",
    ):
        shutil.copy2(upstream / "MC1Services" / "Tests" / "MC1ServicesTests" / "Helpers" / name, test_dir / name)
    shutil.copy2(ROOT / "docs" / "android" / "evidence" / "WP-203" / "WP739InteropTests.swift", test_dir)
    replace_once(upstream / "MeshCore" / "Package.swift", DOCC, "")
    replace_once(upstream / "MC1Services" / "Package.swift", OLD_TARGET, NEW_TARGET)
    # Swift rejects await in defer. This unrelated service is compiled by the
    # target but is never used by the backup oracle; release the lease on both paths.
    room_service = upstream / "MC1Services" / "Sources" / "MC1Services" / "Services" / "RoomServerService.swift"
    replace_once(room_service, ROOM_SEND_START, ROOM_SEND_START_FIXED)
    replace_once(room_service, ROOM_SEND_END, ROOM_SEND_END_FIXED)
    # These send paths also contain unsupported async defers. Fail explicitly if
    # invoked instead of changing any backup or SwiftData implementation.
    services = upstream / "MC1Services" / "Sources" / "MC1Services" / "Services"
    dm = services / "MessageService+SendDM.swift"
    for start, end in (
        ("  func sendDirectMessage(", "\n  }\n\n  // MARK: - Send with Automatic Retry"),
        ("  func sendMessageWithRetry(", "\n  }\n\n  /// Saves the pending Message"),
        ("  internal func performQueuedDirectMessageSend(", "\n  }\n\n  /// Installs an acknowledgement owner"),
    ):
        disable_unrelated_send(dm, start, end, ") async throws -> MessageDTO {")
    queue = services / "ChatSendQueueService.swift"
    disable_unrelated_send(
        queue,
        "    dmQueue = SendQueue<DirectMessageEnvelope>(",
        "\n      },\n      onError: { _, _ in },",
        "      send: { envelope in",
    )
    replace_once(services / "ChatCoordinator+Mutations.swift", SCROLL_CANCEL, SCROLL_CANCEL_FIXED)
    print("Staged backup oracle: unrelated direct-message send paths fail closed if invoked.")


if __name__ == "__main__":
    main()

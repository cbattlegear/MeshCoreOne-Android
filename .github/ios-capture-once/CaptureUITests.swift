import XCTest

final class CaptureUITests: XCTestCase {
  @MainActor
  func testCapture() throws {
    continueAfterFailure = false
    let app = XCUIApplication(bundleIdentifier: "io.pocketmeshtest.app")
    app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    app.launch()

    try expect(app.buttons["Get Started"], "welcome")
    capture("00-welcome")
    app.buttons["Get Started"].tap()
    try expect(app.buttons["Continue"], "optional permissions")
    capture("01-permissions")
    app.buttons["Continue"].tap()

    try expect(app.buttons["Connect Simulator"], "pairing")
    capture("02-pairing")
    if tap(app.buttons["Connect via WiFi"], within: 2) {
      if app.navigationBars["Connect via WiFi"].waitForExistence(timeout: 3) {
        capture("03-wifi-connection")
      }
      if !tap(app.buttons["Cancel"], within: 1) && !tap(app.buttons["Close"], within: 1) {
        app.swipeDown()
      }
    }

    try expect(app.buttons["Connect Simulator"], "return to pairing")
    app.buttons["Connect Simulator"].tap()
    try expect(app.buttons["Skip for now"], "simulator connection and region selection", timeout: 55)
    capture("04-region")
    app.buttons["Skip for now"].tap()
    try expect(tab(app, "Chats"), "main tabs", timeout: 30)

    capture("05-chats")
    if openFixture(app, ["Alice Chen", "Bob Martinez", "Hannah Lee", "Dad"]) {
      capture("06-direct-chat")
      back(app)
    }
    if go(app, "Chats") && openFixture(app, ["Public", "Bay Area", "Trail Crew", "Mesh HQ"]) {
      capture("07-channel-chat")
    }

    if go(app, "Nodes") {
      capture("08-nodes")
      if openFixture(app, ["Alice Chen", "Bob Martinez", "Hannah Lee", "Dad"]) {
        capture("09-contact-detail")
        back(app)
      }
      if tapLabeled(app, "Rooms") && openFixture(app, ["Diana's Room"]) {
        capture("17-room-detail")
      }
    }

    if go(app, "Map") {
      capture("10-map", settle: 3)
    }
    if go(app, "Tools") {
      capture("11-tools")
      if tapLabeled(app, "Line of Sight") {
        capture("12-line-of-sight")
      }
    }
    if go(app, "Settings") {
      capture("13-settings")
      if tapLabeled(app, "Appearance") {
        capture("14-appearance")
        if tapLabeled(app, "Dark") && go(app, "Chats") {
          capture("18-chats-dark")
        }
      }
    }

    if go(app, "Chats") {
      if tapLabeled(app, "Disconnected") {
        capturePicker(app)
      } else {
        if tapLabeled(app, "Bluetooth connection status") {
          capture("15-radio-menu")
          if tapLabeled(app, "Change Device") || tapLabeled(app, "Connect Device") {
            capturePicker(app)
          }
        }
      }
    }
  }

  @MainActor
  private func capturePicker(_ app: XCUIApplication) {
    if app.navigationBars["Connect Device"].waitForExistence(timeout: 3)
      || app.staticTexts["No Paired Devices"].exists {
      capture("16-radio-picker")
    }
  }

  @MainActor
  private func expect(_ element: XCUIElement, _ state: String, timeout: TimeInterval = 10) throws {
    guard element.waitForExistence(timeout: timeout) else {
      capture("diagnostic-reached-before-failure")
      XCTFail("Did not reach \(state)")
      throw CaptureFailure.missingState
    }
  }

  private enum CaptureFailure: Error { case missingState }

  @MainActor
  private func tab(_ app: XCUIApplication, _ name: String) -> XCUIElement {
    app.tabBars.buttons.matching(NSPredicate(format: "label BEGINSWITH[c] %@", name)).firstMatch
  }

  @MainActor
  private func go(_ app: XCUIApplication, _ name: String) -> Bool {
    if app.tabBars.firstMatch.exists && tap(tab(app, name), within: 3) { return true }
    back(app)
    return tap(tab(app, name), within: 3)
  }

  @MainActor
  private func back(_ app: XCUIApplication) {
    let button = app.navigationBars.buttons.firstMatch
    if button.exists && button.frame.midX < app.frame.width / 3 {
      _ = tap(button, within: 1)
    }
  }

  @MainActor
  private func openFixture(_ app: XCUIApplication, _ names: [String]) -> Bool {
    for _ in 0..<3 {
      for name in names where tapLabeled(app, name, within: 0.6) { return true }
      let scrollable = app.collectionViews.firstMatch.exists
        ? app.collectionViews.firstMatch : app.scrollViews.firstMatch
      guard scrollable.exists && scrollable.isHittable else { break }
      scrollable.swipeUp()
    }
    return false
  }

  @MainActor
  private func tapLabeled(_ app: XCUIApplication, _ label: String, within timeout: TimeInterval = 2) -> Bool {
    if tap(app.staticTexts[label].firstMatch, within: timeout) { return true }
    return tap(app.buttons.matching(NSPredicate(format: "label BEGINSWITH[c] %@", label)).firstMatch,
               within: timeout)
  }

  @MainActor
  private func tap(_ element: XCUIElement, within timeout: TimeInterval) -> Bool {
    guard element.waitForExistence(timeout: timeout), element.isHittable, element.isEnabled else { return false }
    element.tap()
    return true
  }

  @MainActor
  private func capture(_ name: String, settle: TimeInterval = 1) {
    RunLoop.current.run(until: Date().addingTimeInterval(settle))
    let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
    attachment.name = name
    attachment.lifetime = .keepAlways
    add(attachment)
  }
}

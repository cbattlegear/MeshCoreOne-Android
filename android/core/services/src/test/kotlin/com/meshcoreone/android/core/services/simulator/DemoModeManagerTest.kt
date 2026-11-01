// PortedFrom: MC1Tests/Utilities/DemoModeManagerTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.simulator

import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Each Swift case builds `UserDefaults(suiteName: "test.<UUID>")`; each case here gets a fresh
 * [InMemoryDemoModeDefaults], read back through the same `bool(forKey:)` call with the literal key strings.
 * `shared` needs its standard store installed first (WP-303 does this at process start).
 */
class DemoModeManagerTest {
    @TestFactory
    fun demoModeManagerTests(): List<DynamicTest> = listOf(
        // MARK: - Singleton Pattern Tests
        case("shared returns the same instance") {
            SimulatorTestSupport.installStandardDefaults()
            val instance1 = DemoModeManager.shared
            val instance2 = DemoModeManager.shared
            assertSame(instance1, instance2)
        },

        // MARK: - Default Values Tests
        case("properties default to false for new instances") {
            val manager = DemoModeManager(InMemoryDemoModeDefaults())
            assertEquals(false, manager.isUnlocked)
            assertEquals(false, manager.isEnabled)
        },

        // MARK: - unlock() Method Tests
        case("unlock sets both isUnlocked and isEnabled to true") {
            val manager = DemoModeManager(InMemoryDemoModeDefaults())

            assertEquals(false, manager.isUnlocked)
            assertEquals(false, manager.isEnabled)

            manager.unlock()

            assertEquals(true, manager.isUnlocked)
            assertEquals(true, manager.isEnabled)
        },

        // MARK: - UserDefaults Persistence Tests
        case("UserDefaults persistence works for isUnlocked") {
            val defaults = InMemoryDemoModeDefaults()
            val manager = DemoModeManager(defaults)

            manager.setUnlocked(true)

            assertEquals(true, defaults.bool(forKey = "isDemoModeUnlocked"))
        },
        case("UserDefaults persistence works for isEnabled") {
            val defaults = InMemoryDemoModeDefaults()
            val manager = DemoModeManager(defaults)

            manager.setEnabled(true)

            assertEquals(true, defaults.bool(forKey = "isDemoModeEnabled"))
        },
        case("unlock persists both values to UserDefaults") {
            val defaults = InMemoryDemoModeDefaults()
            val manager = DemoModeManager(defaults)

            manager.unlock()

            assertEquals(true, defaults.bool(forKey = "isDemoModeUnlocked"))
            assertEquals(true, defaults.bool(forKey = "isDemoModeEnabled"))
        },
        case("values persist and can be read back") {
            val defaults = InMemoryDemoModeDefaults()
            defaults.set(true, forKey = "isDemoModeUnlocked")
            defaults.set(true, forKey = "isDemoModeEnabled")

            val manager = DemoModeManager(defaults)
            assertEquals(true, manager.isUnlocked)
            assertEquals(true, manager.isEnabled)
        },
    )

    private fun case(name: String, body: suspend () -> Unit): DynamicTest =
        SimulatorTestSupport.caseNamed("DemoModeManagerTests", name) { SimulatorTestSupport.blocking { body() } }
}

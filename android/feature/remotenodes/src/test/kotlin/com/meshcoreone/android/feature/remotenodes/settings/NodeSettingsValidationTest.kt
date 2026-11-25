// PortedFrom: MC1Tests/ViewModels/NodeSettingsViewModelValidationTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.settings

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.support.OriginalCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Swift suite `NodeSettingsIdentityValidationTests` ("NodeSettingsViewModel identity validation"). */
class NodeSettingsValidationTest {
    private fun validate(name: String? = null, latitude: Double? = null, longitude: Double? = null) =
        NodeSettingsValidation.validateIdentityFields(name, latitude, longitude)

    @Test @OriginalCase("NodeSettingsIdentityValidationTests::in range coordinates produce no errors()")
    fun `in range coordinates produce no errors`() {
        val errors = validate("Repeater One", 37.7749, -122.4194)
        assertNull(errors.name)
        assertNull(errors.latitude)
        assertNull(errors.longitude)
        assertFalse(errors.hasErrors)
    }

    @Test @OriginalCase("NodeSettingsIdentityValidationTests::zero coordinates are valid()")
    fun `zero coordinates are valid`() {
        assertFalse(validate(null, 0.0, 0.0).hasErrors)
    }

    @Test @OriginalCase("NodeSettingsIdentityValidationTests::nil fields are skipped not flagged()")
    fun `null fields are skipped not flagged`() {
        assertFalse(validate(null, null, null).hasErrors)
    }

    @Test @OriginalCase("NodeSettingsIdentityValidationTests::latitude boundaries are inclusive(lat : Double)")
    fun `latitude boundaries are inclusive`() {
        listOf(-90.0, 90.0).forEach { lat -> assertNull(validate(latitude = lat, longitude = 0.0).latitude, "Latitude $lat") }
    }

    @Test @OriginalCase("NodeSettingsIdentityValidationTests::longitude boundaries are inclusive(lon : Double)")
    fun `longitude boundaries are inclusive`() {
        listOf(-180.0, 180.0).forEach { lon -> assertNull(validate(latitude = 0.0, longitude = lon).longitude, "Longitude $lon") }
    }

    @Test @OriginalCase("NodeSettingsIdentityValidationTests::out of range latitude is rejected(lat : Double)")
    fun `out of range latitude is rejected`() {
        listOf(-90.0001, 90.0001, -91.0, 91.0, 322.2, -1000.0).forEach { lat ->
            val errors = validate(latitude = lat, longitude = 0.0)
            assertNotNull(errors.latitude, "Latitude $lat is out of range and must be flagged")
            assertNull(errors.longitude, "In-range longitude must stay valid")
        }
    }

    @Test @OriginalCase("NodeSettingsIdentityValidationTests::out of range longitude is rejected(lon : Double)")
    fun `out of range longitude is rejected`() {
        listOf(-180.0001, 180.0001, -181.0, 181.0, 5000.0, -5000.0).forEach { lon ->
            val errors = validate(latitude = 0.0, longitude = lon)
            assertNotNull(errors.longitude, "Longitude $lon is out of range and must be flagged")
            assertNull(errors.latitude, "In-range latitude must stay valid")
        }
    }

    @Test @OriginalCase("NodeSettingsIdentityValidationTests::ranges are not swapped()")
    fun `ranges are not swapped`() {
        val errors = validate(latitude = 150.0, longitude = 150.0)
        assertNotNull(errors.latitude, "150 exceeds the latitude range")
        assertNull(errors.longitude, "150 is within the longitude range")
    }

    @Test @OriginalCase("NodeSettingsIdentityValidationTests::non finite latitude is rejected(lat : Double)")
    fun `non finite latitude is rejected`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { lat ->
            assertNotNull(validate(latitude = lat, longitude = 0.0).latitude, "Non-finite latitude $lat")
        }
    }

    @Test @OriginalCase("NodeSettingsIdentityValidationTests::non finite longitude is rejected(lon : Double)")
    fun `non finite longitude is rejected`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { lon ->
            assertNotNull(validate(latitude = 0.0, longitude = lon).longitude, "Non-finite longitude $lon")
        }
    }

    @Test @OriginalCase("NodeSettingsIdentityValidationTests::name at byte cap is valid()")
    fun `name at byte cap is valid`() {
        assertNull(validate(name = "a".repeat(ProtocolLimits.MAX_USABLE_NAME_BYTES)).name)
    }

    @Test @OriginalCase("NodeSettingsIdentityValidationTests::name over byte cap is rejected()")
    fun `name over byte cap is rejected`() {
        val errors = validate(name = "a".repeat(ProtocolLimits.MAX_USABLE_NAME_BYTES + 1))
        assertEquals(
            RemoteNodesText.Resource(R.string.l10n_app_remotenodes_remotenodes_settings_namevalidation, listOf(ProtocolLimits.MAX_USABLE_NAME_BYTES)),
            errors.name,
        )
    }

    @Test @OriginalCase("NodeSettingsIdentityValidationTests::name byte cap counts UTF 8 bytes not characters()")
    fun `name byte cap counts UTF-8 bytes not characters`() {
        // Eight 4-byte emoji are 8 characters but 32 UTF-8 bytes, over the 31-byte cap.
        assertNotNull(validate(name = "😀".repeat(8)).name, "Name length must be measured in UTF-8 bytes")
    }

    @Test @OriginalCase("NodeSettingsIdentityValidationTests::all three invalid reports all three()")
    fun `all three invalid reports all three`() {
        val errors = validate("a".repeat(ProtocolLimits.MAX_USABLE_NAME_BYTES + 1), 200.0, 400.0)
        assertNotNull(errors.name)
        assertNotNull(errors.latitude)
        assertNotNull(errors.longitude)
        assertTrue(errors.hasErrors)
    }

    @Test
    fun `behavior validation allows zero and the firmware ranges`() {
        assertFalse(NodeSettingsValidation.validateBehaviorFields(0, 0, 0).hasErrors)
        assertFalse(NodeSettingsValidation.validateBehaviorFields(60, 3, 64).hasErrors)
        assertFalse(NodeSettingsValidation.validateBehaviorFields(240, 168, null).hasErrors)
        val errors = NodeSettingsValidation.validateBehaviorFields(59, 169, 65)
        assertNotNull(errors.advertInterval)
        assertNotNull(errors.floodInterval)
        assertNotNull(errors.floodMaxHops)
        assertNotNull(NodeSettingsValidation.validateBehaviorFields(241, 2, -1).floodMaxHops)
    }
}

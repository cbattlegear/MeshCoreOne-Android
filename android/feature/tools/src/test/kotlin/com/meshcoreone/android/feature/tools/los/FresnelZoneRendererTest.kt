// PortedFrom: MC1Tests/Views/FresnelZoneRendererTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.los

import com.meshcoreone.android.core.model.Coordinate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class FresnelZoneRendererTest {
    private val origin = Coordinate(0.0, 0.0)

    @Test @OriginalCase("FresnelZoneRendererTests::ProfileSample computes yTop and yBottom correctly()")
    fun `ProfileSample computes yTop and yBottom correctly`() {
        val sample = ProfileSample(x = 3000.0, yTerrain = 100.0, yLOS = 150.0, fresnelRadius = 20.0)
        assertEquals(170.0, sample.yTop)
        assertEquals(130.0, sample.yBottom)
    }

    @Test @OriginalCase("FresnelZoneRendererTests::isObstructed returns true when terrain above yBottom()")
    fun `isObstructed returns true when terrain above yBottom`() {
        assertFalse(ProfileSample(0.0, 100.0, 150.0, 20.0).isObstructed)
        assertTrue(ProfileSample(0.0, 140.0, 150.0, 20.0).isObstructed)
        // Outer bottom = 130; inner bottom = 138. These points distinguish the two zones.
        assertFalse(ProfileSample(0.0, 134.0, 150.0, 20.0).isObstructed)
        assertFalse(ProfileSample(0.0, 137.999, 150.0, 20.0).isObstructed)
        assertFalse(ProfileSample(0.0, 138.0, 150.0, 20.0).isObstructed)
        assertTrue(ProfileSample(0.0, 138.001, 150.0, 20.0).isObstructed)
    }

    @Test @OriginalCase("FresnelZoneRendererTests::yVisibleBottom clamps to prevent path inversion()")
    fun `yVisibleBottom clamps to prevent path inversion`() {
        assertEquals(130.0, ProfileSample(0.0, 100.0, 150.0, 20.0).yVisibleBottom)
        assertEquals(140.0, ProfileSample(0.0, 140.0, 150.0, 20.0).yVisibleBottom)
        assertEquals(170.0, ProfileSample(0.0, 180.0, 150.0, 20.0).yVisibleBottom)
    }

    @Test @OriginalCase("FresnelZoneRendererTests::ProfileSample computes inner 60% zone bounds()")
    fun `ProfileSample computes inner 60 percent zone bounds`() {
        val sample = ProfileSample(x = 3000.0, yTerrain = 100.0, yLOS = 150.0, fresnelRadius = 20.0)
        assertEquals(162.0, sample.yTop60)
        assertEquals(138.0, sample.yBottom60)
    }

    @Test @OriginalCase("FresnelZoneRendererTests::yVisibleBottom60 clamps terrain to inner zone()")
    fun `yVisibleBottom60 clamps terrain to inner zone`() {
        assertEquals(138.0, ProfileSample(0.0, 100.0, 150.0, 20.0).yVisibleBottom60)
        assertEquals(145.0, ProfileSample(0.0, 145.0, 150.0, 20.0).yVisibleBottom60)
        assertEquals(162.0, ProfileSample(0.0, 170.0, 150.0, 20.0).yVisibleBottom60)
    }

    @Test @OriginalCase("FresnelZoneRendererTests::losHeight interpolates linearly between endpoints()")
    fun `losHeight interpolates linearly between endpoints`() {
        assertEquals(100.0, FresnelZoneRenderer.losHeight(0.0, 10000.0, 100.0, 200.0))
        assertEquals(150.0, FresnelZoneRenderer.losHeight(5000.0, 10000.0, 100.0, 200.0))
        assertEquals(200.0, FresnelZoneRenderer.losHeight(10000.0, 10000.0, 100.0, 200.0))
        // Oracle los.zero / los.third: zero-length path returns heightA; operand order kept.
        assertEquals(100.0, FresnelZoneRenderer.losHeight(5000.0, 0.0, 100.0, 200.0))
        assertBits("40553962fc962fca", FresnelZoneRenderer.losHeight(1.0, 3.0, 123.4, 7.89), "los.third")
    }

    @Test @OriginalCase("FresnelZoneRendererTests::buildProfileSamples creates samples with correct geometry()")
    fun `buildProfileSamples creates samples with correct geometry`() {
        val profile = listOf(0.0, 3000.0, 6000.0).map { ElevationSample(origin, 100.0, it) }
        val samples = FresnelZoneRenderer.buildProfileSamples(profile, 50.0, 50.0, 910.0, 1.33)

        assertEquals(3, samples.size)
        assertEquals(0.0, samples[0].x)
        assertEquals(100.0, samples[0].yTerrain)
        assertEquals(150.0, samples[0].yLOS)
        assertEquals(0.0, samples[0].fresnelRadius)

        assertEquals(3000.0, samples[1].x)
        assertTrue(samples[1].yTerrain > 100.5)
        assertTrue(samples[1].yTerrain < 100.6)
        assertEquals(150.0, samples[1].yLOS)
        assertTrue(samples[1].fresnelRadius > 20)
        // Oracle flat3.1: exact Swift Double bits for bulge-adjusted terrain and radius.
        assertBits("405921fd14b66669", samples[1].yTerrain, "flat3.1.yTerrain")
        assertBits("40363ad343e28203", samples[1].fresnelRadius, "flat3.1.fresnelRadius")

        assertEquals(6000.0, samples[2].x)
        assertEquals(100.0, samples[2].yTerrain)
        assertEquals(150.0, samples[2].yLOS)
        assertEquals(0.0, samples[2].fresnelRadius)
    }

    @Test @OriginalCase("FresnelZoneRendererTests::buildProfileSamples handles segment slice correctly (R→B case)()")
    fun `buildProfileSamples handles segment slice correctly R to B case`() {
        val fullProfile = listOf(0.0, 3000.0, 6000.0, 9000.0, 12000.0).map { ElevationSample(origin, 100.0, it) }
        val segmentRB = fullProfile.subList(2, fullProfile.size).toList()

        val samples = FresnelZoneRenderer.buildProfileSamples(segmentRB, 50.0, 50.0, 910.0, 1.33)

        assertEquals(3, samples.size)
        assertEquals(6000.0, samples[0].x)
        assertEquals(0.0, samples[0].fresnelRadius, "Fresnel radius at segment start (R) must be 0")
        assertEquals(9000.0, samples[1].x)
        assertTrue(samples[1].fresnelRadius > 20, "Fresnel radius at segment midpoint should be maximum")
        assertBits("40363ad343e28203", samples[1].fresnelRadius, "slice.1.fresnelRadius")
        assertBits("405921fd14b66669", samples[1].yTerrain, "slice.1.yTerrain")
        assertEquals(12000.0, samples[2].x)
        assertEquals(0.0, samples[2].fresnelRadius, "Fresnel radius at segment end (B) must be 0")
    }

    /** Android-only: the canvas preview profile (k = 4/3, unequal antennas) matches Swift bit for bit. */
    @Test
    fun `hill profile samples match the Swift oracle bit for bit`() {
        val profile = HillOracle.elevations.mapIndexed { i, hex ->
            ElevationSample(Coordinate(37.7749 + i * 0.001, -122.4194), bits(hex), i * 500.0)
        }
        val samples = FresnelZoneRenderer.buildProfileSamples(profile, 10.0, 15.0, 906.0, 4.0 / 3.0)
        samples.forEachIndexed { i, sample ->
            assertBits(HillOracle.yTerrain[i], sample.yTerrain, "hill.$i.yTerrain")
            assertBits(HillOracle.yLOS[i], sample.yLOS, "hill.$i.yLOS")
            assertBits(HillOracle.fresnelRadius[i], sample.fresnelRadius, "hill.$i.fresnelRadius")
            assertBits(HillOracle.yVisibleBottom[i], sample.yVisibleBottom, "hill.$i.yVisibleBottom")
            assertBits(HillOracle.yVisibleBottom60[i], sample.yVisibleBottom60, "hill.$i.yVisibleBottom60")
            assertEquals(i in 1..19, sample.isObstructed, "hill.$i.isObstructed")
        }
    }

    /** Android-only: mirrored RFCalculator spot values, including the non-positive guards. */
    @Test
    fun `mirrored Fresnel and earth bulge math matches the Swift oracle`() {
        assertBits("4036475fbb0362ee", LosFresnelMath.fresnelRadius(906.0, 3000.0, 3000.0))
        assertBits("3fe25ded951fef32", LosFresnelMath.fresnelRadius(910.0, 1.0, 59999.0))
        assertBits("404ec847c978a0bc", LosFresnelMath.fresnelRadius(868.0, 12345.678, 98765.4321))
        assertEquals(0.0, LosFresnelMath.fresnelRadius(0.0, 1.0, 1.0))
        assertEquals(0.0, LosFresnelMath.fresnelRadius(906.0, 0.0, 1.0))
        assertBits("3fe0fe8a5b333450", LosFresnelMath.earthBulge(3000.0, 3000.0, 1.33))
        assertBits("3ff78b4fae599b25", LosFresnelMath.earthBulge(5000.0, 5000.0, 4.0 / 3.0))
        assertBits("4057ec61d90bd7e5", LosFresnelMath.earthBulge(12345.678, 98765.4321, 1.0))
        assertEquals(0.0, LosFresnelMath.earthBulge(1.0, 1.0, 0.0))
        assertEquals(0.0, LosFresnelMath.earthBulge(1.0, -1.0, 1.0))
        assertTrue(FresnelZoneRenderer.buildProfileSamples(emptyList(), 1.0, 1.0, 906.0, 1.0).isEmpty())
    }
}

/** Swift oracle output for the TerrainProfileCanvas #Preview hill (docs/android/evidence/WP-315). */
internal object HillOracle {
    val elevations = listOf(
        "4059000000000000", "405eddc5575e5073", "40624b48152b095a", "4065032786ad2d0a", "4067855e849cc2f3",
        "4069c21cd00e8d0a", "406bab48152b095a", "406d34d4d121d808", "406e55123f4a09cd", "406f04e76ed40fce",
        "406f400000000000", "406f04e76ed40fcd", "406e55123f4a09ce", "406d34d4d121d808", "406bab48152b095a",
        "4069c21cd00e8d0a", "4067855e849cc2f4", "4065032786ad2d0a", "40624b48152b095b", "405eddc5575e5074",
        "4059000000000001",
    )
    val yTerrain = listOf(
        "4059000000000000", "405eefaa190bc7c0", "40625c3bbf2db045", "40651b2b6230eed6", "4067a38179f6cf3f",
        "4069e56dc7941373", "406bd2d5f731397d", "406d5fae85fde184", "406e8246af511c3f", "406f3385825b5ad3",
        "406f6f169f5cb336", "406f3385825b5ad2", "406e8246af511c40", "406d5fae85fde184", "406bd2d5f731397d",
        "4069e56dc7941373", "4067a38179f6cf40", "40651b2b6230eed6", "40625c3bbf2db046", "405eefaa190bc7c1",
        "4059000000000001",
    )
    val yLOS = listOf(
        "405b800000000000", "405b900000000000", "405ba00000000000", "405bb00000000000", "405bc00000000000",
        "405bd00000000000", "405be00000000000", "405bf00000000000", "405c000000000000", "405c100000000000",
        "405c200000000000", "405c300000000001", "405c400000000001", "405c500000000001", "405c600000000001",
        "405c700000000001", "405c800000000001", "405c900000000001", "405ca00000000001", "405cb00000000001",
        "405cc00000000001",
    )
    val fresnelRadius = listOf(
        "0000000000000000", "402912ef836390ca", "403141d080a68ddc", "40348a403f55b2a1", "4037026b563367d0",
        "4038e890e05fddb7", "403a5c52b42157d9", "403b6fe09903cab0", "403c2e42c458b7e9", "403c9e1dd1dfc71e",
        "403cc3062bc041c4", "403c9e1dd1dfc71e", "403c2e42c458b7e9", "403b6fe09903caaf", "403a5c52b42157d9",
        "4038e890e05fddb7", "4037026b563367d0", "40348a403f55b2a1", "403141d080a68ddc", "402912ef836390ca",
        "0000000000000000",
    )
    val yVisibleBottom = listOf(
        "405b800000000000", "405eb25df06c7219", "405ff0742029a377", "4060694807eab654", "4060c04d6ac66cfa",
        "406105121c0bfbb7", "40613b8a56842afb", "406165fc13207956", "406185c8588b16fd", "40619bc3ba3bf8e4",
        "4061a860c5780838", "4061abc3ba3bf8e4", "4061a5c8588b16fe", "406195fc13207956", "40617b8a56842afc",
        "406155121c0bfbb7", "4061204d6ac66cfa", "4060d94807eab655", "4060783a1014d1bc", "405eefaa190bc7c1",
        "405cc00000000001",
    )
    val yVisibleBottom60 = listOf(
        "405b800000000000", "405d716b90411142", "405e36ac134c2ee1", "405ec4bcd64cdacb", "405f339019bae92c",
        "405f8c7c21a7fadb", "405fd43f9b050060", "406006ca71e048cd", "40601d11ceb9da98", "40602d756fbd9555",
        "4060383a1014d1bc", "40603d756fbd9556", "40603d11ceb9da98", "406036ca71e048ce", "40602a1fcd828031",
        "4060163e10d3fd6e", "405ff39019bae92d", "405fa4bcd64cdacc", "405f36ac134c2ee2", "405e916b90411143",
        "405cc00000000001",
    )
}

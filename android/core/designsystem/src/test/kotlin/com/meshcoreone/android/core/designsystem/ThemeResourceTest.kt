// PortedFrom: MC1Tests/ThemeLocalizedNameTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/MessageTextThemeTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.designsystem

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import java.security.MessageDigest
import java.util.Locale
import kotlin.test.*
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ThemeResourceTest {
    private fun resources(locale: String = "en"): Resources {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocales(LocaleList(Locale.forLanguageTag(locale)))
        return context.createConfigurationContext(configuration).resources
    }

    @OriginalCase("ThemeLocalizedNameTests::every registry theme has a non-empty localized name()")
    @Test fun nonemptyLocalizedNames() {
        for (theme in ThemeRegistry.allThemes) assertTrue(theme.localizedName(resources()).isNotEmpty())
    }
    @OriginalCase("ThemeLocalizedNameTests::every registry theme resolves to copy distinct from its raw display-name key()")
    @Test fun namesNeverExposeKeys() {
        for (theme in ThemeRegistry.allThemes) assertNotEquals(theme.displayNameKey, theme.localizedName(resources()))
    }
    @OriginalCase("ThemeLocalizedNameTests::theme names are distinct across the registry()")
    @Test fun distinctLocalizedNames() {
        for (locale in listOf("en", "de", "es", "fr", "it", "ko", "nl", "pl", "pt-PT", "ru", "uk", "zh-Hans")) {
            assertEquals(11, ThemeRegistry.allThemes.map { it.localizedName(resources(locale)) }.toSet().size, locale)
        }
    }
    @OriginalCase("ThemeLocalizedNameTests::default and ember resolve to their expected English copy()")
    @Test fun sourceEnglishNames() {
        val r = resources()
        assertEquals("Default", ThemeRegistry.default.localizedName(r))
        assertEquals("Ember", assertNotNull(ThemeRegistry.theme("ember")).localizedName(r))
        assertEquals(r.getString(AppSettingsStrings.supportThemeDefault), ThemeRegistry.default.localizedName(r))
        assertEquals(r.getString(AppSettingsStrings.supportThemeEmber), ThemeRegistry.theme("ember")?.localizedName(r))
        assertEquals(listOf("Sakura", "Solarized", "Nord", "Catppuccin"),
            listOf("sakura", "solarized", "nord", "catppuccin").map { ThemeRegistry.theme(it)?.localizedName(r) })
        assertEquals("Gradient", assertNotNull(ThemeRegistry.theme("gradient")).localizedName(r))
        assertEquals("Verlauf", assertNotNull(ThemeRegistry.theme("gradient")).localizedName(resources("de")))
    }

    private fun sourceOracle(): JSONObject {
        val stream = assertNotNull(javaClass.classLoader?.getResourceAsStream("theme-source-inputs.json"))
        return JSONObject(stream.reader(Charsets.UTF_8).use { it.readText() }).also {
            assertEquals(sourcePin, it.getString("source_pin"))
            assertEquals("8918fdc604341e6996a68c88f6bb1c02b9c2f87e", it.getString("source_tree"))
        }
    }
    @Test fun all176NamedColorStatesMatchActualFrozenAssetComponentsAndBlobs() {
        val assets = sourceOracle().getJSONObject("assets")
        assertEquals(44, assets.length())
        var compared = 0
        for (name in assets.keys()) {
            val asset = assets.getJSONObject(name)
            val raw = asset.getString("raw").toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-1").run {
                update("blob ${raw.size}\u0000".toByteArray(Charsets.UTF_8))
                digest(raw).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            }
            assertEquals(asset.getString("blob_sha"), digest)
            val variants = mutableMapOf<Pair<Boolean, Boolean>, ThemeColor>()
            val colors = JSONObject(raw.toString(Charsets.UTF_8)).getJSONArray("colors")
            for (index in 0 until colors.length()) {
                val value = colors.getJSONObject(index)
                var dark = false
                var high = false
                val appearances = value.optJSONArray("appearances")
                if (appearances != null) for (n in 0 until appearances.length()) {
                    val appearance = appearances.getJSONObject(n)
                    when (appearance.getString("appearance")) {
                        "luminosity" -> dark = appearance.getString("value") == "dark"
                        "contrast" -> high = appearance.getString("value") == "high"
                        else -> fail("Unknown frozen appearance")
                    }
                }
                val color = value.getJSONObject("color")
                val components = color.getJSONObject("components")
                variants[dark to high] = ThemeColor(
                    components.getString("red").toDouble(), components.getString("green").toDouble(),
                    components.getString("blue").toDouble(), components.getString("alpha").toDouble(),
                    if (color.getString("color-space") == "display-p3") ThemeColorSpace.DISPLAY_P3 else ThemeColorSpace.SRGB,
                )
            }
            for (scheme in ColorScheme.entries) for (high in listOf(false, true)) {
                val dark = scheme == ColorScheme.DARK
                val expected = variants[dark to high] ?: variants[dark to false] ?: variants[false to high]
                    ?: assertNotNull(variants[false to false])
                assertEquals(expected, GeneratedThemeColors.get(name).resolve(scheme, high), "$name:$scheme:$high")
                compared++
            }
        }
        assertEquals(176, compared)
        assertFailsWith<ThemeResourceFailure> { GeneratedThemeColors.get("Theme/Future/Missing") }
    }
    @Test fun compiledRecoveryResourcesRetainAllTwelveExactSourceTranslations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val data = context.assets.open("theme-source-map.json").reader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        for (locale in data.getJSONObject("recovery").keys()) {
            val expected = data.getJSONObject("recovery").getJSONObject(locale).getString("text")
            assertEquals(expected, resources(if (locale == "pt") "pt-PT" else locale).getString(ThemeRecoveryStrings.themeReverted), locale)
        }
        assertEquals("Theme reverted to the default.", resources().getString(ThemeRecoveryStrings.themeReverted))
    }

    private fun colors(outgoing: ThemeColor = ThemeColor.WHITE, hashtag: ThemeColor = ThemeColor(0.0, 1.0, 1.0)) =
        ThemeTextColors(ThemeColor.BLACK, outgoing, hashtag) { ThemeRegistry.default.resolve(ColorScheme.LIGHT, false).identityColor(it) }
    @OriginalCase("MessageTextThemeTests::incoming hashtag color is baked: different hashtagColor yields different output()")
    @Test fun incomingHashtagIsActuallyBaked() {
        val runs = SnapshotList.of(ThemeTextRun(4, 9, ThemeTextRole.HASHTAG))
        val cyan = buildThemedText("see #news", false, runs, colors())
        val orange = buildThemedText("see #news", false, runs, colors(hashtag = ThemeColor(1.0, 0.5, 0.0)))
        assertNotEquals(cyan, orange)
        assertEquals(ThemeColor(0.0, 1.0, 1.0).toComposeColor(), cyan.spanStyles.last().item.color)
    }
    @OriginalCase("MessageTextThemeTests::outgoing text color is baked: different outgoingTextColor yields different output()")
    @Test fun outgoingTextIsActuallyBaked() {
        val runs = SnapshotList.of(ThemeTextRun(6, 11, ThemeTextRole.HASHTAG))
        val white = buildThemedText("hello #news", true, runs, colors())
        val pink = buildThemedText("hello #news", true, runs, colors(outgoing = ThemeColor(1.0, 0.5, 0.7)))
        assertNotEquals(white, pink)
        assertTrue(white.spanStyles.all { it.item.color == ThemeColor.WHITE.toComposeColor() })
    }
    @OriginalCase("MessageTextThemeTests::default theme's hashtag color is the one baked into the hashtag run()")
    @Test fun defaultSourceHashtagIsThePassedBakedColor() {
        val source = ThemeRegistry.default.hashtagColor.resolve(ColorScheme.LIGHT, false)
        val result = buildThemedText("tap #news", false, SnapshotList.of(ThemeTextRun(4, 9, ThemeTextRole.HASHTAG)), colors(hashtag = source))
        assertEquals(source.toComposeColor(), result.spanStyles.last().item.color)
    }
    @Test fun nativeColorQuantizationStillClearsOriginalIdentityAndMaterialFloors() {
        var identities = 0
        for (frame in effectiveFrames()) {
            for (name in themeNames) {
                val rendered = frame.identityColor(name).toComposeColor().toThemeColor()
                for (surface in listOf(frame.canvas, frame.incomingBubble)) {
                    assertTrue(WCAGContrast.contrastRatio(rendered, surface.toComposeColor().toThemeColor()) >= WCAGContrast.floor(frame.highContrast))
                }
                identities++
            }
            val role = MaterialRoles.from(frame)
            val material = role.toMaterialColorScheme(frame.colorScheme == ColorScheme.DARK)
            for ((text, background) in listOf(material.onPrimary to material.primary,
                material.onPrimaryContainer to material.primaryContainer, material.onSecondary to material.secondary,
                material.onTertiary to material.tertiary, material.onSurface to material.surface,
                material.onSurfaceVariant to material.surfaceVariant, material.onError to material.error)) {
                assertTrue(WCAGContrast.contrastRatio(text.toThemeColor(), background.toThemeColor()) >= 4.5, frame.theme.id.rawValue)
            }
        }
        assertEquals(15466, identities)
    }
    @Test fun classifiedRunBoundariesAndGlyphMeaningsAreNotSilent() {
        assertFailsWith<IllegalArgumentException> {
            buildThemedText("\uD83D\uDC69 #a", false, SnapshotList.of(ThemeTextRun(1, 2, ThemeTextRole.HASHTAG)), colors())
        }
        assertFailsWith<IllegalArgumentException> {
            buildThemedText("abc", false, SnapshotList.of(ThemeTextRun(2, 4, ThemeTextRole.HASHTAG)), colors())
        }
        for (symbol in MeshSymbol.entries) {
            assertEquals(24f, symbol.vector.viewportWidth)
            assertEquals(24f, symbol.vector.viewportHeight)
            assertTrue(symbol.vector.root.size > 0)
        }
        assertEquals(5, SignalColorRole.entries.map { resources().getString(it.labelResource) }.toSet().size)
        assertEquals(listOf(1.0, 0.75, 0.5, 0.25, 0.0), SignalColorRole.entries.map { it.barLevel })
    }
    @Test fun outgoingIdentityRunsUseOutgoingForegroundAndIncomingRunsRetainSourceIdentity() {
        val runs = SnapshotList.of(ThemeTextRun(0, 6, ThemeTextRole.IDENTITY, "Alice"))
        for (frame in effectiveFrames()) {
            val palette = frame.textColors()
            val outgoing = buildThemedText("@Alice", true, runs, palette)
            val incoming = buildThemedText("@Alice", false, runs, palette)
            assertEquals(0, outgoing.spanStyles.last().start)
            assertEquals(6, outgoing.spanStyles.last().end)
            assertEquals(palette.outgoing.toComposeColor(), outgoing.spanStyles.last().item.color)
            assertTrue(WCAGContrast.contrastRatio(outgoing.spanStyles.last().item.color.toThemeColor(),
                frame.accent.toComposeColor().toThemeColor()) >= 4.5, frame.theme.id.rawValue)
            assertEquals(frame.identityColor("Alice").toComposeColor(), incoming.spanStyles.last().item.color)
            val emoji = "\uD83D\uDC69\u200d\uD83D\uDCBB @Alice"
            val start = emoji.indexOf('@')
            val indexed = buildThemedText(emoji, true, SnapshotList.of(
                ThemeTextRun(start, emoji.length, ThemeTextRole.IDENTITY, "Alice"),
            ), palette)
            assertEquals(start, indexed.spanStyles.last().start)
            assertEquals(emoji.length, indexed.spanStyles.last().end)
            assertEquals(palette.outgoing.toComposeColor(), indexed.spanStyles.last().item.color)
        }
    }
}

// PortedFrom: MC1Tests/Services/ElevationServiceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.content

import android.content.res.Configuration
import android.os.LocaleList
import com.meshcoreone.android.core.services.content.ElevationServiceError
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class ElevationErrorPresentationTest {
    private val context = RuntimeEnvironment.getApplication().let { application ->
        application.createConfigurationContext(Configuration(application.resources.configuration).apply {
            setLocales(LocaleList(Locale.US))
        })
    }

    @Test
    fun `networkError has descriptive message`() {
        val message = ElevationServiceError.NetworkError("Connection failed").localizedDescription(context)

        assertTrue(message.contains("Network error"))
        assertTrue(message.contains("Connection failed"))
    }

    @Test
    fun `invalidResponse has descriptive message`() {
        assertEquals(
            "Invalid response from elevation API",
            ElevationServiceError.InvalidResponse.localizedDescription(context),
        )
    }

    @Test
    fun `apiError includes message`() {
        val message = ElevationServiceError.ApiError("Rate limit exceeded").localizedDescription(context)

        assertTrue(message.contains("API error"))
        assertTrue(message.contains("Rate limit exceeded"))
    }

    @Test
    fun `noData has descriptive message`() {
        assertEquals(
            "No elevation data returned",
            ElevationServiceError.NoData.localizedDescription(context),
        )
    }
}

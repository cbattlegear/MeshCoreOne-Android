// AndroidOnly: WP-305 Durable permission history and reconciliation with live platform facts.
package com.meshcoreone.android.app.container.onboarding

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.connectivity.permissions.ConnectivityPermission
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionKind as Kind
import com.meshcoreone.android.feature.onboarding.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class OnboardingPermissionPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val preferences get() = context.getSharedPreferences("platform_permission_requests", Context.MODE_PRIVATE)

    @Before fun clear() { check(preferences.edit().clear().commit()) }

    private fun port(facts: FakePermissionFacts, scan: Boolean = false, connectionOnly: Boolean = false) =
        AndroidOnboardingPermissionPort(facts, RequestedPermissions(context), scan, bluetoothConnectionOnly = connectionOnly)

    @Test fun denialAndObservedGrantSurviveReconstructionButLiveFactsWin() {
        val facts = FakePermissionFacts()
        val first = port(facts)
        assertEquals(PermissionStatus.NOT_DETERMINED, first.snapshot.value.location)
        first.onRequestFinished(Kind.LOCATION, emptyList())
        assertEquals(PermissionStatus.NOT_DETERMINED, port(facts).snapshot.value.location)
        first.onRequestFinished(Kind.LOCATION)
        assertEquals(PermissionStatus.DENIED, port(facts).snapshot.value.location)
        facts.location = true
        assertEquals(PermissionStatus.GRANTED, port(facts).snapshot.value.location)
        facts.location = false
        assertEquals(PermissionStatus.DENIED, port(facts).snapshot.value.location)

        val grantedFirst = FakePermissionFacts(granted = setOf(ConnectivityPermission.ACCESS_LOCAL_NETWORK), sdk = 37)
        assertEquals(PermissionStatus.GRANTED, port(grantedFirst).snapshot.value.localNetwork)
        grantedFirst.granted = emptySet()
        assertEquals(PermissionStatus.DENIED, port(grantedFirst).snapshot.value.localNetwork)
    }

    @Test fun bluetoothHistoryIsPerPermissionAndPerScope() {
        val facts = FakePermissionFacts(granted = setOf(ConnectivityPermission.BLUETOOTH_CONNECT))
        assertEquals(PermissionStatus.GRANTED, port(facts, connectionOnly = true).snapshot.value.bluetooth)
        val scan = port(facts, scan = true)
        assertEquals(PermissionStatus.NOT_DETERMINED, scan.snapshot.value.bluetooth)
        scan.onRequestFinished(Kind.BLUETOOTH)
        assertEquals(PermissionStatus.DENIED, port(facts, scan = true).snapshot.value.bluetooth)
        assertEquals(PermissionStatus.GRANTED, port(facts, connectionOnly = true).snapshot.value.bluetooth)
        facts.granted = setOf(ConnectivityPermission.BLUETOOTH_CONNECT, ConnectivityPermission.BLUETOOTH_SCAN)
        assertEquals(PermissionStatus.GRANTED, port(facts, scan = true).snapshot.value.bluetooth)
        facts.granted = setOf(ConnectivityPermission.BLUETOOTH_SCAN)
        assertEquals(PermissionStatus.DENIED, port(facts, connectionOnly = true).snapshot.value.bluetooth)
    }

    @Test fun notificationsRespectApiBoundaryAndLegacyPreferences() {
        check(preferences.edit().putBoolean("notifications", true).commit())
        val facts = FakePermissionFacts(sdk = 32, notificationsEnabled = false)
        assertEquals(PermissionStatus.DENIED, port(facts).snapshot.value.notifications)
        facts.sdk = 33
        assertEquals(PermissionStatus.NOT_DETERMINED, port(facts).snapshot.value.notifications)
        port(facts).onRequestFinished(Kind.NOTIFICATIONS)
        assertEquals(PermissionStatus.DENIED, port(facts).snapshot.value.notifications)
        facts.granted = setOf(ConnectivityPermission.POST_NOTIFICATIONS)
        facts.notificationsEnabled = true
        assertEquals(PermissionStatus.GRANTED, port(facts).snapshot.value.notifications)
        facts.notificationsEnabled = false
        assertEquals(PermissionStatus.DENIED, port(facts).snapshot.value.notifications)
        facts.granted = emptySet()
        assertEquals(PermissionStatus.DENIED, port(facts).snapshot.value.notifications)
    }

    @Test fun legacyLocationHistoryIsRetained() {
        check(preferences.edit().putBoolean("location", true).commit())
        assertEquals(PermissionStatus.DENIED, port(FakePermissionFacts()).snapshot.value.location)
    }

    @Test fun persistenceFailureIsNotReportedAsSuccess() {
        val failing = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by preferences.edit() {
                override fun commit() = false
            }
        }
        val p = AndroidOnboardingPermissionPort(FakePermissionFacts(), RequestedPermissions(failing), false)
        assertThrows(IllegalStateException::class.java) { p.onRequestFinished(Kind.LOCATION) }
        assertEquals(PermissionStatus.NOT_DETERMINED, p.snapshot.value.location)
    }
}

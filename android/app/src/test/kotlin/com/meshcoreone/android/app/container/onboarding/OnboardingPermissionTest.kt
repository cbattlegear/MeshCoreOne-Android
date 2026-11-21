// AndroidOnly: WP-303 Permission snapshot mapping and runtime request selection.
package com.meshcoreone.android.app.container.onboarding

import com.meshcoreone.android.core.connectivity.permissions.ConnectivityPermission
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionKind as Kind
import com.meshcoreone.android.feature.onboarding.PermissionStatus
import com.meshcoreone.android.feature.onboarding.PermissionCardStatus
import com.meshcoreone.android.feature.onboarding.permissionCardStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OnboardingPermissionTest {
    private fun port(facts: FakePermissionFacts, scan: Boolean = false) =
        AndroidOnboardingPermissionPort(facts, RequestedPermissions(), scan)

    @Test fun `nothing granted or asked is not determined, implicit grants are granted`() {
        val s = port(FakePermissionFacts(sdk = 30)).snapshot.value
        assertEquals(PermissionStatus.NOT_DETERMINED, s.location)
        assertEquals(PermissionStatus.GRANTED, s.notifications)   // below API 33
        assertEquals(PermissionStatus.GRANTED, s.bluetooth)       // below API 31
        assertEquals(PermissionStatus.GRANTED, s.localNetwork)    // below API 37
    }

    @Test fun `api 34 needs notifications and bluetooth connect, not local network`() {
        val s = port(FakePermissionFacts(sdk = 34)).snapshot.value
        assertEquals(PermissionStatus.NOT_DETERMINED, s.notifications)
        assertEquals(PermissionStatus.NOT_DETERMINED, s.bluetooth)
        assertEquals(PermissionStatus.GRANTED, s.localNetwork)
        val s37 = port(FakePermissionFacts(sdk = 37)).snapshot.value
        assertEquals(PermissionStatus.NOT_DETERMINED, s37.localNetwork)
    }

    @Test fun `granted permissions map to granted and a finished request that stays missing becomes denied`() {
        val facts = FakePermissionFacts(granted = setOf(ConnectivityPermission.BLUETOOTH_CONNECT), location = true)
        val p = port(facts)
        assertEquals(PermissionStatus.GRANTED, p.snapshot.value.bluetooth)
        assertEquals(PermissionStatus.GRANTED, p.snapshot.value.location)
        p.onRequestFinished(Kind.NOTIFICATIONS)
        assertEquals(PermissionStatus.DENIED, p.snapshot.value.notifications)
        assertEquals(PermissionStatus.NOT_DETERMINED, port(facts).snapshot.value.notifications)
    }

    @Test fun `refresh republishes platform changes and the adapter flag`() {
        val facts = FakePermissionFacts()
        val p = port(facts)
        facts.location = true
        facts.bluetoothEnabled = false
        p.refresh()
        assertEquals(PermissionStatus.GRANTED, p.snapshot.value.location)
        assertFalse(p.snapshot.value.bluetoothAdapterEnabled)
    }

    @Test fun `disabled notifications offer settings and refresh after recovery`() {
        for (sdk in listOf(32, 33, 37)) {
            val facts = FakePermissionFacts(
                sdk = sdk,
                granted = setOf(ConnectivityPermission.POST_NOTIFICATIONS),
                notificationsEnabled = false,
            )
            val p = port(facts)
            assertEquals(PermissionStatus.DENIED, p.snapshot.value.notifications)
            assertEquals(PermissionCardStatus.DENIED, permissionCardStatus(p.snapshot.value.notifications))
            facts.notificationsEnabled = true
            p.refresh()
            assertEquals(PermissionStatus.GRANTED, p.snapshot.value.notifications)
        }
    }

    @Test fun `unrequested notification permission remains requestable when notifications are disabled`() {
        val p = port(FakePermissionFacts(sdk = 33, notificationsEnabled = false))
        assertEquals(PermissionStatus.NOT_DETERMINED, p.snapshot.value.notifications)
        assertEquals(PermissionCardStatus.REQUESTABLE, permissionCardStatus(p.snapshot.value.notifications))
        p.onRequestFinished(Kind.NOTIFICATIONS)
        assertEquals(PermissionStatus.DENIED, p.snapshot.value.notifications)
    }

    @Test fun `scan fallback asks for scan and connect, companion mode only connect`() {
        assertEquals(listOf("android.permission.BLUETOOTH_CONNECT"), OnboardingPermissionMapping.permissionsFor(Kind.BLUETOOTH, 34, false))
        assertEquals(
            listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN"),
            OnboardingPermissionMapping.permissionsFor(Kind.BLUETOOTH, 34, true),
        )
        assertEquals(PermissionStatus.NOT_DETERMINED, port(FakePermissionFacts(), scan = true).snapshot.value.bluetooth)
    }

    @Test fun `request names follow the api level and location is coarse only`() {
        assertEquals(emptyList<String>(), OnboardingPermissionMapping.permissionsFor(Kind.NOTIFICATIONS, 32, false))
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS"), OnboardingPermissionMapping.permissionsFor(Kind.NOTIFICATIONS, 33, false))
        assertEquals(emptyList<String>(), OnboardingPermissionMapping.permissionsFor(Kind.LOCAL_NETWORK, 36, false))
        assertEquals(listOf("android.permission.ACCESS_LOCAL_NETWORK"), OnboardingPermissionMapping.permissionsFor(Kind.LOCAL_NETWORK, 37, false))
        assertEquals(listOf("android.permission.ACCESS_COARSE_LOCATION"), OnboardingPermissionMapping.permissionsFor(Kind.LOCATION, 34, false))
    }
}

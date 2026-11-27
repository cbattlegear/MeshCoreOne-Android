// AndroidOnly: WP-101 Prizm Mobile Observer settings state holder tests.
package com.meshcoreone.android.feature.settings.prizm

import com.meshcoreone.android.feature.settings.app.support.scenario
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Test

internal class FakePrizmPort : PrizmObserverPort {
    override val state = MutableStateFlow(PrizmObserverState())
    val calls = mutableListOf<String>()
    var signInResult: PrizmSignInResult = PrizmSignInResult.SignedIn
    var claimResults = ArrayDeque<PrizmClaimResult>()
    var location: PrizmCoordinate? = PrizmCoordinate(46.8772, -96.7898)
    var deleteResult: PrizmDeleteResult = PrizmDeleteResult.Unavailable
    var throwOnClaim = false

    override suspend fun signIn(): PrizmSignInResult { calls += "signIn"; if (signInResult is PrizmSignInResult.SignedIn) state.update { it.copy(signedInAs = "Fargo Fan") }; return signInResult }
    override suspend fun signOut() { calls += "signOut"; state.update { it.copy(signedInAs = null, uploadEnabled = false) } }
    override fun setUploadEnabled(enabled: Boolean) { calls += "upload:$enabled"; state.update { it.copy(uploadEnabled = enabled) } }
    override fun setShareLocation(enabled: Boolean) { calls += "share:$enabled"; state.update { it.copy(shareLocation = enabled) } }
    override fun addZone(latitude: Double, longitude: Double, radiusMeters: Double) { calls += "addZone:$latitude,$longitude,$radiusMeters" }
    override fun removeZone(id: String) { calls += "removeZone:$id" }
    override suspend fun currentLocation() = location
    override suspend fun claim(type: PrizmNodeType, confirmTakeover: Boolean): PrizmClaimResult {
        calls += "claim:$type:$confirmTakeover"
        if (throwOnClaim) error("boom")
        return claimResults.removeFirstOrNull() ?: PrizmClaimResult.Linked
    }
    override fun dismissClaimPrompt() { calls += "dismissClaim" }
    override suspend fun deleteMyData(): PrizmDeleteResult = deleteResult
}

private class FakePermissions(var grant: Boolean = true) : PrizmPermissionRequester {
    val requests = mutableListOf<Pair<Boolean, Boolean>>()
    override fun request(needsLocation: Boolean, needsNotifications: Boolean, onResult: (Boolean) -> Unit) {
        requests += needsLocation to needsNotifications
        onResult(grant)
    }
}

class PrizmObserverStateHolderTest {
    @Test fun `upload needs a signed in user before any permission is asked`() = scenario {
        val port = FakePrizmPort(); val perms = FakePermissions()
        val holder = PrizmObserverStateHolder(port, scope, perms)
        holder.setUploadEnabled(true)
        assertEquals(PrizmNotice.NEEDS_SIGN_IN, holder.notice.value)
        assertTrue(perms.requests.isEmpty()); assertTrue(port.calls.isEmpty())
    }

    @Test fun `upload turns on only after the location and notification permissions are granted`() = scenario {
        val port = FakePrizmPort(); val perms = FakePermissions()
        val holder = PrizmObserverStateHolder(port, scope, perms)
        holder.signIn()
        holder.setUploadEnabled(true)
        assertEquals(listOf(true to true), perms.requests)
        assertTrue(holder.state.value.uploadEnabled); assertNull(holder.notice.value)
    }

    @Test fun `denied permissions leave upload off and explain why`() = scenario {
        val port = FakePrizmPort(); val perms = FakePermissions(grant = false)
        val holder = PrizmObserverStateHolder(port, scope, perms)
        holder.signIn(); holder.setUploadEnabled(true)
        assertFalse(holder.state.value.uploadEnabled)
        assertEquals(PrizmNotice.NEEDS_PERMISSION, holder.notice.value)
    }

    @Test fun `turning upload off needs no permission and goes straight to the port`() = scenario {
        val port = FakePrizmPort(); val perms = FakePermissions()
        val holder = PrizmObserverStateHolder(port, scope, perms)
        holder.signIn(); holder.setUploadEnabled(true); perms.requests.clear()
        holder.setUploadEnabled(false)
        assertFalse(holder.state.value.uploadEnabled); assertTrue(perms.requests.isEmpty())
    }

    @Test fun `sharing location asks only for location`() = scenario {
        val port = FakePrizmPort(); val perms = FakePermissions()
        val holder = PrizmObserverStateHolder(port, scope, perms)
        holder.setShareLocation(true)
        assertEquals(listOf(true to false), perms.requests); assertTrue(holder.state.value.shareLocation)
        perms.grant = false; holder.setShareLocation(false); holder.setShareLocation(true)
        assertEquals(PrizmNotice.NEEDS_LOCATION_PERMISSION, holder.notice.value)
    }

    @Test fun `browser sign in shows a waiting notice and failures are surfaced`() = scenario {
        val port = FakePrizmPort(); val holder = PrizmObserverStateHolder(port, scope, FakePermissions())
        port.signInResult = PrizmSignInResult.BrowserOpened
        holder.signIn()
        assertEquals(PrizmNotice.SIGN_IN_WAITING, holder.notice.value); assertFalse(holder.busy.value)
        port.signInResult = PrizmSignInResult.Failed("denied")
        holder.signIn()
        assertEquals("denied", holder.signInFailure.value)
        holder.clearFailure(); assertNull(holder.signInFailure.value)
    }

    @Test fun `sign out clears the user and turns upload off`() = scenario {
        val port = FakePrizmPort(); val holder = PrizmObserverStateHolder(port, scope, FakePermissions())
        holder.signIn(); holder.setUploadEnabled(true)
        holder.signOut()
        assertFalse(holder.state.value.signedIn); assertFalse(holder.state.value.uploadEnabled)
    }
}

class PrizmPrivacyStateHolderTest {
    @Test fun `a valid zone defaults to 300 metres and resets the form`() = scenario {
        val port = FakePrizmPort(); val holder = PrizmPrivacyStateHolder(port, scope)
        holder.setLatitude("46.8772"); holder.setLongitude("-96.7898"); holder.setRadius("")
        assertTrue(holder.addZone())
        assertEquals(listOf("addZone:46.8772,-96.7898,300.0"), port.calls)
        assertEquals(PrizmZoneForm(), holder.form.value)
    }

    @Test fun `invalid coordinates and radius are rejected per field and nothing is added`() = scenario {
        val port = FakePrizmPort(); val holder = PrizmPrivacyStateHolder(port, scope)
        holder.setLatitude("91"); holder.setLongitude("abc"); holder.setRadius("0")
        assertFalse(holder.addZone())
        assertEquals(setOf(PrizmZoneError.LATITUDE, PrizmZoneError.LONGITUDE, PrizmZoneError.RADIUS), holder.form.value.errors)
        assertTrue(port.calls.isEmpty())
        holder.setLatitude("46"); assertEquals(setOf(PrizmZoneError.LONGITUDE, PrizmZoneError.RADIUS), holder.form.value.errors)
    }

    @Test fun `use my current location fills six decimals or reports it unavailable`() = scenario {
        val port = FakePrizmPort(); val holder = PrizmPrivacyStateHolder(port, scope)
        holder.useCurrentLocation()
        assertEquals("46.877200", holder.form.value.latitude); assertEquals("-96.789800", holder.form.value.longitude)
        port.location = null; holder.useCurrentLocation()
        assertTrue(holder.form.value.locationUnavailable)
    }

    @Test fun `removing a zone and delete my data go through the port`() = scenario {
        val port = FakePrizmPort(); val holder = PrizmPrivacyStateHolder(port, scope)
        holder.removeZone("z1"); assertEquals(listOf("removeZone:z1"), port.calls)
        holder.deleteMyData()
        assertEquals(PrizmDeleteStatus.Finished(PrizmDeleteResult.Unavailable), holder.delete.value)
        holder.acknowledgeDelete(); assertEquals(PrizmDeleteStatus.Idle, holder.delete.value)
    }
}

class PrizmClaimStateHolderTest {
    @Test fun `prompt then type then claim finishes linked`() = scenario {
        val port = FakePrizmPort(); val holder = PrizmClaimStateHolder(port, scope)
        holder.accept(); assertEquals(PrizmClaimPhase.PickType, holder.phase.value)
        holder.choose(PrizmNodeType.VEHICLE)
        assertEquals(PrizmClaimPhase.Finished(PrizmClaimResult.Linked), holder.phase.value)
        assertEquals(listOf("claim:VEHICLE:false"), port.calls)
        holder.dismiss(); assertEquals(PrizmClaimPhase.Idle, holder.phase.value)
    }

    @Test fun `a node claimed by someone else needs an explicit takeover confirmation`() = scenario {
        val port = FakePrizmPort(); val holder = PrizmClaimStateHolder(port, scope)
        port.claimResults.addLast(PrizmClaimResult.NeedsConfirmation("Alex"))
        holder.accept(); holder.choose(PrizmNodeType.HOME_BASE)
        assertEquals(PrizmClaimPhase.ConfirmTakeover(PrizmNodeType.HOME_BASE, "Alex"), holder.phase.value)
        assertEquals(1, port.calls.size, "no second request until the user confirms")
        holder.confirmTakeover()
        assertEquals(listOf("claim:HOME_BASE:false", "claim:HOME_BASE:true"), port.calls)
        assertEquals(PrizmClaimPhase.Finished(PrizmClaimResult.Linked), holder.phase.value)
    }

    @Test fun `declining the takeover claims nothing`() = scenario {
        val port = FakePrizmPort(); val holder = PrizmClaimStateHolder(port, scope)
        port.claimResults.addLast(PrizmClaimResult.NeedsConfirmation(null))
        holder.accept(); holder.choose(PrizmNodeType.MOBILE); holder.dismiss()
        assertEquals(PrizmClaimPhase.Idle, holder.phase.value); assertEquals(1, port.calls.size)
    }

    @Test fun `not now dismisses the prompt on the port and unexpected errors become a network failure`() = scenario {
        val port = FakePrizmPort(); val holder = PrizmClaimStateHolder(port, scope)
        holder.notNow(); assertEquals(listOf("dismissClaim"), port.calls)
        port.throwOnClaim = true
        holder.accept(); holder.choose(PrizmNodeType.HANDHELD)
        assertIs<PrizmClaimResult.NetworkFailed>((holder.phase.value as PrizmClaimPhase.Finished).result)
    }

    @Test fun `choosing a type outside the picker is ignored`() = scenario {
        val port = FakePrizmPort(); val holder = PrizmClaimStateHolder(port, scope)
        holder.choose(PrizmNodeType.PORTABLE); holder.confirmTakeover()
        assertTrue(port.calls.isEmpty())
    }
}

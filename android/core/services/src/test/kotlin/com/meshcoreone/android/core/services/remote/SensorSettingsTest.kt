// AndroidOnly: WP-311 Sensor settings wire responses and administration over the remote-node session.
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RoomPermissionLevel
import com.meshcoreone.android.core.protocol.event.LoginInfo
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.ContactType
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class SensorSettingsTest {
    @Test
    fun `pages retain values and reject malformed pagination`() {
        assertEquals(
            SensorSettingsResponseParser.Page(3, listOf(SensorSetting("a", "1"), SensorSetting("b", "x=y")), 2),
            SensorSettingsResponseParser.list("3 vars\na=1\nb=x=y\n... next:2", 0),
        )
        assertEquals(SensorSettingsResponseParser.Page(3, listOf(SensorSetting("c", "")), null),
            SensorSettingsResponseParser.list("3 vars\nc=", 2))
        assertEquals(SensorSettingsResponseParser.Page(0, emptyList(), null),
            SensorSettingsResponseParser.list("no custom var", 0))
        listOf("3 vars\na=1\n... next:2", "3 vars\na=1\na=2\n... next:2",
            "3 vars\na=1", "3 vars\n=1\n... next:1").forEach {
            assertFailsWith<RemoteNodeError.InvalidResponse> { SensorSettingsResponseParser.list(it, 0) }
        }
    }

    @Test
    fun `settings reject whitespace control and oversized utf8 values`() {
        val setting = SensorSetting("gps", "0")
        assertEquals("sensor set gps 1", setting.command("1"))
        listOf("", "a b", "a\nb", "é".repeat(80)).forEach {
            assertFailsWith<RemoteNodeError.InvalidSensorSetting> { setting.command(it) }
        }
    }

    @TestFactory
    fun exchanges(): List<DynamicTest> = listOf(
        remoteCoreNative("sensor pages and verified write use one command per exchange") {
            withRemoteCoreHarness {
                val publicKey = remoteCoreKey(0xC1)
                val key = addSession(remoteCoreSession(radioId, publicKey, RemoteNodeRole.SENSOR, RoomPermissionLevel.ADMIN, true))
                startMonitoring()
                suspend fun reply(index: Int, text: String) {
                    remoteCoreAwait("sensor command not sent") { session.sendCommandInvocations.size > index }
                    val command = session.sendCommandInvocations[index].command
                    yieldReply(checkNotNull(CLIResponse.splitEchoedPrefix(command)).prefix + text, publicKey)
                }
                val load = async { service.fetchSensorSettings(key) }
                reply(0, "3 vars\na=1\nb=x=y\n... next:2")
                reply(1, "3 vars\nc=")
                assertEquals(listOf(SensorSetting("a", "1"), SensorSetting("b", "x=y"), SensorSetting("c", "")), load.await())
                val save = async { service.setSensorSetting(key, SensorSetting("a", "1"), "2") }
                reply(2, "ok")
                reply(3, "> 2")
                assertEquals(SensorSetting("a", "2"), save.await())
                assertEquals(listOf("sensor list", "sensor list 2", "sensor set a 2", "sensor get a"),
                    session.sendCommandInvocations.map { checkNotNull(CLIResponse.splitEchoedPrefix(it.command)).body })
                assertTrue(session.resetPathPublicKeys.isEmpty())
            }
        },
        remoteCoreNative("sensor guests cannot issue settings commands") {
            withRemoteCoreHarness {
                val key = addSession(remoteCoreSession(radioId, role = RemoteNodeRole.SENSOR, isConnected = true))
                assertFailsWith<RemoteNodeError.PermissionDenied> { service.fetchSensorSettings(key) }
                assertTrue(session.sendCommandInvocations.isEmpty())
            }
        },
        remoteCoreNative("sensor CLI refuses non-sensor sessions") {
            withRemoteCoreHarness {
                val key = addSession(remoteCoreSession(radioId, role = RemoteNodeRole.REPEATER,
                    permissionLevel = RoomPermissionLevel.ADMIN, isConnected = true))
                assertFailsWith<RemoteNodeError.SessionNotFound> { service.sendSensorRawCLICommand(key, "sensor list") }
                assertTrue(session.sendCommandInvocations.isEmpty())
            }
        },
        remoteCoreNative("sensor admin permission is rechecked after waiting for the CLI slot") {
            withRemoteCoreHarness {
                val publicKey = remoteCoreKey(0xC1)
                val key = addSession(remoteCoreSession(radioId, publicKey, RemoteNodeRole.SENSOR, RoomPermissionLevel.ADMIN, true))
                startMonitoring()
                val first = async { service.fetchSensorSettings(key) }
                remoteCoreAwait("first command not sent") { session.sendCommandInvocations.size == 1 }
                val second = async { runCatching { service.sendRawCLICommand(key, "sensor list") } }
                remoteCoreAwait("second command did not queue") {
                    synchronized(service.lock) { service.cliSlotWaiters.isNotEmpty() }
                }
                store.updateRemoteNodeSessionConnection(key, true, RoomPermissionLevel.GUEST)
                val prefix = checkNotNull(CLIResponse.splitEchoedPrefix(session.sendCommandInvocations[0].command)).prefix
                yieldReply(prefix + "no custom var", publicKey)
                first.await()
                assertIs<RemoteNodeError.PermissionDenied>(second.await().exceptionOrNull())
                assertEquals(1, session.sendCommandInvocations.size)
            }
        },
        remoteCoreNative("sensor send failure reports an uncertain outcome without retry") {
            withRemoteCoreHarness {
                val key = addSession(remoteCoreSession(radioId, role = RemoteNodeRole.SENSOR,
                    permissionLevel = RoomPermissionLevel.ADMIN, isConnected = true))
                session.sendCommandResult = Result.failure(MeshCoreException.NotConnected())
                assertFailsWith<RemoteNodeError.SensorOutcomeUnknown> {
                    service.setSensorSetting(key, SensorSetting("a", "1"), "2")
                }
                assertEquals(1, session.sendCommandInvocations.size)
                assertTrue(session.resetPathPublicKeys.isEmpty())
            }
        },
        remoteCoreNative("service shutdown during sensor write reports an uncertain outcome") {
            withRemoteCoreHarness {
                val key = addSession(remoteCoreSession(radioId, role = RemoteNodeRole.SENSOR,
                    permissionLevel = RoomPermissionLevel.ADMIN, isConnected = true))
                val write = async { runCatching { service.setSensorSetting(key, SensorSetting("a", "1"), "2") } }
                remoteCoreAwait("sensor write did not start") { session.sendCommandInvocations.size == 1 }
                service.close()
                assertIs<RemoteNodeError.SensorOutcomeUnknown>(write.await().exceptionOrNull())
                assertEquals(1, session.sendCommandInvocations.size)
            }
        },
        remoteCoreNative("sensor authorization is rechecked in the sender before dispatch") {
            withRemoteCoreHarness {
                val key = addSession(remoteCoreSession(radioId, role = RemoteNodeRole.SENSOR,
                    permissionLevel = RoomPermissionLevel.ADMIN, isConnected = true))
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var reads = 0
                store.fetchSessionHandler = {
                    if (++reads == 3) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
                val command = async { runCatching { service.sendRawCLICommand(key, "sensor list") } }
                entered.await()
                store.updateRemoteNodeSessionConnection(key, true, RoomPermissionLevel.GUEST)
                release.complete(Unit)
                assertIs<RemoteNodeError.PermissionDenied>(command.await().exceptionOrNull())
                assertTrue(session.sendCommandInvocations.isEmpty())
            }
        },
        remoteCoreNative("cancelled sensor sender cannot dispatch after dismissal") {
            withRemoteCoreHarness {
                val key = addSession(remoteCoreSession(radioId, role = RemoteNodeRole.SENSOR,
                    permissionLevel = RoomPermissionLevel.ADMIN, isConnected = true))
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var reads = 0
                store.fetchSessionHandler = {
                    if (++reads == 3) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
                val command = launch { service.sendRawCLICommand(key, "sensor set a 2") }
                entered.await()
                command.cancelAndJoin()
                release.complete(Unit)
                remoteCoreSettle()
                assertTrue(session.sendCommandInvocations.isEmpty())
            }
        },
        remoteCoreNative("cancelled sensor command retains its CLI slot until its sender exits") {
            withRemoteCoreHarness {
                val publicKey = remoteCoreKey(0xC1)
                val key = addSession(remoteCoreSession(radioId, publicKey, RemoteNodeRole.SENSOR, RoomPermissionLevel.ADMIN, true))
                startMonitoring()
                val release = CompletableDeferred<Unit>()
                var sends = 0
                session.sendCommandHandler = {
                    if (++sends == 1) withContext(NonCancellable) { release.await() }
                    RemoteCoreFakeSession.sentInfo(100u)
                }
                val first = launch { service.sendRawCLICommand(key, "sensor set a 2") }
                remoteCoreAwait("first sender did not start") { session.sendCommandInvocations.size == 1 }
                first.cancel()
                val second = async { service.sendRawCLICommand(key, "sensor list") }
                remoteCoreAwait("second command did not queue") {
                    synchronized(service.lock) { service.cliSlotWaiters.isNotEmpty() }
                }
                assertEquals(1, session.sendCommandInvocations.size)
                assertTrue(!first.isCompleted)
                release.complete(Unit)
                first.join()
                remoteCoreAwait("second sender did not start") { session.sendCommandInvocations.size == 2 }
                val prefix = checkNotNull(CLIResponse.splitEchoedPrefix(session.sendCommandInvocations[1].command)).prefix
                yieldReply(prefix + "no custom var", publicKey)
                assertEquals("no custom var", second.await())
            }
        },
        remoteCoreNative("sensor readback mismatch reports an uncertain outcome") {
            withRemoteCoreHarness {
                val publicKey = remoteCoreKey(0xC1)
                val key = addSession(remoteCoreSession(radioId, publicKey, RemoteNodeRole.SENSOR, RoomPermissionLevel.ADMIN, true))
                startMonitoring()
                val save = async { runCatching { service.setSensorSetting(key, SensorSetting("a", "1"), "2") } }
                remoteCoreAwait("setter not sent") { session.sendCommandInvocations.size == 1 }
                yieldReply(checkNotNull(CLIResponse.splitEchoedPrefix(session.sendCommandInvocations[0].command)).prefix + "ok", publicKey)
                remoteCoreAwait("readback not sent") { session.sendCommandInvocations.size == 2 }
                yieldReply(checkNotNull(CLIResponse.splitEchoedPrefix(session.sendCommandInvocations[1].command)).prefix + "> 1", publicKey)
                assertIs<RemoteNodeError.SensorOutcomeUnknown>(save.await().exceptionOrNull())
            }
        },
        remoteCoreNative("a replacement sensor login survives cancellation of its predecessor") {
            withRemoteCoreHarness {
                val publicKey = remoteCoreKey(0xC1)
                val contact = remoteCoreContact(radioId, publicKey).copy(typeRawValue = ContactType.SENSOR.rawValue)
                store.saveContact(contact)
                startMonitoring()
                session.setSendLoginResults(List(2) { Result.success(RemoteCoreFakeSession.sentInfo(1000u)) })
                val first = async {
                    try {
                        service.connectSensor(radioId, contact, "old", false, true, 0u) {}
                        false
                    } catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        true
                    }
                }
                remoteCoreAwait("first login not sent") { session.sendLoginInvocations.size == 1 }
                val second = async { service.connectSensor(radioId, contact, "new", false, true, 0u) {} }
                remoteCoreAwait("replacement login not sent") { session.sendLoginInvocations.size == 2 }
                assertTrue(first.await(), "replacing an attempt must not cancel its caller")
                session.yieldEvent(MeshEvent.LoginSuccess(LoginInfo(2u, true, publicKey.prefix(6))))
                val connected = second.await()
                assertTrue(connected.isSensor)
                assertTrue(connected.isConnected)
                assertTrue(checkNotNull(store.session(com.meshcoreone.android.core.contracts.domain.EntityKey(radioId, connected.id))).isConnected)
            }
        },
        remoteCoreNative("sensor ACL without admin flag cannot open management") {
            withRemoteCoreHarness {
                val publicKey = remoteCoreKey(0xC2)
                val contact = remoteCoreContact(radioId, publicKey).copy(typeRawValue = ContactType.SENSOR.rawValue)
                store.saveContact(contact)
                startMonitoring()
                session.setSendLoginResults(listOf(Result.success(RemoteCoreFakeSession.sentInfo(1000u))))
                val attempt = async { runCatching { service.connectSensor(radioId, contact, "", false, true, 0u) {} } }
                remoteCoreAwait("login not sent") { session.sendLoginInvocations.size == 1 }
                session.yieldEvent(MeshEvent.LoginSuccess(LoginInfo(2u, false, publicKey.prefix(6))))
                assertIs<RemoteNodeError.PermissionDenied>(attempt.await().exceptionOrNull())
                assertTrue(session.sendCommandInvocations.isEmpty())
            }
        },
    )
}

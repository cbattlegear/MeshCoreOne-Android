// AndroidOnly: WP-311 Sensor settings protocol based on upstream MeshCoreOne at 5e980a53e077c45a1270170612fdf5dae97792ff.
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.model.ContactType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SensorSetting(val key: String, val value: String) {
    fun command(value: String): String {
        fun validToken(token: String) = token.isNotEmpty() && token.none {
            it.isWhitespace() || it.isISOControl()
        }
        if (!validToken(key) || !validToken(value)) throw RemoteNodeError.InvalidSensorSetting()
        return "sensor set $key $value".also(::validateSensorCommand)
    }
}

internal fun validateSensorCommand(command: String) {
    if (command.isEmpty() || command.toByteArray(Charsets.UTF_8).size > ProtocolLimits.MAX_MESSAGE_LENGTH - 3 ||
        command.any { it.isISOControl() }) throw RemoteNodeError.InvalidSensorSetting()
}

suspend fun RemoteNodeService.connectSensor(
    radioId: RadioId,
    contact: ContactDTO,
    password: String,
    rememberPassword: Boolean,
    requireAdmin: Boolean,
    pathLength: UByte,
    onTimeoutKnown: suspend (Long) -> Unit,
): RemoteNodeSessionDTO = coroutineScope {
    if (contact.type != ContactType.SENSOR || contact.radioId != radioId) throw RemoteNodeError.InvalidResponse()
    if (password.toByteArray(Charsets.UTF_8).size > 15) throw RemoteNodeError.LoginFailed("Password exceeds 15 bytes")
    val identity = radioId to contact.publicKey
    val owner = checkNotNull(currentCoroutineContext()[Job])
    val (mutex, previous) = synchronized(lock) {
        sensorConnectionLocks.getOrPut(identity) { Mutex() } to sensorConnectionAttempts.put(identity, owner)
    }
    if (previous !== owner) previous?.cancel()
    try {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (!serviceScope.isActive) throw RemoteNodeError.NotConnected()
            val created = createSession(radioId, contact)
            try {
                val result = login(created.remoteSessionKey, password, pathLength, onTimeoutKnown)
                currentCoroutineContext().ensureActive()
                if (!serviceScope.isActive) throw RemoteNodeError.NotConnected()
                if (requireAdmin && !result.isAdmin) throw RemoteNodeError.PermissionDenied()
                val current = dataStore.fetchRemoteNodeSession(created.remoteSessionKey) ?: throw RemoteNodeError.SessionNotFound()
                if (!current.isSensor || !current.isConnected) throw RemoteNodeError.NotConnected()
                if (rememberPassword) storePassword(password, contact.publicKey) else deletePassword(contact)
                currentCoroutineContext().ensureActive()
                current
            } catch (error: Exception) {
                withContext(NonCancellable) { disconnect(created.remoteSessionKey) }
                throw error
            }
        }
    } finally {
        synchronized(lock) {
            if (sensorConnectionAttempts[identity] === owner) sensorConnectionAttempts.remove(identity)
        }
    }
}

internal object SensorSettingsResponseParser {
    data class Page(val total: Int, val settings: List<SensorSetting>, val next: Int?)

    fun list(response: String, offset: Int): Page {
        if (response == "no custom var" && offset == 0) return Page(0, emptyList(), null)
        val lines = response.split('\n').toMutableList()
        if (lines.lastOrNull() == "") lines.removeAt(lines.lastIndex)
        val header = lines.removeFirstOrNull() ?: throw RemoteNodeError.InvalidResponse()
        if (!header.endsWith(" vars")) throw RemoteNodeError.InvalidResponse()
        val total = header.removeSuffix(" vars").toIntOrNull() ?: throw RemoteNodeError.InvalidResponse()
        if (total <= 0 || offset < 0 || offset >= total) throw RemoteNodeError.InvalidResponse()
        val next = if (lines.lastOrNull()?.startsWith("... next:") == true) {
            lines.removeAt(lines.lastIndex).removePrefix("... next:").toIntOrNull()
                ?: throw RemoteNodeError.InvalidResponse()
        } else null
        val keys = mutableSetOf<String>()
        val settings = lines.map { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) throw RemoteNodeError.InvalidResponse()
            val key = line.substring(0, separator)
            if (!keys.add(key)) throw RemoteNodeError.InvalidResponse()
            SensorSetting(key, line.substring(separator + 1))
        }
        val end = offset.toLong() + settings.size
        if (settings.isEmpty() || end > total ||
            (next != null && (next.toLong() != end || next >= total)) ||
            (next == null && end != total.toLong())) throw RemoteNodeError.InvalidResponse()
        return Page(total, settings, next)
    }

    fun value(response: String): String? {
        if (response == "null") return null
        if (!response.startsWith("> ")) throw RemoteNodeError.InvalidResponse()
        return response.substring(2)
    }
}

internal suspend fun RemoteNodeService.requireSensorAdmin(key: EntityKey) {
    currentCoroutineContext().ensureActive()
    val session = dataStore.fetchRemoteNodeSession(key) ?: throw RemoteNodeError.SessionNotFound()
    if (!session.isSensor) throw RemoteNodeError.SessionNotFound()
    if (!session.isConnected) throw RemoteNodeError.NotConnected()
    if (!session.isAdmin) throw RemoteNodeError.PermissionDenied()
}

suspend fun RemoteNodeService.fetchSensorSettings(key: EntityKey): List<SensorSetting> {
    requireSensorAdmin(key)
    val settings = mutableListOf<SensorSetting>()
    val keys = mutableSetOf<String>()
    var total: Int? = null
    var offset = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val command = if (offset == 0) "sensor list" else "sensor list $offset"
        val page = SensorSettingsResponseParser.list(sendRawCLICommand(key, command), offset)
        if (total != null && total != page.total) throw RemoteNodeError.InvalidResponse()
        total = page.total
        for (setting in page.settings) {
            if (!keys.add(setting.key)) throw RemoteNodeError.InvalidResponse()
            settings.add(setting)
        }
        offset = page.next ?: return settings
    }
}

suspend fun RemoteNodeService.setSensorSetting(key: EntityKey, setting: SensorSetting, value: String): SensorSetting {
    val command = setting.command(value)
    requireSensorAdmin(key)
    val response = try {
        sendRawCLICommand(key, command)
    } catch (error: RemoteNodeError.Timeout) {
        throw RemoteNodeError.SensorOutcomeUnknown()
    } catch (error: RemoteNodeError.SessionError) {
        throw RemoteNodeError.SensorOutcomeUnknown()
    } catch (error: RemoteNodeError.Cancelled) {
        throw RemoteNodeError.SensorOutcomeUnknown()
    }
    if (response == "can't find custom var") throw RemoteNodeError.SensorSettingRejected()
    if (response != "ok") throw RemoteNodeError.SensorOutcomeUnknown()
    val verified = try {
        SensorSettingsResponseParser.value(sendRawCLICommand(key, "sensor get ${setting.key}"))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: RemoteNodeError) {
        throw RemoteNodeError.SensorOutcomeUnknown()
    }
    if (verified != value) throw RemoteNodeError.SensorOutcomeUnknown()
    return SensorSetting(setting.key, value)
}

suspend fun RemoteNodeService.rebootSensor(key: EntityKey) {
    requireSensorAdmin(key)
    sendRawCLICommand(key, "reboot")
}

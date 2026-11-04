// AndroidOnly: WP-401 Typed, content-free notification action extras for Android PendingIntents.
package com.meshcoreone.android.platform.notifications.messaging

import android.content.Intent
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

internal object NotificationPayloadCodec {
    private const val TYPE = "mc1.notification.type"
    private const val RADIO = "mc1.notification.radio"
    private const val CONTACT = "mc1.notification.contact"
    private const val MESSAGE = "mc1.notification.message"
    private const val CHANNEL = "mc1.notification.channel"
    private const val ROOM = "mc1.notification.room"
    private const val SESSION = "mc1.notification.session"
    private const val BATTERY = "mc1.notification.battery"

    fun write(intent: Intent, payload: NotificationPayload): Intent = intent.apply {
        when (payload) {
            is NotificationPayload.DirectMessage -> {
                putExtra(TYPE, "direct")
                entity(payload.contact, CONTACT)
                putExtra(MESSAGE, payload.messageID.toString())
            }
            is NotificationPayload.ChannelMessage -> {
                putExtra(TYPE, "channel")
                radio(payload.radioId)
                putExtra(CHANNEL, payload.channelIndex.toInt())
                putExtra(MESSAGE, payload.messageID.toString())
            }
            is NotificationPayload.RoomMessage -> {
                putExtra(TYPE, "room")
                putExtra(ROOM, payload.roomName)
                entity(payload.session, SESSION)
                putExtra(MESSAGE, payload.messageID.toString())
            }
            is NotificationPayload.NewContact -> {
                putExtra(TYPE, "new-contact")
                entity(payload.contact, CONTACT)
            }
            is NotificationPayload.Reaction -> {
                putExtra(TYPE, "reaction")
                putExtra(MESSAGE, payload.messageID.toString())
                payload.contact?.let { entity(it, CONTACT) }
                payload.radioId?.let { value -> radio(value) }
                payload.channelIndex?.let { putExtra(CHANNEL, it.toInt()) }
            }
            is NotificationPayload.LowBattery -> {
                putExtra(TYPE, "low-battery")
                putExtra(BATTERY, payload.batteryPercentage)
            }
            is NotificationPayload.QuickReplyFailed -> {
                putExtra(TYPE, "reply-failed")
                entity(payload.contact, CONTACT)
            }
            is NotificationPayload.ChannelQuickReplyFailed -> {
                putExtra(TYPE, "channel-reply-failed")
                radio(payload.radioId)
                putExtra(CHANNEL, payload.channelIndex.toInt())
            }
        }
    }

    fun encode(payload: NotificationPayload): String {
        val extras = write(Intent(), payload).extras ?: error("Notification payload has no encoded fields")
        val fields = JSONObject()
        extras.keySet().sorted().forEach { key ->
            val taggedValue = when (val value = extras.get(key)) {
                is String -> JSONObject().put("kind", "string").put("value", value)
                is Int -> JSONObject().put("kind", "int").put("value", value)
                is Long -> JSONObject().put("kind", "long").put("value", value)
                else -> error("Unsupported notification payload field")
            }
            fields.put(key, taggedValue)
        }
        return JSONObject().put("version", 1).put("fields", fields).toString()
    }

    fun decode(encoded: String): NotificationPayload? = try {
        val root = JSONObject(encoded)
        if (root.keys().asSequence().toSet() != setOf("version", "fields") || root.opt("version") != 1) {
            return null
        }
        val fields = root.getJSONObject("fields")
        val intent = Intent()
        val keys = fields.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in PERSISTED_KEYS) return null
            val taggedValue = fields.getJSONObject(key)
            if (taggedValue.keys().asSequence().toSet() != setOf("kind", "value")) return null
            when (taggedValue.opt("kind")) {
                "string" -> {
                    val value = taggedValue.opt("value")
                    if (value !is String) return null
                    intent.putExtra(key, value)
                }
                "int" -> {
                    val value = taggedValue.opt("value")
                    if (value !is Number) return null
                    val longValue = value.toLong()
                    if (value.toDouble() != longValue.toDouble() ||
                        longValue !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                    ) return null
                    intent.putExtra(key, longValue.toInt())
                }
                "long" -> {
                    val value = taggedValue.opt("value")
                    if (value !is Number) return null
                    val longValue = value.toLong()
                    if (value.toDouble() != longValue.toDouble()) return null
                    intent.putExtra(key, longValue)
                }
                else -> return null
            }
        }
        read(intent)
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun read(intent: Intent): NotificationPayload? = try {
        when (intent.getStringExtra(TYPE)) {
            "direct" -> NotificationPayload.DirectMessage(entity(intent, CONTACT), uuid(intent, MESSAGE))
            "channel" -> NotificationPayload.ChannelMessage(
                radio(intent),
                channel(intent),
                uuid(intent, MESSAGE),
            )
            "room" -> NotificationPayload.RoomMessage(
                intent.getStringExtra(ROOM) ?: return null,
                entity(intent, SESSION),
                uuid(intent, MESSAGE),
            )
            "new-contact" -> NotificationPayload.NewContact(entity(intent, CONTACT))
            "reaction" -> NotificationPayload.Reaction(
                messageID = uuid(intent, MESSAGE),
                contact = optionalEntity(intent, CONTACT),
                channelIndex = optionalChannel(intent),
                radioId = optionalRadio(intent),
            )
            "low-battery" -> NotificationPayload.LowBattery(intent.getLongExtra(BATTERY, Long.MIN_VALUE).also {
                if (it == Long.MIN_VALUE) return null
            })
            "reply-failed" -> NotificationPayload.QuickReplyFailed(entity(intent, CONTACT))
            "channel-reply-failed" -> NotificationPayload.ChannelQuickReplyFailed(radio(intent), channel(intent))
            else -> null
        }
    } catch (_: RuntimeException) {
        null
    }

    fun radioId(payload: NotificationPayload): RadioId? = when (payload) {
        is NotificationPayload.DirectMessage -> payload.contact.radioId
        is NotificationPayload.ChannelMessage -> payload.radioId
        is NotificationPayload.RoomMessage -> payload.session.radioId
        is NotificationPayload.NewContact -> payload.contact.radioId
        is NotificationPayload.Reaction -> payload.contact?.radioId ?: payload.radioId
        is NotificationPayload.QuickReplyFailed -> payload.contact.radioId
        is NotificationPayload.ChannelQuickReplyFailed -> payload.radioId
        is NotificationPayload.LowBattery -> null
    }

    private fun Intent.entity(value: EntityKey, prefix: String) {
        putExtra("$prefix.radio", value.radioId.value.toString())
        putExtra("$prefix.id", value.id.toString())
    }

    private fun Intent.radio(value: RadioId) {
        putExtra(RADIO, value.value.toString())
    }

    private fun entity(intent: Intent, prefix: String) = EntityKey(
        RadioId(UUID.fromString(intent.getStringExtra("$prefix.radio") ?: throw IllegalArgumentException())),
        UUID.fromString(intent.getStringExtra("$prefix.id") ?: throw IllegalArgumentException()),
    )

    private fun optionalEntity(intent: Intent, prefix: String): EntityKey? =
        if (intent.hasExtra("$prefix.radio") && intent.hasExtra("$prefix.id")) entity(intent, prefix) else null

    private fun radio(intent: Intent) =
        RadioId(UUID.fromString(intent.getStringExtra(RADIO) ?: throw IllegalArgumentException()))

    private fun optionalRadio(intent: Intent): RadioId? =
        intent.getStringExtra(RADIO)?.let { RadioId(UUID.fromString(it)) }

    private fun uuid(intent: Intent, key: String) =
        UUID.fromString(intent.getStringExtra(key) ?: throw IllegalArgumentException())

    private fun channel(intent: Intent): UByte {
        val value = intent.getIntExtra(CHANNEL, -1)
        require(value in 0..UByte.MAX_VALUE.toInt())
        return value.toUByte()
    }

    private fun optionalChannel(intent: Intent): UByte? =
        if (intent.hasExtra(CHANNEL)) channel(intent) else null

    private val PERSISTED_KEYS = setOf(
        TYPE, RADIO, MESSAGE, CHANNEL, ROOM, BATTERY,
        "$CONTACT.radio", "$CONTACT.id", "$SESSION.radio", "$SESSION.id",
    )
}

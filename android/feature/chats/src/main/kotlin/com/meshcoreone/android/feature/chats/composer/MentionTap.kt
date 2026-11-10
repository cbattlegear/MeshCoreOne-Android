// PortedFrom: MC1/Views/Chats/Mentions/MentionTapEvaluator.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Mentions/MentionPickerContext.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.feature.chats.list.ChatTextMatching
import com.meshcoreone.android.feature.chats.list.ChatMailtoPolicy
import com.meshcoreone.android.feature.chats.list.SenderContactMatcher
import java.util.UUID

/** Data for the disambiguation / status sheet shown when a tapped `@mention` is not exactly one saved contact. */
data class MentionPickerContext(
    val name: String,
    val radioId: RadioId,
    val matches: List<ContactDTO>,
    val isSelfMention: Boolean,
    val id: UUID = UUID.randomUUID(),
)

/** Pure resolution of a tapped `meshcoreone://mention/...` link. */
object MentionTapEvaluator {
    sealed interface Outcome {
        data class Navigate(val contact: ContactDTO) : Outcome
        data class Picker(val context: MentionPickerContext) : Outcome
    }

    fun evaluate(rawName: String, contacts: List<ContactDTO>, connectedDeviceName: String?, radioId: RadioId): Outcome {
        val sanitized = MessageTextFormatter.displayName(rawName)
        if (ComposerText.isBlank(sanitized)) return Outcome.Picker(MentionPickerContext(sanitized, radioId, emptyList(), false))
        val isSelf = connectedDeviceName?.let { ChatTextMatching.caseInsensitiveEquals(sanitized, it) } == true
        if (isSelf) return Outcome.Picker(MentionPickerContext(sanitized, radioId, emptyList(), true))
        val matches = SenderContactMatcher.filter(contacts, sanitized, excludeBlocked = false)
        return if (matches.size == 1) Outcome.Navigate(matches.single())
        else Outcome.Picker(MentionPickerContext(sanitized, radioId, matches, false))
    }
}

/**
 * Body-link tap routing (iOS `MentionTapHandler` open-URL action): mention links resolve locally through
 * [MentionTapEvaluator]; other chat URLs go to [route] (the shared `ChatLinkRouter`); anything that is
 * still unclaimed opens externally only when it is an http(s) or explicit mailto link, so custom schemes, intents,
 * `javascript:` and file URLs are never handed to the system.
 */
class MessageLinkDispatcher(
    private val contacts: () -> List<ContactDTO>,
    private val radioId: () -> RadioId,
    private val connectedDeviceName: () -> String?,
    private val onMention: (MentionTapEvaluator.Outcome) -> Unit,
    private val route: (String) -> Boolean,
    private val openExternal: (String) -> Unit,
    private val shouldSuppress: () -> Boolean = { false },
) {
    /** Returns true when the tap was consumed. */
    fun dispatch(url: String): Boolean {
        if (shouldSuppress()) return true
        MentionDeeplink.name(url)?.let { name ->
            onMention(MentionTapEvaluator.evaluate(name, contacts(), connectedDeviceName(), radioId()))
            return true
        }
        if (route(url)) return true
        if (LinkOpenPolicy.isOpenable(url) || ChatMailtoPolicy.isOpenable(url)) {
            openExternal(url)
            return true
        }
        return false
    }
}

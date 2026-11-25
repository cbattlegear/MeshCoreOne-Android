// PortedFrom: MC1Tests/MessageTextThemeTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: bake theme colors into caller-classified text runs; normalization/link detection belong to WP-308.
package com.meshcoreone.android.core.designsystem

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes

data class AvatarColorInput(val name: String, val imageIdentity: Bytes? = null, val category: AvatarCategory? = null)

enum class ThemeTextRole { BODY, HASHTAG, IDENTITY }
data class ThemeTextRun(val start: Int, val end: Int, val role: ThemeTextRole, val identityName: String? = null)
data class ThemeTextColors(
    val incoming: ThemeColor,
    val outgoing: ThemeColor,
    val hashtag: ThemeColor,
    val identity: (String) -> ThemeColor,
)

fun ThemeFrame.textColors(): ThemeTextColors {
    val roles = MaterialRoles.from(this)
    return ThemeTextColors(roles.onIncomingBubble, roles.onOutgoingBubble, roles.incomingHashtag, ::identityColor)
}

fun buildThemedText(
    text: String,
    isOutgoing: Boolean,
    runs: SnapshotList<ThemeTextRun>,
    colors: ThemeTextColors,
): AnnotatedString {
    val base = if (isOutgoing) colors.outgoing else colors.incoming
    val builder = AnnotatedString.Builder(text)
    builder.addStyle(SpanStyle(color = base.toComposeColor()), 0, text.length)
    var previousEnd = 0
    for (run in runs) {
        require(run.start >= previousEnd && run.end > run.start && run.end <= text.length)
        require(run.start == 0 || !text[run.start].isLowSurrogate())
        require(run.end == text.length || !text[run.end].isLowSurrogate())
        val color = when (run.role) {
            ThemeTextRole.BODY -> base
            ThemeTextRole.HASHTAG -> if (isOutgoing) colors.outgoing else colors.hashtag
            ThemeTextRole.IDENTITY -> if (isOutgoing) colors.outgoing else colors.identity(requireNotNull(run.identityName))
        }
        builder.addStyle(SpanStyle(color = color.toComposeColor()), run.start, run.end)
        previousEnd = run.end
    }
    return builder.toAnnotatedString()
}

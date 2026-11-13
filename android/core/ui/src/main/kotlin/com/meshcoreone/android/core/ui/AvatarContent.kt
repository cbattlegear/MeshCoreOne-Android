// PortedFrom: MC1/Views/Components/ContactAvatar.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Components/NodeAvatar.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: ICU graphemes, explicitly injected bounded decoding and original native geometry.
package com.meshcoreone.android.core.ui

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshcoreone.android.core.designsystem.AvatarCategory
import com.meshcoreone.android.core.designsystem.LocalMeshTheme
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.designsystem.readableGlyph
import com.meshcoreone.android.core.designsystem.accentBorder
import com.meshcoreone.android.core.designsystem.toComposeColor
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale

fun avatarInitials(name: String): String {
    val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT)
    boundaries.setText(name)
    val graphemes = mutableListOf<String>()
    var start = boundaries.first()
    var end = boundaries.next()
    while (end != BreakIterator.DONE) {
        graphemes += name.substring(start, end)
        start = end
        end = boundaries.next()
    }
    val emoji = graphemes.firstOrNull { character ->
        val first = character.codePointAt(0)
        UCharacter.hasBinaryProperty(first, UProperty.EMOJI) &&
            (first > 0x238C || character.codePointCount(0, character.length) > 1)
    }
    if (emoji != null) return emoji
    fun first(word: String): String {
        val breaker = BreakIterator.getCharacterInstance(Locale.ROOT)
        breaker.setText(word)
        val limit = breaker.next()
        return if (limit == BreakIterator.DONE) "" else word.substring(0, limit)
    }
    val words = name.split(' ').filter { it.isNotEmpty() }
    return if (words.size >= 2) (first(words[0]) + first(words[1])).uppercase(Locale.ROOT)
        else first(name).uppercase(Locale.ROOT)
}

sealed interface AvatarImageState {
    data object Absent : AvatarImageState
    data class Ready(val image: ImageBitmap) : AvatarImageState
    data class Failed(val failure: IOException) : AvatarImageState
}

class AvatarImageCache(
    maximumBytes: Int = 32 * 1024 * 1024,
    private val reporter: UiErrorReporter = AndroidUiErrorReporter,
    private val copyDecodedBitmap: (Bitmap) -> Bitmap? = { it.copy(Bitmap.Config.ARGB_8888, false) },
) {
    private val cache = object : LruCache<Bytes, Bitmap>(maximumBytes) {
        override fun sizeOf(key: Bytes, value: Bitmap): Int = value.allocationByteCount
    }

    fun decode(data: Bytes?): AvatarImageState {
        if (data == null) return AvatarImageState.Absent
        cache.get(data)?.let { return AvatarImageState.Ready(it.asImageBitmap()) }
        return try {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(data.toByteArray()))
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > AvatarCropGeometry.DECODE_MAX_PIXEL_SIZE) {
                    val factor = AvatarCropGeometry.DECODE_MAX_PIXEL_SIZE.toDouble() / longest
                    decoder.setTargetSize(maxOf(1, (info.size.width * factor).toInt()),
                        maxOf(1, (info.size.height * factor).toInt()))
                }
            }
            val immutable = copyDecodedBitmap(decoded) ?: throw IOException("Unable to copy the decoded avatar bitmap")
            cache.put(data, immutable)
            AvatarImageState.Ready(immutable.asImageBitmap())
        } catch (failure: IOException) {
            reporter.report(failure)
            AvatarImageState.Failed(failure)
        }
    }

    fun clear() = cache.evictAll()
}

@Composable
fun ContactAvatar(
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    image: AvatarImageState = AvatarImageState.Absent,
) {
    val frame = LocalMeshTheme.current.frame
    val fill = frame.identityColor(name)
    val glyph = readableGlyph(frame.avatarGlyphColor(fill, false), fill)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val diameter = size * fontScale
    Box(modifier.size(diameter).clip(CircleShape).background(fill.toComposeColor()).accentBorder(1.5.dp, CircleShape)
        .semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
        if (image is AvatarImageState.Ready) {
            Image(image.image, null, Modifier.size(diameter), contentScale = ContentScale.Crop)
        } else {
            Text(avatarInitials(name), color = glyph.toComposeColor(),
                fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ContactAvatar(contact: ContactDTO, size: Dp, image: AvatarImageState, modifier: Modifier = Modifier) =
    ContactAvatar(contact.displayName, size, modifier, image)

@Composable
fun NodeAvatar(role: RemoteNodeRole, size: Dp, label: UiText, modifier: Modifier = Modifier) {
    val frame = LocalMeshTheme.current.frame
    val category = if (role == RemoteNodeRole.ROOM_SERVER) AvatarCategory.ROOM else AvatarCategory.REPEATER
    val fill = frame.categoryAvatarColor(category)
    val glyph = readableGlyph(frame.avatarGlyphColor(fill, frame.theme.usesCategoryAvatarOverride), fill)
    val description = uiString(label)
    val diameter = size * LocalDensity.current.fontScale.coerceAtLeast(1f)
    Box(modifier.size(diameter).clip(CircleShape).background(fill.toComposeColor()).accentBorder(1.5.dp, CircleShape)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon((if (role == RemoteNodeRole.ROOM_SERVER) MeshSymbol.ROOM else MeshSymbol.RADIO).vector,
            null, Modifier.size(diameter * 0.45f), tint = glyph.toComposeColor())
    }
}

@Composable
fun CategoryAvatar(category: AvatarCategory, size: Dp, label: UiText, modifier: Modifier = Modifier) {
    val frame = LocalMeshTheme.current.frame
    val fill = frame.categoryAvatarColor(category)
    val glyph = readableGlyph(frame.avatarGlyphColor(fill, frame.theme.usesCategoryAvatarOverride), fill)
    val description = uiString(label)
    val diameter = size * LocalDensity.current.fontScale.coerceAtLeast(1f)
    Box(modifier.size(diameter).clip(CircleShape).background(fill.toComposeColor()).accentBorder(1.5.dp, CircleShape)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(when (category) {
            AvatarCategory.CHANNEL -> MeshSymbol.HASHTAG
            AvatarCategory.REPEATER -> MeshSymbol.RADIO
            AvatarCategory.ROOM -> MeshSymbol.ROOM
        }.vector, null, Modifier.size(diameter * 0.45f), tint = glyph.toComposeColor())
    }
}

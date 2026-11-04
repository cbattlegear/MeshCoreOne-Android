// AndroidOnly: WP-218 own native decode allocations until result delivery across dispatcher cancellation.
package com.meshcoreone.android.app.content

import android.graphics.Bitmap
import com.meshcoreone.android.core.services.content.ImageDecodeOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal class DecodedBitmapOwnership {
    private val bitmaps = mutableListOf<Bitmap>()

    fun own(bitmap: Bitmap): Bitmap = bitmap.also { bitmaps.add(it) }

    fun recycle(bitmap: Bitmap) {
        bitmaps.remove(bitmap)
        bitmap.recycle()
    }

    fun recycleAll() {
        bitmaps.forEach { it.recycle() }
        bitmaps.clear()
    }
}

internal suspend fun decodeWithBitmapOwnership(
    dispatcher: CoroutineDispatcher,
    decode: suspend DecodedBitmapOwnership.() -> ImageDecodeOutcome,
): ImageDecodeOutcome {
    val ownership = DecodedBitmapOwnership()
    var delivered = false
    try {
        val result = withContext(dispatcher) { ownership.decode() }
        // withContext can discard a successful result if the caller is cancelled on return.
        delivered = result is ImageDecodeOutcome.Decoded
        return result
    } finally {
        if (!delivered) ownership.recycleAll()
    }
}

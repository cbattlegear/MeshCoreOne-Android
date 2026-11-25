// PortedFrom: MC1/Views/Chats/Reactions/EmojiProvider.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Reactions/EmojiPickerViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Reactions/RecentEmojisStore.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.feature.chats.list.ChatTextMatching
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EmojiEntry(
    val hexcode: String,
    val unicode: String,
    val label: String,
    val shortcodes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

data class EmojiItem(val id: String, val unicode: String, val label: String)

/** A picker section. [id] is an Emojibase group name or `frequent`. */
data class EmojiCategoryData(val id: String, val emojis: List<EmojiItem>)

/**
 * Seam for the emoji dataset. The iOS app bundles Emojibase; the Android catalog (Emojibase JSON asset
 * or AndroidX emoji2 `EmojiCompat` metadata) is a dependency decision deferred to a lock amendment
 * (see WP-308.md): [load] returns categories keyed by Emojibase group names, in any order.
 */
fun interface EmojiCatalogSource {
    suspend fun load(): Map<String, List<EmojiEntry>>
}

/** Persistence for the frequently used list (iOS `@AppStorage(frequentEmojis)`, JSON string array). */
interface FrequentEmojiStore {
    fun read(): List<String>
    fun write(emojis: List<String>)
}

sealed interface EmojiPickerLoad {
    data object NotLoaded : EmojiPickerLoad
    data object Loading : EmojiPickerLoad
    data object Loaded : EmojiPickerLoad
    data class Failed(val message: String?) : EmojiPickerLoad
}

data class EmojiPickerState(
    val query: String = "",
    val categories: List<EmojiCategoryData> = emptyList(),
    val load: EmojiPickerLoad = EmojiPickerLoad.NotLoaded,
)

/** The six reaction shortcuts and their MRU rule (iOS `RecentEmojisStore`). */
object RecentEmojis {
    const val MAX_RECENT = 6
    val DEFAULTS = listOf("👍", "👎", "❤️", "😂", "😮", "😢")

    fun record(current: List<String>, emoji: String): List<String> =
        (listOf(emoji) + current.filter { it != emoji }).take(MAX_RECENT)
}

/**
 * Search, frequent-first ordering and MRU for the picker. A new query cancels the previous search so a
 * slow earlier result can never overwrite a newer one.
 */
class EmojiPickerStateHolder(
    private val catalog: EmojiCatalogSource,
    private val frequent: FrequentEmojiStore,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(EmojiPickerState())
    val state: StateFlow<EmojiPickerState> = mutableState.asStateFlow()
    private var store: Map<String, List<EmojiEntry>>? = null
    private var searchJob: Job? = null

    fun load(): Job = scope.launch { refresh() }

    fun setQuery(query: String): Job {
        mutableState.update { it.copy(query = query) }
        searchJob?.cancel()
        return scope.launch { refresh() }.also { searchJob = it }
    }

    private suspend fun refresh() {
        val query = mutableState.value.query
        val loaded = ensureLoaded() ?: return
        val result = categories(loaded, query.ifEmpty { null })
        // A superseded search must not publish (the query may have changed while loading).
        if (mutableState.value.query == query) mutableState.update { it.copy(categories = result) }
    }

    private suspend fun ensureLoaded(): Map<String, List<EmojiEntry>>? {
        store?.let { return it }
        mutableState.update { it.copy(load = EmojiPickerLoad.Loading) }
        return try {
            catalog.load().also {
                store = it
                mutableState.update { state -> state.copy(load = EmojiPickerLoad.Loaded) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutableState.update { it.copy(load = EmojiPickerLoad.Failed(failure.message)) }
            null
        }
    }

    private fun categories(loaded: Map<String, List<EmojiEntry>>, query: String?): List<EmojiCategoryData> {
        val result = ArrayList<EmojiCategoryData>()
        if (query == null) {
            val used = frequent.read()
            if (used.isNotEmpty()) {
                result += EmojiCategoryData("frequent", used.mapIndexed { index, unicode -> EmojiItem("frequent-$index", unicode, "") })
            }
        }
        for (category in CATEGORY_ORDER) {
            val emojis = loaded[category] ?: continue
            val folded = query?.lowercase(Locale.ROOT)
            val filtered = if (folded == null) emojis else emojis.filter { emoji ->
                ChatTextMatching.standardContains(emoji.label, folded) ||
                    emoji.shortcodes.any { ChatTextMatching.standardContains(it, folded) } ||
                    emoji.tags.any { ChatTextMatching.standardContains(it, folded) }
            }
            if (filtered.isNotEmpty()) {
                result += EmojiCategoryData(category, filtered.map { EmojiItem("$category-${it.hexcode}", it.unicode, it.label) })
            }
        }
        return result
    }

    /** MRU insert at the front, de-duplicated, capped at [MAX_FREQUENT]. */
    fun markAsFrequentlyUsed(emoji: String) {
        frequent.write((listOf(emoji) + frequent.read().filter { it != emoji }).take(MAX_FREQUENT))
    }

    companion object {
        const val MAX_FREQUENT = 20
        val CATEGORY_ORDER = listOf("people", "nature", "foods", "activity", "places", "objects", "symbols", "flags")
    }
}

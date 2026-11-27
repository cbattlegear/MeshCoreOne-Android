// PortedFrom: MC1/Views/Chats/Reactions/EmojiPickerSheet.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Reactions/EmojiCategoryHeader.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Reactions/EmojiPickerRow.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.composer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.meshcoreone.android.core.ui.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.feature.chats.composer.EmojiPickerLoad
import com.meshcoreone.android.feature.chats.composer.EmojiPickerStateHolder

private fun categoryTitle(id: String): Int? = when (id) {
    "frequent" -> AppChatsStrings.reactionsEmojiCategoryFrequent
    "people" -> AppChatsStrings.reactionsEmojiCategoryPeople
    "nature" -> AppChatsStrings.reactionsEmojiCategoryNature
    "foods" -> AppChatsStrings.reactionsEmojiCategoryFoods
    "activity" -> AppChatsStrings.reactionsEmojiCategoryActivity
    "places" -> AppChatsStrings.reactionsEmojiCategoryPlaces
    "objects" -> AppChatsStrings.reactionsEmojiCategoryObjects
    "symbols" -> AppChatsStrings.reactionsEmojiCategorySymbols
    "flags" -> AppChatsStrings.reactionsEmojiCategoryFlags
    else -> null
}

/** Searchable, categorized emoji grid; the dataset arrives through the injected catalog seam. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerSheet(holder: EmojiPickerStateHolder, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val state by holder.state.collectAsStateWithLifecycle()
    LaunchedEffect(holder) { holder.load() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = TextFieldValue(state.query, TextRange(state.query.length)),
                onValueChange = { holder.setQuery(it.text) },
                placeholder = { Text(stringResource(AppChatsStrings.reactionsEmojiSearchPlaceholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            when (state.load) {
                EmojiPickerLoad.Loading, EmojiPickerLoad.NotLoaded -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
                is EmojiPickerLoad.Failed -> Text(
                    stringResource(AppChatsStrings.chatsErrorServicesUnavailable),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp),
                )
                EmojiPickerLoad.Loaded -> LazyVerticalGrid(GridCells.Adaptive(48.dp), Modifier.fillMaxWidth()) {
                    state.categories.forEach { category ->
                        item(key = "header-${category.id}", span = { GridItemSpan(maxLineSpan) }) {
                            categoryTitle(category.id)?.let {
                                Text(stringResource(it), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp).semantics { heading() })
                            }
                        }
                        items(category.emojis, key = { it.id }) { emoji ->
                            Box(
                                Modifier.sharedTouchTarget()
                                    .clickable(role = Role.Button) { onPick(emoji.unicode) }
                                    .semantics { contentDescription = emoji.label.ifEmpty { emoji.unicode } },
                                contentAlignment = Alignment.Center,
                            ) { Text(emoji.unicode, fontSize = 28.sp) }
                        }
                    }
                }
            }
        }
    }
}

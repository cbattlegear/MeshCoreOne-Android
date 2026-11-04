// PortedFrom: MC1/Views/Contacts/ContactDetailView.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: configuration-retained contact/editor lifetime without saving private media to instance state.
package com.meshcoreone.android.feature.nodes.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.ui.AvatarCropGeometry
import com.meshcoreone.android.core.ui.AvatarCropView
import com.meshcoreone.android.core.ui.AvatarImageCache
import com.meshcoreone.android.core.ui.AvatarImageState
import com.meshcoreone.android.core.ui.ContactAvatar
import com.meshcoreone.android.core.ui.CropSize
import com.meshcoreone.android.core.ui.cropAvatarImage
import com.meshcoreone.android.feature.nodes.detail.AvatarSaveObserver
import com.meshcoreone.android.feature.nodes.detail.AvatarProcessing
import com.meshcoreone.android.feature.nodes.detail.ContactDetailStateHolder
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Configuration-only retention: private image state never enters a Bundle or persistent store. */
internal class ContactDetailRetainedState : ViewModel() {
    private val details = mutableMapOf<String, ContactDetailLifetime>()
    private var avatarSaveObserver = AvatarSaveObserver.NONE
    val lifetimes: List<ContactDetailLifetime> get() = details.values.toList()

    fun setAvatarSaveObserver(observer: AvatarSaveObserver) {
        avatarSaveObserver = observer
        details.values.forEach { it.holder.setAvatarSaveObserver(observer) }
    }

    fun bind(entryId: String, contact: ContactDTO, dependencies: NodesFeatureDependencies): ContactDetailLifetime {
        details[entryId]?.takeIf {
            it.holder.state.value.contact.let { current ->
                current.id == contact.id && current.radioId == contact.radioId && current.publicKey == contact.publicKey
            }
        }?.let { return it }
        details[entryId]?.close()
        return ContactDetailLifetime(contact, dependencies,
            CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job])),
            avatarSaveObserver)
            .also { details[entryId] = it }
    }

    fun release(entryId: String, value: ContactDetailLifetime) {
        if (details[entryId] === value) {
            value.close()
            details.remove(entryId)
        }
    }

    override fun onCleared() {
        details.values.forEach(ContactDetailLifetime::close)
        details.clear()
    }
}

internal class ContactDetailLifetime(
    contact: ContactDTO,
    dependencies: NodesFeatureDependencies,
    val scope: CoroutineScope,
    avatarSaveObserver: AvatarSaveObserver,
) {
    val holder = ContactDetailStateHolder(
        contact,
        dependencies,
        scope,
        avatarSaveObserver = avatarSaveObserver,
    )
    var active = true
        private set
    var choosing by mutableStateOf(false)
    var picking by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var image by mutableStateOf<ImageBitmap?>(null)
    var geometry by mutableStateOf(AvatarCropGeometry(300.0, CropSize.ZERO))
    var avatarIdentity by mutableStateOf<Pair<EntityKey, Bytes>?>(null)

    fun close() {
        active = false
        scope.cancel()
        holder.onDisappear()
        image = null
        choosing = false
        picking = false
        loading = false
        avatarIdentity = null
    }
}

/** URI grants are used only for this selection; no path, persistent grant or original image is stored. */
internal object ContactAvatarMedia {
    const val MAX_INPUT_BYTES = 20 * 1024 * 1024

    suspend fun decode(resolver: ContentResolver, uri: Uri): ImageBitmap = withContext(Dispatchers.IO) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw IOException("Expected a granted content URI")
        val bytes = resolver.openInputStream(uri)?.use { readBounded(it) }
            ?: throw IOException("Unable to open the selected image")
        val cache = AvatarImageCache()
        try {
            when (val decoded = cache.decode(Bytes(bytes))) {
                is AvatarImageState.Ready -> decoded.image
                is AvatarImageState.Failed -> throw decoded.failure
                AvatarImageState.Absent -> throw IOException("The selected image is empty")
            }
        } finally {
            cache.clear()
        }
    }

    internal suspend fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_INPUT_BYTES - output.size() + 1))
            if (count < 0) break
            if (count > MAX_INPUT_BYTES - output.size()) throw IOException("Selected image exceeds the 20 MiB input limit")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    suspend fun encode(image: ImageBitmap, geometry: AvatarCropGeometry): Bytes? = withContext(Dispatchers.Default) {
        val cropped = cropAvatarImage(image, geometry).asAndroidBitmap()
        try {
            ByteArrayOutputStream().use { output ->
                if (!cropped.compress(Bitmap.CompressFormat.JPEG, (AvatarProcessing.JPEG_QUALITY * 100).toInt(), output)) {
                    null
                } else {
                    Bytes(output.toByteArray())
                }
            }
        } finally {
            cropped.recycle()
        }
    }
}

@Composable
internal fun rememberContactAvatarEditor(detail: ContactDetailLifetime): @Composable () -> Unit {
    val holder = detail.holder
    val state by holder.state.collectAsStateWithLifecycle()
    if (state.contact.type != ContactType.CHAT) return {}
    val scope = detail.scope
    val resolver = LocalContext.current.applicationContext.contentResolver
    var choosing by detail::choosing
    var picking by detail::picking
    var loading by detail::loading
    var image by detail::image
    var geometry by detail::geometry
    val cache = remember { AvatarImageCache() }
    var avatar by remember { mutableStateOf<AvatarImageState>(AvatarImageState.Absent) }
    androidx.compose.runtime.LaunchedEffect(state.contact.avatarImageData) {
        avatar = withContext(Dispatchers.IO) { cache.decode(state.contact.avatarImageData) }
    }
    DisposableEffect(holder) {
        onDispose {
            cache.clear()
        }
    }
    val receive: (Uri?) -> Unit = { uri ->
        if (detail.active && picking) {
            picking = false
            if (uri != null) {
                loading = true
                scope.launch {
                    try {
                        val decoded = ContactAvatarMedia.decode(resolver, uri)
                        image = decoded
                        geometry = AvatarCropGeometry(300.0, CropSize(decoded.width.toDouble(), decoded.height.toDouble()))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: IOException) {
                        avatarIdentity = null
                        holder.invalidAvatar()
                    } catch (error: SecurityException) {
                        avatarIdentity = null
                        holder.avatarFailure(error)
                    } finally {
                        loading = false
                    }
                }
            } else {
                avatarIdentity = null
            }
        }
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), receive)
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), receive)
    val busy = picking || loading || state.isSavingAvatar
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false; avatarIdentity = null },
            title = { Text(stringResource(C.contactsDetailAvatarChooseSource)) },
            text = {
                Column {
                    TextButton(onClick = {
                        choosing = false
                        picking = true
                        try {
                            photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        } catch (error: android.content.ActivityNotFoundException) {
                            picking = false
                            avatarIdentity = null
                            holder.avatarFailure(error)
                        } catch (error: SecurityException) {
                            picking = false
                            avatarIdentity = null
                            holder.avatarFailure(error)
                        }
                    }) { Text(stringResource(C.contactsDetailAvatarChoosePhoto)) }
                    TextButton(onClick = {
                        choosing = false
                        picking = true
                        try {
                            file.launch(arrayOf("image/*"))
                        } catch (error: android.content.ActivityNotFoundException) {
                            picking = false
                            avatarIdentity = null
                            holder.avatarFailure(error)
                        } catch (error: SecurityException) {
                            picking = false
                            avatarIdentity = null
                            holder.avatarFailure(error)
                        }
                    }) { Text(stringResource(C.contactsDetailAvatarChooseFile)) }
                    if (state.contact.avatarImageData != null) {
                        TextButton(onClick = {
                            choosing = false
                            val identity = avatarIdentity
                            avatarIdentity = null
                            if (identity != null) scope.launch { holder.removeAvatar(identity) }
                        }) { Text(stringResource(C.contactsDetailAvatarRemovePhoto)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false; avatarIdentity = null }) { Text(stringResource(C.contactsCommonCancel)) }
            },
        )
    }
    image?.let { selected ->
        AvatarCropView(
            selected, geometry, onGeometryChange = { geometry = it },
            onCancel = { image = null; avatarIdentity = null },
            onConfirm = { chosen ->
                image = null
                val identity = avatarIdentity
                avatarIdentity = null
                if (identity != null) {
                    holder.launchAvatarSave(identity) { ContactAvatarMedia.encode(selected, chosen) }
                }
            },
        )
    }
    // Keep launchers, work and dialogs in the screen lifetime, not a recycled LazyColumn row.
    return {
        Column {
            ContactAvatar(state.contact, 96.dp, avatar)
            TextButton(onClick = {
                loading = true
                avatarIdentity = null
                try {
                    val identity = holder.captureAvatarIdentity()
                    if (detail.active && identity != null) {
                        avatarIdentity = identity
                        choosing = true
                    }
                } finally {
                    loading = false
                }
            }, enabled = !busy) {
                Text(stringResource(C.contactsDetailAvatarChooseSource))
            }
            if (loading || state.isSavingAvatar) {
                CircularProgressIndicator()
                if (state.isSavingAvatar) {
                    Text(stringResource(C.contactsDetailAvatarSavingAnnouncement),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
    }
}

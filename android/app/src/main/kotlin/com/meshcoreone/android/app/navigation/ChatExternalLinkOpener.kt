// AndroidOnly: WP-306 Native mailto action for chat taps without widening web preview/network policy.
package com.meshcoreone.android.app.navigation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import com.meshcoreone.android.feature.chats.composer.LinkOpenPolicy
import com.meshcoreone.android.feature.chats.list.ChatMailtoPolicy

internal class ChatExternalLinkOpener(
    private val context: Context,
    private val openWeb: (String) -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    fun open(url: String) {
        try {
            when {
                ChatMailtoPolicy.isOpenable(url) -> {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(url).normalizeScheme())
                    val activityBacked = generateSequence(context) { current ->
                        (current as? ContextWrapper)?.baseContext?.takeUnless { it === current }
                    }.any { it is Activity }
                    if (!activityBacked) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                LinkOpenPolicy.isOpenable(url) -> openWeb(url)
                else -> onFailure(IllegalArgumentException("Unsupported chat link"))
            }
        } catch (failure: ActivityNotFoundException) {
            onFailure(failure)
        } catch (failure: SecurityException) {
            onFailure(failure)
        }
    }
}

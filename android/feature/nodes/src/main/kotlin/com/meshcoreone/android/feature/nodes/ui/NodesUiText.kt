// AndroidOnly: WP-311 Resolves typed nodes messages with the active locale/configuration.
package com.meshcoreone.android.feature.nodes.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.meshcoreone.android.feature.nodes.deps.NodesMessage

@Composable
internal fun nodesString(message: NodesMessage): String {
    LocalConfiguration.current
    val resources = LocalContext.current.resources
    fun resolve(value: NodesMessage): String = when (value) {
        is NodesMessage.Text -> value.value
        is NodesMessage.Joined -> value.parts.joinToString(value.separator, transform = ::resolve)
        is NodesMessage.Resource -> resources.getString(
            value.id,
            *value.args.map { argument ->
                if (argument is NodesMessage) resolve(argument) else argument
            }.toTypedArray(),
        )
    }
    return resolve(message)
}

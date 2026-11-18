// PortedFrom: MC1/State/NavigationCoordinator.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: Versioned native restoration retains public stacks, never identity-bearing DTOs or pending links.
package com.meshcoreone.android.app.navigation

import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.snapshotMap

object NavigationSavedState {
    const val KEY = "wp302.navigation"
    private const val VERSION = "wp302.v1"

    fun encode(state: NavigationState): ArrayList<String> = ArrayList<String>().apply {
        add("$VERSION|${state.selectedTab.name}|${state.nextEntryId}")
        state.stacks.forEach { (tab, stack) ->
            stack.forEach { entry ->
                val token = when (val destination = entry.destination) {
                    is NavigationDestination.Root -> "root|${destination.tab.name}"
                    is NavigationDestination.Tool -> if (destination.selection.requiresRadio) "private|"
                        else "tool|${destination.selection.sourceName}"
                    is NavigationDestination.Setting -> if (destination.selection.requiresDevice) "private|"
                        else "setting|${destination.selection.sourceName}"
                    is NavigationDestination.Auxiliary -> "auxiliary|${destination.feature.stableId}"
                    else -> "private|"
                }
                add("${tab.name}|${entry.id}|$token")
            }
        }
    }

    fun restore(tokens: List<String>): NavigationState {
        fun invalid() = NavigationState(failure = NavigationFailure.InvalidSavedState)
        val header = tokens.firstOrNull()?.split('|') ?: return invalid()
        if (header.size != 3 || header[0] != VERSION) return invalid()
        val selected = AppTab.entries.find { it.name == header[1] } ?: return invalid()
        val nextId = header[2].toLongOrNull()?.takeIf { it >= 5 && it < Long.MAX_VALUE } ?: return invalid()
        val stacks = AppTab.entries.associateWith { mutableListOf<NavigationEntry>() }
        val ids = mutableSetOf<Long>()
        var privateCount = 0
        for (token in tokens.drop(1)) {
            val fields = token.split('|')
            if (fields.size != 4) return invalid()
            val tab = AppTab.entries.find { it.name == fields[0] } ?: return invalid()
            val id = fields[1].toLongOrNull()?.takeIf { it >= 0 && it < nextId } ?: return invalid()
            if (!ids.add(id)) return invalid()
            if (fields[2] == "private") {
                if (fields[3].isNotEmpty() || stacks.getValue(tab).isEmpty()) return invalid()
                privateCount++
                continue
            }
            val destination = when (fields[2]) {
                "root" -> if (fields[3] == tab.name && stacks.getValue(tab).isEmpty()) NavigationDestination.Root(tab)
                    else return invalid()
                "tool" -> {
                    if (tab != AppTab.TOOLS) return invalid()
                    val tool = ToolSelection.entries.find { it.sourceName == fields[3] && !it.requiresRadio } ?: return invalid()
                    NavigationDestination.Tool(tool)
                }
                "setting" -> {
                    if (tab != AppTab.SETTINGS) return invalid()
                    val setting = SettingsDetail.entries.find { it.sourceName == fields[3] && !it.requiresDevice } ?: return invalid()
                    NavigationDestination.Setting(setting)
                }
                "auxiliary" -> {
                    val feature = FeatureId.entries.find { it.stableId == fields[3] && it.tab == null } ?: return invalid()
                    NavigationDestination.Auxiliary(feature)
                }
                else -> return invalid()
            }
            if (stacks.getValue(tab).isEmpty() && destination !is NavigationDestination.Root) return invalid()
            stacks.getValue(tab).add(NavigationEntry(id, destination, tab))
        }
        if (stacks.any { (_, entries) -> entries.isEmpty() }) return invalid()
        val selectedStack = stacks.getValue(selected)
        return NavigationState(
            selectedTab = selected,
            stacks = stacks.mapValues { (_, stack) -> stack.snapshot() }.snapshotMap(),
            nextEntryId = nextId,
            tabBarVisible = selectedStack.size == 1,
            selectedSetting = (stacks.getValue(AppTab.SETTINGS).last().destination as? NavigationDestination.Setting)?.selection,
            selectedTool = (stacks.getValue(AppTab.TOOLS).last().destination as? NavigationDestination.Tool)?.selection,
            failure = if (privateCount > 0) NavigationFailure.PrivateSelectionNotRestored(privateCount) else null,
        )
    }
}

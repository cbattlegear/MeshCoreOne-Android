// PortedFrom: MC1/Views/Tools/ToolSelection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// HistoricalPortedFrom: MC1/Views/Tools/ToolsContentColumn.swift@db14559b39d32322b06477c6ae676112f583db50
// HistoricalPortedFrom: MC1/Views/Tools/ToolsDetailColumn.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/ToolsView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Tools/ToolDestinationView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Selection rules only; the list, split columns and destination views are WP-314 UI work.
package com.meshcoreone.android.feature.tools.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The diagnostic tools, in list order (source `ToolSelection.allCases`). */
enum class ToolSelection {
    TRACE_PATH,
    LINE_OF_SIGHT,
    RX_LOG,
    NOISE_FLOOR,
    NODE_DISCOVERY,
    CLI,
    ;

    /** Line of Sight analyses offline; every other tool needs a connected radio. */
    val requiresRadio: Boolean get() = this != LINE_OF_SIGHT

    /** Tools that collapse a wide layout's sidebar to reclaim width. */
    val prefersCollapsedSidebar: Boolean get() = this == LINE_OF_SIGHT || this == TRACE_PATH
}

/** Result of a selection change in the two-pane layout. */
data class ToolSelectionChange(val selected: ToolSelection?, val animated: Boolean)

/**
 * Two-pane tools selection (the source kept it on `NavigationCoordinator.selectedTool`, WP-302).
 * Line of Sight swaps the content column for its panel, so entering or leaving it is unanimated.
 */
class ToolsNavigationState(initial: ToolSelection? = null) {
    private val mutableSelected = MutableStateFlow(initial)
    val selectedTool: StateFlow<ToolSelection?> = mutableSelected.asStateFlow()

    /** The content column shows the Line of Sight panel instead of the tool list. */
    val showsLineOfSightPanel: Boolean get() = mutableSelected.value == ToolSelection.LINE_OF_SIGHT

    fun select(tool: ToolSelection?): ToolSelectionChange {
        val togglesPanel = tool == ToolSelection.LINE_OF_SIGHT || mutableSelected.value == ToolSelection.LINE_OF_SIGHT
        mutableSelected.value = tool
        return ToolSelectionChange(tool, animated = !togglesPanel)
    }

    /** The detail column is titled by the tool, except Line of Sight (own chrome) and no selection. */
    val detailShowsTitle: Boolean
        get() = mutableSelected.value.let { it != null && it != ToolSelection.LINE_OF_SIGHT }

    /** No selection shows the "select a tool" placeholder. */
    val showsPlaceholder: Boolean get() = mutableSelected.value == null
}

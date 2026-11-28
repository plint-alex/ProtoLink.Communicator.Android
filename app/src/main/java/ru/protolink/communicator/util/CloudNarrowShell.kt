package ru.protolink.communicator.util

/**
 * E9h pure helpers: narrow Cloud shell (&lt;700dp) shows Folders tree XOR contents.
 * Wide (≥700) keeps E9e two-panel. Parity with Win CloudView.CompactBreakpoint = 700.
 */
object CloudNarrowShell {
    const val COMPACT_BREAKPOINT_DP = 700

    enum class Panel {
        Tree,
        Contents
    }

    /** Narrow = shell width strictly below 700 logical/dp px. */
    fun isNarrow(widthDp: Float): Boolean = widthDp < COMPACT_BREAKPOINT_DP

    fun isNarrow(widthDp: Int): Boolean = widthDp < COMPACT_BREAKPOINT_DP

    /** Cold open / return-to-tree visible panel. */
    fun coldOpenPanel(): Panel = Panel.Tree

    fun afterOpenFolderFromTree(): Panel = Panel.Contents

    /** Locations / mapped / list open while narrow → show contents. */
    fun afterOpenBrowseTarget(): Panel = Panel.Contents

    fun afterBackToTree(): Panel = Panel.Tree

    /**
     * Whether the Folders tree column/panel should paint.
     * Wide: always when non-empty. Narrow: only when stack is on Tree.
     */
    fun showTreePanel(isNarrow: Boolean, panel: Panel, hasTreeRows: Boolean): Boolean {
        if (!hasTreeRows) return false
        return if (isNarrow) panel == Panel.Tree else true
    }

    /**
     * Whether the contents/details panel should paint.
     * Wide: always. Narrow with tree: only when stack is on Contents.
     * Narrow without tree: contents only (E9d empty-tree).
     */
    fun showContentsPanel(isNarrow: Boolean, panel: Panel, hasTreeRows: Boolean): Boolean {
        if (!isNarrow) return true
        if (!hasTreeRows) return true
        return panel == Panel.Contents
    }

    fun shouldShowBackToTree(isNarrow: Boolean, panel: Panel, hasTreeRows: Boolean): Boolean =
        isNarrow && hasTreeRows && panel == Panel.Contents

    fun shouldHandleSystemBackToTree(isNarrow: Boolean, panel: Panel, hasTreeRows: Boolean): Boolean =
        shouldShowBackToTree(isNarrow, panel, hasTreeRows)
}

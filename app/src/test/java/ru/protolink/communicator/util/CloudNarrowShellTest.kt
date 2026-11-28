package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** E9h narrow single-panel XOR / stack helpers. */
class CloudNarrowShellTest {

    @Test
    fun isNarrow_below700_true_atOrAbove_false() {
        assertThat(CloudNarrowShell.isNarrow(699)).isTrue()
        assertThat(CloudNarrowShell.isNarrow(699f)).isTrue()
        assertThat(CloudNarrowShell.isNarrow(700)).isFalse()
        assertThat(CloudNarrowShell.isNarrow(700f)).isFalse()
        assertThat(CloudNarrowShell.isNarrow(1200)).isFalse()
        assertThat(CloudNarrowShell.COMPACT_BREAKPOINT_DP).isEqualTo(700)
    }

    @Test
    fun coldOpen_and_stack_transitions() {
        assertThat(CloudNarrowShell.coldOpenPanel()).isEqualTo(CloudNarrowShell.Panel.Tree)
        assertThat(CloudNarrowShell.afterOpenFolderFromTree())
            .isEqualTo(CloudNarrowShell.Panel.Contents)
        assertThat(CloudNarrowShell.afterOpenBrowseTarget())
            .isEqualTo(CloudNarrowShell.Panel.Contents)
        assertThat(CloudNarrowShell.afterBackToTree())
            .isEqualTo(CloudNarrowShell.Panel.Tree)
    }

    @Test
    fun narrow_xor_tree_or_contents_never_both() {
        val tree = CloudNarrowShell.Panel.Tree
        val contents = CloudNarrowShell.Panel.Contents

        assertThat(CloudNarrowShell.showTreePanel(true, tree, hasTreeRows = true)).isTrue()
        assertThat(CloudNarrowShell.showContentsPanel(true, tree, hasTreeRows = true)).isFalse()

        assertThat(CloudNarrowShell.showTreePanel(true, contents, hasTreeRows = true)).isFalse()
        assertThat(CloudNarrowShell.showContentsPanel(true, contents, hasTreeRows = true)).isTrue()
    }

    @Test
    fun wide_shows_both_when_tree_present() {
        val tree = CloudNarrowShell.Panel.Tree
        assertThat(CloudNarrowShell.showTreePanel(false, tree, hasTreeRows = true)).isTrue()
        assertThat(CloudNarrowShell.showContentsPanel(false, tree, hasTreeRows = true)).isTrue()
        assertThat(CloudNarrowShell.showTreePanel(false, CloudNarrowShell.Panel.Contents, true))
            .isTrue()
        assertThat(CloudNarrowShell.showContentsPanel(false, CloudNarrowShell.Panel.Contents, true))
            .isTrue()
    }

    @Test
    fun empty_tree_contents_only_narrow_and_wide() {
        assertThat(CloudNarrowShell.showTreePanel(true, CloudNarrowShell.Panel.Tree, false))
            .isFalse()
        assertThat(CloudNarrowShell.showContentsPanel(true, CloudNarrowShell.Panel.Tree, false))
            .isTrue()
        assertThat(CloudNarrowShell.showTreePanel(false, CloudNarrowShell.Panel.Tree, false))
            .isFalse()
        assertThat(CloudNarrowShell.showContentsPanel(false, CloudNarrowShell.Panel.Tree, false))
            .isTrue()
    }

    @Test
    fun back_to_tree_only_on_narrow_contents_with_tree() {
        assertThat(
            CloudNarrowShell.shouldShowBackToTree(true, CloudNarrowShell.Panel.Contents, true)
        ).isTrue()
        assertThat(
            CloudNarrowShell.shouldHandleSystemBackToTree(
                true,
                CloudNarrowShell.Panel.Contents,
                true
            )
        ).isTrue()
        assertThat(
            CloudNarrowShell.shouldShowBackToTree(true, CloudNarrowShell.Panel.Tree, true)
        ).isFalse()
        assertThat(
            CloudNarrowShell.shouldShowBackToTree(false, CloudNarrowShell.Panel.Contents, true)
        ).isFalse()
        assertThat(
            CloudNarrowShell.shouldShowBackToTree(true, CloudNarrowShell.Panel.Contents, false)
        ).isFalse()
    }
}

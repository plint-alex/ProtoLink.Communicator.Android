package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** AT-ND-1…4 offline (parity with Windows NotesDeleteNavigateTests). */
class NotesDeleteNavigateTest {

    @Test
    fun resolveNext_prevSibling_whenOpenLeafDeleted() {
        val next = NotesDeleteNavigate.resolveNextRelativePath(
            deletedRelativePath = "B",
            openRelativePath = "B",
            siblingFolderNamesUnderParent = listOf("A", "B")
        )
        assertThat(next).isEqualTo("A")
    }

    @Test
    fun resolveNext_parent_whenSoleChildDeleted() {
        val next = NotesDeleteNavigate.resolveNextRelativePath(
            deletedRelativePath = "Parent/Only",
            openRelativePath = "Parent/Only",
            siblingFolderNamesUnderParent = listOf("Only")
        )
        assertThat(next).isEqualTo("Parent")
    }

    @Test
    fun resolveNext_root_whenSoleChildUnderRootDeleted() {
        val next = NotesDeleteNavigate.resolveNextRelativePath(
            deletedRelativePath = "Only",
            openRelativePath = "Only",
            siblingFolderNamesUnderParent = listOf("Only")
        )
        assertThat(next).isEqualTo("")
    }

    @Test
    fun resolveNext_unchanged_whenNonOpenSiblingDeleted() {
        val next = NotesDeleteNavigate.resolveNextRelativePath(
            deletedRelativePath = "A",
            openRelativePath = "B",
            siblingFolderNamesUnderParent = listOf("A", "B")
        )
        assertThat(next).isEqualTo("B")
    }

    @Test
    fun resolveNext_ancestorDelete_opensPrevSiblingOfAncestor() {
        val next = NotesDeleteNavigate.resolveNextRelativePath(
            deletedRelativePath = "B",
            openRelativePath = "B/Nested",
            siblingFolderNamesUnderParent = listOf("A", "B")
        )
        assertThat(next).isEqualTo("A")
    }

    @Test
    fun resolveNext_firstSibling_usesNextAsNearest() {
        val next = NotesDeleteNavigate.resolveNextRelativePath(
            deletedRelativePath = "A",
            openRelativePath = "A",
            siblingFolderNamesUnderParent = listOf("A", "B")
        )
        assertThat(next).isEqualTo("B")
    }

    @Test
    fun isOpenPageAffected_selfAndDescendant() {
        assertThat(NotesDeleteNavigate.isOpenPageAffected("A/B", "A/B")).isTrue()
        assertThat(NotesDeleteNavigate.isOpenPageAffected("A/B/C", "A/B")).isTrue()
        assertThat(NotesDeleteNavigate.isOpenPageAffected("A/B", "A/C")).isFalse()
        assertThat(NotesDeleteNavigate.isOpenPageAffected(null, "A")).isFalse()
    }
}

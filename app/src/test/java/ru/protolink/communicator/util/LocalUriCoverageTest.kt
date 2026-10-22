package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** E6 / AT-MAP-4 Notes root covered by mapping local root. */
class LocalUriCoverageTest {
    @Test
    fun equalPaths_covered() {
        val root = """C:\Notes"""
        assertThat(LocalUriCoverage.isCoveredByAnyMappingRoot(root, listOf(root))).isTrue()
    }

    @Test
    fun nestedUnderMapping_covered() {
        val notes = """C:\Maps\MyNotesFolder\work"""
        val mapping = """C:\Maps\MyNotesFolder"""
        assertThat(LocalUriCoverage.isCoveredByAnyMappingRoot(notes, listOf(mapping))).isTrue()
    }

    @Test
    fun siblingNotCovered() {
        val notes = """C:\OtherNotes"""
        val mapping = """C:\Maps"""
        assertThat(LocalUriCoverage.isCoveredByAnyMappingRoot(notes, listOf(mapping))).isFalse()
    }

    @Test
    fun mappingNestedUnderNotes_notCovered() {
        val notes = """C:\Notes"""
        val nestedMap = """C:\Notes\Sub"""
        assertThat(LocalUriCoverage.isCoveredByAnyMappingRoot(notes, listOf(nestedMap))).isFalse()
    }

    @Test
    fun blankOrEmpty_notCovered() {
        assertThat(LocalUriCoverage.isCoveredByAnyMappingRoot(null, listOf("""C:\Maps"""))).isFalse()
        assertThat(LocalUriCoverage.isCoveredByAnyMappingRoot("  ", listOf("""C:\Maps"""))).isFalse()
        assertThat(LocalUriCoverage.isCoveredByAnyMappingRoot("""C:\Notes""", emptyList())).isFalse()
    }

    @Test
    fun equalSafTreeUris_covered() {
        val uri =
            "content://com.android.externalstorage.documents/tree/primary%3AMyNotesFolder"
        assertThat(LocalUriCoverage.isCoveredByAnyMappingRoot(uri, listOf(uri))).isTrue()
    }

    @Test
    fun nestedSafDocumentUnderTree_covered() {
        val tree =
            "content://com.android.externalstorage.documents/tree/primary%3AMyNotesFolder"
        val nested =
            "content://com.android.externalstorage.documents/tree/primary%3AMyNotesFolder%2Fwork"
        assertThat(LocalUriCoverage.isCoveredByAnyMappingRoot(nested, listOf(tree))).isTrue()
    }

    @Test
    fun differentSafTrees_notCovered() {
        val notes =
            "content://com.android.externalstorage.documents/tree/primary%3ANotes"
        val mapping =
            "content://com.android.externalstorage.documents/tree/primary%3AOther"
        assertThat(LocalUriCoverage.isCoveredByAnyMappingRoot(notes, listOf(mapping))).isFalse()
    }

    @Test
    fun relativeUnder_equalFilePath_empty() {
        val root = """C:\Notes"""
        assertThat(LocalUriCoverage.relativeUnder(root, root)).isEqualTo("")
    }

    @Test
    fun relativeUnder_nestedFilePath() {
        val root = """C:\Maps\MyNotesFolder"""
        val nested = """C:\Maps\MyNotesFolder\work"""
        assertThat(LocalUriCoverage.relativeUnder(nested, root)).isEqualTo("work")
    }

    @Test
    fun relativeUnder_equalSaf_empty() {
        val uri =
            "content://com.android.externalstorage.documents/tree/primary%3AMyNotesFolder"
        assertThat(LocalUriCoverage.relativeUnder(uri, uri)).isEqualTo("")
    }
}

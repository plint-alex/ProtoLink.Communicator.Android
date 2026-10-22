package ru.protolink.communicator.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** AT-N10-style offline enumerator checks for Android E5/E5a (AT-N11). */
class NotesSiblingFilesTest {

    @Test
    fun listSiblingFiles_excludesIndexAndDirs_includesDirectFilesOnly() {
        // Direct children of the open folder only (SAF listChildren). Nested b.txt under sub/
        // never appears here — E5a: open the child note for its own Folder files.
        val children = listOf(
            NotesSiblingFiles.ChildEntry("index.html", isDirectory = false, documentId = "1", uri = "u1"),
            NotesSiblingFiles.ChildEntry("Index.HTM", isDirectory = false, documentId = "2", uri = "u2"),
            NotesSiblingFiles.ChildEntry("a.pdf", isDirectory = false, documentId = "3", uri = "u3"),
            NotesSiblingFiles.ChildEntry("sub", isDirectory = true, documentId = "4", uri = "u4")
        )
        val files = NotesSiblingFiles.listSiblingFiles(children)
        assertThat(files).hasSize(1)
        assertThat(files[0].name).isEqualTo("a.pdf")
        assertThat(files[0].uri).isEqualTo("u3")
        assertThat(files.none { NotesSiblingFiles.isNotesOwnedFileName(it.name) }).isTrue()
        assertThat(files.none { it.name == "sub" }).isTrue()
    }

    @Test
    fun listSiblingFiles_onlyOwnedIndex_returnsEmpty() {
        val children = listOf(
            NotesSiblingFiles.ChildEntry("index.html", isDirectory = false)
        )
        assertThat(NotesSiblingFiles.listSiblingFiles(children)).isEmpty()
    }

    @Test
    fun listSiblingFiles_onlyDirsAndIndex_returnsEmpty() {
        val children = listOf(
            NotesSiblingFiles.ChildEntry("index.html", isDirectory = false),
            NotesSiblingFiles.ChildEntry("child-note", isDirectory = true),
            NotesSiblingFiles.ChildEntry("other", isDirectory = true)
        )
        assertThat(NotesSiblingFiles.listSiblingFiles(children)).isEmpty()
    }

    @Test
    fun listSiblingFiles_emptyOrMissing_returnsEmptyWithoutThrow() {
        assertThat(NotesSiblingFiles.listSiblingFiles(emptyList())).isEmpty()
    }

    @Test
    fun isNotesOwnedFileName_caseInsensitive() {
        assertThat(NotesSiblingFiles.isNotesOwnedFileName("index.html")).isTrue()
        assertThat(NotesSiblingFiles.isNotesOwnedFileName("INDEX.HTML")).isTrue()
        assertThat(NotesSiblingFiles.isNotesOwnedFileName("index.htm")).isTrue()
        assertThat(NotesSiblingFiles.isNotesOwnedFileName("Index.HTM")).isTrue()
        assertThat(NotesSiblingFiles.isNotesOwnedFileName("a.pdf")).isFalse()
        assertThat(NotesSiblingFiles.isNotesOwnedFileName("index.html.bak")).isFalse()
        assertThat(NotesSiblingFiles.isNotesOwnedFileName(null)).isFalse()
        assertThat(NotesSiblingFiles.isNotesOwnedFileName("")).isFalse()
    }
}

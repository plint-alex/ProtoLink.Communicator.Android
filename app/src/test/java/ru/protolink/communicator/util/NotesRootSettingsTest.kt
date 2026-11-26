package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.protolink.communicator.data.AppSettings

/** E11 — migrate / select / neighbor for Notes SAF multi-root (AT-NMR offline). */
class NotesRootSettingsTest {

    private val a = "content://com.android.externalstorage.documents/tree/primary%3ANotesA"
    private val b = "content://com.android.externalstorage.documents/tree/primary%3ANotesB"
    private val aSlash = "$a/"

    @Test
    fun resolveRoots_legacySingleUri_becomesOneItemList() {
        val settings = AppSettings(notesRootUri = a)
        val roots = NotesRootSettings.resolveRoots(settings)
        assertThat(roots).hasSize(1)
        assertThat(NotesRootSettings.urisEqual(roots[0], a)).isTrue()
    }

    @Test
    fun resolveRoots_prefersNotesRootUris_overLegacy() {
        val settings = AppSettings(
            notesRootUri = "content://tree/legacy",
            notesRootUris = listOf(a, b)
        )
        val roots = NotesRootSettings.resolveRoots(settings)
        assertThat(roots).hasSize(2)
        assertThat(NotesRootSettings.urisEqual(roots[0], a)).isTrue()
        assertThat(NotesRootSettings.urisEqual(roots[1], b)).isTrue()
    }

    @Test
    fun resolveRoots_dedupesNormalizedUris() {
        val settings = AppSettings(notesRootUris = listOf(aSlash, a, b))
        val roots = NotesRootSettings.resolveRoots(settings)
        assertThat(roots).hasSize(2)
    }

    @Test
    fun resolveSelected_usesLastSelected_whenInList() {
        val settings = AppSettings(
            notesRootUris = listOf(a, b),
            lastSelectedNotesRootUri = "$b/",
            notesRootUri = a
        )
        val selected = NotesRootSettings.resolveSelected(settings)
        assertThat(NotesRootSettings.urisEqual(selected, b)).isTrue()
    }

    @Test
    fun write_mirrorsSelectedToLegacyNotesRootUri() {
        val settings = AppSettings()
        val written = NotesRootSettings.write(settings, listOf(a, b), b)
        assertThat(written.notesRootUris).hasSize(2)
        assertThat(NotesRootSettings.urisEqual(written.lastSelectedNotesRootUri, b)).isTrue()
        assertThat(NotesRootSettings.urisEqual(written.notesRootUri, b)).isTrue()
    }

    @Test
    fun preferNeighborAfterRemove_prefersPrevious() {
        val roots = listOf(a, b, "content://tree/c")
        assertThat(NotesRootSettings.preferNeighborAfterRemove(roots, 1)).isEqualTo(a)
        assertThat(NotesRootSettings.preferNeighborAfterRemove(roots, 0)).isEqualTo(b)
        assertThat(NotesRootSettings.preferNeighborAfterRemove(listOf(a), 0)).isNull()
    }

    @Test
    fun needsPersistMigration_trueForLegacyOnly() {
        val legacy = AppSettings(notesRootUri = a)
        assertThat(NotesRootSettings.needsPersistMigration(legacy)).isTrue()
        val multi = NotesRootSettings.write(AppSettings(), listOf(a), a)
        assertThat(NotesRootSettings.needsPersistMigration(multi)).isFalse()
    }

    @Test
    fun cue_falseWhenCoveredByMapping() {
        assertThat(LocalUriCoverage.isCoveredByAnyMappingRoot(a, listOf(a))).isTrue()
        assertThat(MapLocalFolderUi.showNotesNudge(a, listOf(a))).isFalse()
        assertThat(MapLocalFolderUi.showNotesNudge(a, listOf(b))).isTrue()
    }
}

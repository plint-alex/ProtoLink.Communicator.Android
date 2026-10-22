package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.protolink.communicator.data.CloudSyncMappingDto
import ru.protolink.communicator.sync.model.SyncItemMeta

/** E2 / AC-NS-1 — Notes path → Cloud RemoteId gate. */
class NoteRemoteIdResolverTest {
    private val cloudId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val mappingId = NoteRemoteIdResolver.mappingIdOf(cloudId)
    private val notesRoot = """C:\Notes"""
    private val mapping = CloudSyncMappingDto(cloudId, notesRoot, "Notes")

    private fun folder(rel: String, remoteId: String) = SyncItemMeta(
        mappingId = mappingId,
        remoteId = remoteId,
        parentRemoteId = cloudId,
        relativePath = rel,
        isFolder = true,
        sizeBytes = 0
    )

    @Test
    fun equalNotesAndMapping_childFolder_resolves() {
        val noteId = "11111111-1111-1111-1111-111111111111"
        val items = listOf(folder("work", noteId))
        val id = NoteRemoteIdResolver.resolveFrom(
            notesRootUri = notesRoot,
            noteRelativePath = "work",
            mappings = listOf(mapping),
            items = items
        )
        assertThat(id).isEqualTo(noteId)
    }

    @Test
    fun equalNotesAndMapping_root_returnsCloudFolderId() {
        val id = NoteRemoteIdResolver.resolveFrom(
            notesRootUri = notesRoot,
            noteRelativePath = "",
            mappings = listOf(mapping),
            items = emptyList()
        )
        assertThat(id).isEqualTo(cloudId)
    }

    @Test
    fun unsyncedChild_null() {
        val id = NoteRemoteIdResolver.resolveFrom(
            notesRootUri = notesRoot,
            noteRelativePath = "missing",
            mappings = listOf(mapping),
            items = listOf(folder("work", "11111111-1111-1111-1111-111111111111"))
        )
        assertThat(id).isNull()
    }

    @Test
    fun unmappedNotes_null() {
        val id = NoteRemoteIdResolver.resolveFrom(
            notesRootUri = """C:\OtherNotes""",
            noteRelativePath = "work",
            mappings = listOf(mapping),
            items = listOf(folder("work", "11111111-1111-1111-1111-111111111111"))
        )
        assertThat(id).isNull()
    }

    @Test
    fun notesNestedUnderMapping_joinsPrefix() {
        val mapRoot = """C:\Maps\MyNotesFolder"""
        val nestedNotes = """C:\Maps\MyNotesFolder\work"""
        val m = CloudSyncMappingDto(cloudId, mapRoot, "Maps")
        val noteId = "33333333-3333-3333-3333-333333333333"
        val items = listOf(folder("work/page", noteId))
        val id = NoteRemoteIdResolver.resolveFrom(
            notesRootUri = nestedNotes,
            noteRelativePath = "page",
            mappings = listOf(m),
            items = items
        )
        assertThat(id).isEqualTo(noteId)
    }

    @Test
    fun mappingNestedUnderNotes_stripsPrefix() {
        val notes = """C:\Notes"""
        val mapRoot = """C:\Notes\Shared"""
        val m = CloudSyncMappingDto(cloudId, mapRoot, "Shared")
        val noteId = "44444444-4444-4444-4444-444444444444"
        val items = listOf(folder("page", noteId))
        val id = NoteRemoteIdResolver.resolveFrom(
            notesRootUri = notes,
            noteRelativePath = "Shared/page",
            mappings = listOf(m),
            items = items
        )
        assertThat(id).isEqualTo(noteId)
    }

    @Test
    fun safEqualTree_usesNoteRelativePath() {
        val uri =
            "content://com.android.externalstorage.documents/tree/primary%3AMyNotesFolder"
        val m = CloudSyncMappingDto(cloudId, uri, "Notes")
        val noteId = "55555555-5555-5555-5555-555555555555"
        val items = listOf(folder("inbox", noteId))
        val id = NoteRemoteIdResolver.resolveFrom(
            notesRootUri = uri,
            noteRelativePath = "inbox",
            mappings = listOf(m),
            items = items
        )
        assertThat(id).isEqualTo(noteId)
    }
}

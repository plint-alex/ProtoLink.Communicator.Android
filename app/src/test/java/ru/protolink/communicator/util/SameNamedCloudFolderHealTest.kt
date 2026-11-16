package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * FAIL class P1-20261117: Android mapped duplicate MyNotesFolder 0bc549dd (newer empty films)
 * while Win/AC use older 898c1453.
 */
class SameNamedCloudFolderHealTest {

    private val winId = "898c1453-4034-4b9f-96f6-430b4ceca476"
    private val dupId = "0bc549dd-c880-42df-af32-4f3c340ba970"
    private val local = "content://com.android.externalstorage.documents/tree/primary%3AMyNotesFolder"

    private val siblings = listOf(
        SameNamedCloudFolderHeal.Sibling(dupId, "MyNotesFolder", "2026-11-15T10:00:00"),
        SameNamedCloudFolderHeal.Sibling(winId, "MyNotesFolder", "2026-10-29T18:49:32.7901832"),
        SameNamedCloudFolderHeal.Sibling("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Other", "2026-01-01T00:00:00")
    )

    @Test
    fun preferExistingSameName_picksOldestMyNotesFolder() {
        val pick = SameNamedCloudFolderHeal.preferExistingSameName("MyNotesFolder", siblings)
        assertThat(pick!!.id).isEqualTo(winId)
    }

    @Test
    fun preferExistingSameName_noMatch_returnsNull() {
        assertThat(
            SameNamedCloudFolderHeal.preferExistingSameName("BrandNew", siblings)
        ).isNull()
    }

    @Test
    fun canonicalIdIfMappedIsDuplicate_remapsNewerDupToWin() {
        assertThat(
            SameNamedCloudFolderHeal.canonicalIdIfMappedIsDuplicate(dupId, "MyNotesFolder", siblings)
        ).isEqualTo(winId)
    }

    @Test
    fun canonicalIdIfMappedIsDuplicate_alreadyCanonical_null() {
        assertThat(
            SameNamedCloudFolderHeal.canonicalIdIfMappedIsDuplicate(winId, "MyNotesFolder", siblings)
        ).isNull()
    }

    @Test
    fun remapsAndApply_healsAndroidDupMapping() {
        val mappings = listOf(
            SameNamedCloudFolderHeal.MappingRow(dupId, local, "MyNotesFolder")
        )
        val parent = "cloud-root-id"
        val remaps = SameNamedCloudFolderHeal.remapsForDuplicateNames(
            mappings = mappings,
            siblingsByParent = mapOf(parent to siblings),
            parentIdByMappedId = mapOf(dupId to parent)
        )
        assertThat(remaps).hasSize(1)
        assertThat(remaps[0].fromId).isEqualTo(dupId)
        assertThat(remaps[0].toId).isEqualTo(winId)

        val healed = SameNamedCloudFolderHeal.applyRemaps(mappings, remaps)
        assertThat(healed).hasSize(1)
        assertThat(healed[0].cloudFolderId).isEqualTo(winId)
        assertThat(healed[0].localPath).isEqualTo(local)
    }

    @Test
    fun displayLabel_showsShortIdWhenDuplicateNames() {
        val names = listOf("MyNotesFolder", "MyNotesFolder", "Other")
        assertThat(SameNamedCloudFolderHeal.displayLabel("MyNotesFolder", winId, names))
            .isEqualTo("MyNotesFolder · 898c1453")
        assertThat(SameNamedCloudFolderHeal.displayLabel("Other", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", names))
            .isEqualTo("Other")
    }
}

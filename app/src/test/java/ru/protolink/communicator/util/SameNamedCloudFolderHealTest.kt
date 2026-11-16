package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * FAIL class P1-20261117: Android mapped duplicate MyNotesFolder 0bc549dd
 * while Win/AC use populated 898c1453. Live: empty dup is older than populated Win id.
 */
class SameNamedCloudFolderHealTest {

    private val winId = "898c1453-4034-4b9f-96f6-430b4ceca476"
    private val dupId = "0bc549dd-c880-42df-af32-4f3c340ba970"
    private val local = "content://com.android.externalstorage.documents/tree/primary%3AMyNotesFolder"

    /** Prior assumption (Win oldest) — both empty → oldest still wins. */
    private val siblingsWinOldest = listOf(
        SameNamedCloudFolderHeal.Sibling(dupId, "MyNotesFolder", "2026-11-15T10:00:00", 0),
        SameNamedCloudFolderHeal.Sibling(winId, "MyNotesFolder", "2026-10-29T18:49:32.7901832", 0),
        SameNamedCloudFolderHeal.Sibling("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Other", "2026-01-01T00:00:00", 0)
    )

    /**
     * Live ADY-LX9: empty 0bc549dd created 2026-10-28 (older), populated 898c1453 on 2026-10-29.
     * Pure oldest-wins wrongly keeps the empty dup.
     */
    private val siblingsLiveEmptyOlder = listOf(
        SameNamedCloudFolderHeal.Sibling(dupId, "MyNotesFolder", "2026-10-28T12:00:00", 0),
        SameNamedCloudFolderHeal.Sibling(winId, "MyNotesFolder", "2026-10-29T18:49:32.7901832", 12),
        SameNamedCloudFolderHeal.Sibling("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Other", "2026-01-01T00:00:00", 0)
    )

    @Test
    fun preferExistingSameName_picksOldestWhenChildCountsEqual() {
        val pick = SameNamedCloudFolderHeal.preferExistingSameName("MyNotesFolder", siblingsWinOldest)
        assertThat(pick!!.id).isEqualTo(winId)
    }

    @Test
    fun preferExistingSameName_prefersPopulatedOverOlderEmpty() {
        val pick = SameNamedCloudFolderHeal.preferExistingSameName("MyNotesFolder", siblingsLiveEmptyOlder)
        assertThat(pick!!.id).isEqualTo(winId)
    }

    @Test
    fun preferExistingSameName_noMatch_returnsNull() {
        assertThat(
            SameNamedCloudFolderHeal.preferExistingSameName("BrandNew", siblingsWinOldest)
        ).isNull()
    }

    @Test
    fun canonicalIdIfMappedIsDuplicate_remapsNewerDupToWin() {
        assertThat(
            SameNamedCloudFolderHeal.canonicalIdIfMappedIsDuplicate(dupId, "MyNotesFolder", siblingsWinOldest)
        ).isEqualTo(winId)
    }

    @Test
    fun canonicalIdIfMappedIsDuplicate_remapsOlderEmptyDupToPopulatedWin() {
        assertThat(
            SameNamedCloudFolderHeal.canonicalIdIfMappedIsDuplicate(dupId, "MyNotesFolder", siblingsLiveEmptyOlder)
        ).isEqualTo(winId)
    }

    @Test
    fun canonicalIdIfMappedIsDuplicate_alreadyCanonical_null() {
        assertThat(
            SameNamedCloudFolderHeal.canonicalIdIfMappedIsDuplicate(winId, "MyNotesFolder", siblingsLiveEmptyOlder)
        ).isNull()
    }

    @Test
    fun remapsAndApply_healsAndroidDupMappingWhenEmptyIsOlder() {
        val mappings = listOf(
            SameNamedCloudFolderHeal.MappingRow(dupId, local, "MyNotesFolder")
        )
        val parent = "cloud-root-id"
        val remaps = SameNamedCloudFolderHeal.remapsForDuplicateNames(
            mappings = mappings,
            siblingsByParent = mapOf(parent to siblingsLiveEmptyOlder),
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

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

    /**
     * Demotion FAIL class: after folderChildCount enrich, twin junk files score 0 folders while
     * content-bearing 898 scores ~24 — mapped 898 must not remap to twin; twin remaps → 898.
     */
    @Test
    fun preferExisting_folderRichCanonicalBeatsJunkFileTwin() {
        val siblings = listOf(
            SameNamedCloudFolderHeal.Sibling(dupId, "MyNotesFolder", "2026-10-28T09:46:55", 0),
            SameNamedCloudFolderHeal.Sibling(winId, "MyNotesFolder", "2026-10-29T18:49:32", 24)
        )
        val pick = SameNamedCloudFolderHeal.preferExistingSameName("MyNotesFolder", siblings)
        assertThat(pick!!.id).isEqualTo(winId)
        assertThat(
            SameNamedCloudFolderHeal.canonicalIdIfMappedIsDuplicate(winId, "MyNotesFolder", siblings)
        ).isNull()
    }

    @Test
    fun remaps_mappedCanonical_notDemotedWhenTwinHasZeroFolders() {
        val siblings = listOf(
            SameNamedCloudFolderHeal.Sibling(dupId, "MyNotesFolder", "2026-10-28T09:46:55", 0),
            SameNamedCloudFolderHeal.Sibling(winId, "MyNotesFolder", "2026-10-29T18:49:32", 24)
        )
        val remaps = SameNamedCloudFolderHeal.remapsForDuplicateNames(
            mappings = listOf(SameNamedCloudFolderHeal.MappingRow(winId, local, "MyNotesFolder")),
            siblingsByParent = mapOf("parent" to siblings),
            parentIdByMappedId = mapOf(winId to "parent")
        )
        assertThat(remaps).isEmpty()
    }

    @Test
    fun remaps_twinJunk_healsToFolderRichCanonical() {
        val siblings = listOf(
            SameNamedCloudFolderHeal.Sibling(dupId, "MyNotesFolder", "2026-10-28T09:46:55", 0),
            SameNamedCloudFolderHeal.Sibling(winId, "MyNotesFolder", "2026-10-29T18:49:32", 24)
        )
        val remaps = SameNamedCloudFolderHeal.remapsForDuplicateNames(
            mappings = listOf(SameNamedCloudFolderHeal.MappingRow(dupId, local, "MyNotesFolder")),
            siblingsByParent = mapOf("parent" to siblings),
            parentIdByMappedId = mapOf(dupId to "parent")
        )
        assertThat(remaps).hasSize(1)
        assertThat(remaps[0].fromId).isEqualTo(dupId)
        assertThat(remaps[0].toId).isEqualTo(winId)
    }

    /**
     * FAIL class after folderChildCount tip: twin near-clone (29 folders + 86 junk files)
     * still outranked 898 (28+25) on folders alone — healRichnessScore must prefer 898.
     */
    @Test
    fun preferExisting_nearCloneJunkTwinLosesToCanonical898() {
        val siblings = listOf(
            SameNamedCloudFolderHeal.Sibling(
                dupId, "MyNotesFolder", "2026-10-28T09:46:55",
                immediateChildCount = 29, immediateFileCount = 86
            ),
            SameNamedCloudFolderHeal.Sibling(
                winId, "MyNotesFolder", "2026-10-29T18:49:32",
                immediateChildCount = 28, immediateFileCount = 25
            )
        )
        val pick = SameNamedCloudFolderHeal.preferExistingSameName("MyNotesFolder", siblings)
        assertThat(pick!!.id).isEqualTo(winId)
        assertThat(
            SameNamedCloudFolderHeal.canonicalIdIfMappedIsDuplicate(dupId, "MyNotesFolder", siblings)
        ).isEqualTo(winId)
        assertThat(
            SameNamedCloudFolderHeal.canonicalIdIfMappedIsDuplicate(winId, "MyNotesFolder", siblings)
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

    @Test
    fun mergeById_dedupesAndKeepsHigherChildCount() {
        val a = SameNamedCloudFolderHeal.Sibling(dupId, "MyNotesFolder", "2026-10-28T12:00:00", 0)
        val b = SameNamedCloudFolderHeal.Sibling(dupId, "MyNotesFolder", null, 75)
        val c = SameNamedCloudFolderHeal.Sibling(winId, "MyNotesFolder", "2026-10-29T18:49:32", 306)
        val merged = SameNamedCloudFolderHeal.mergeById(listOf(a), listOf(b, c))
        assertThat(merged).hasSize(2)
        assertThat(merged.first { it.id == dupId }.immediateChildCount).isEqualTo(75)
        assertThat(merged.first { it.id == winId }.immediateChildCount).isEqualTo(306)
    }

    @Test
    fun cloudRootIdsForDiscovery_includesMustDriveAndOldest() {
        val androidRoot = "8aec679c-a389-46b9-8105-8a5b238ee979"
        val driveRoot = "3e5a2d6e-27ca-4c8b-89e6-e3cb253c05d6"
        val otherNew = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val roots = listOf(
            SameNamedCloudFolderHeal.Sibling(otherNew, "Scratch", "2026-11-17T00:00:00"),
            SameNamedCloudFolderHeal.Sibling(androidRoot, "", "2026-10-28T09:00:00"),
            SameNamedCloudFolderHeal.Sibling(driveRoot, "Drive", "2026-02-28T16:35:14")
        )
        val picked = SameNamedCloudFolderHeal.cloudRootIdsForDiscovery(
            cloudRoots = roots,
            mustIncludeIds = listOf(androidRoot),
            maxRoots = 16
        )
        assertThat(picked).contains(androidRoot)
        assertThat(picked).contains(driveRoot)
    }

    @Test
    fun remaps_crossCloudRootMergedSiblings_prefersPopulatedWin() {
        // Live: 0bc549dd under Android CloudRoot; 898c1453 under Drive — merge surfaces both.
        val androidRoot = "8aec679c-a389-46b9-8105-8a5b238ee979"
        val underAndroid = listOf(
            SameNamedCloudFolderHeal.Sibling(dupId, "MyNotesFolder", "2026-10-28T09:46:55", 75)
        )
        val underDrive = listOf(
            SameNamedCloudFolderHeal.Sibling(winId, "MyNotesFolder", "2026-10-29T18:49:32", 306)
        )
        val merged = SameNamedCloudFolderHeal.mergeById(underAndroid, underDrive)
        val remaps = SameNamedCloudFolderHeal.remapsForDuplicateNames(
            mappings = listOf(SameNamedCloudFolderHeal.MappingRow(dupId, local, "MyNotesFolder")),
            siblingsByParent = mapOf(androidRoot to merged),
            parentIdByMappedId = mapOf(dupId to androidRoot)
        )
        assertThat(remaps).hasSize(1)
        assertThat(remaps[0].fromId).isEqualTo(dupId)
        assertThat(remaps[0].toId).isEqualTo(winId)
    }

    @Test
    fun resolveMappingAfterHeal_prefersLocalPathCanonicalId_afterTwinRemap() {
        // Force UI still holds twin 0bc549dd; heal persisted prefer-populated 898c1453.
        val twin = "0bc549dd-0000-4000-8000-000000000001"
        val preferred = "898c1453-0000-4000-8000-000000000002"
        val healed = listOf(
            SameNamedCloudFolderHeal.MappingRow(preferred, local, "MyNotesFolder")
        )
        val resolved = SameNamedCloudFolderHeal.resolveMappingAfterHeal(
            requestedId = twin,
            requestedLocalPath = local,
            mappings = healed
        )
        assertThat(resolved).isNotNull()
        assertThat(resolved!!.cloudFolderId).isEqualTo(preferred)
        assertThat(resolved.localPath).isEqualTo(local)
    }

    @Test
    fun healBeforeForce_applyRemapsThenResolve_usesPreferredNotTwin() {
        val remaps = SameNamedCloudFolderHeal.remapsForDuplicateNames(
            mappings = listOf(SameNamedCloudFolderHeal.MappingRow(dupId, local, "MyNotesFolder")),
            siblingsByParent = mapOf("parent" to siblingsLiveEmptyOlder),
            parentIdByMappedId = mapOf(dupId to "parent")
        )
        assertThat(remaps).hasSize(1)
        val rows = listOf(SameNamedCloudFolderHeal.MappingRow(dupId, local, "MyNotesFolder"))
        val persisted = SameNamedCloudFolderHeal.applyRemaps(rows, remaps)
        val forForce = SameNamedCloudFolderHeal.resolveMappingAfterHeal(
            requestedId = dupId,
            requestedLocalPath = local,
            mappings = persisted
        )
        assertThat(forForce!!.cloudFolderId).isEqualTo(winId)
    }
}

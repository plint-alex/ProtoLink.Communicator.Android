package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonPrimitive
import org.junit.Test
import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.api.EntityDto
import ru.protolink.communicator.data.api.EntityValueDto
import ru.protolink.communicator.sync.model.SyncItemMeta
import java.time.Instant

/**
 * Win-parity CloudInteractiveListing — listing diverge FAIL class
 * (only-web structural folders never painted; heal twin junk count).
 */
class CloudInteractiveListingTest {

    private val nameType = CloudCodes.NAME_TYPE_ID

    private fun named(id: String, code: String, name: String, update: String? = null) = EntityDto(
        id = id,
        code = code,
        updateTime = update,
        values = listOf(
            EntityValueDto(
                value = JsonPrimitive(name),
                parents = listOf(nameType)
            )
        )
    )

    @Test
    fun resolveRowName_prefersNameSlot() {
        val e = named("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", CloudCodes.CLOUD_FOLDER, "dev ops")
        assertThat(CloudInteractiveListing.resolveRowName(e)).isEqualTo("dev ops")
    }

    @Test
    fun resolveRowName_usesSyncMetaLeafWhenSynthetic() {
        val id = "321b20bd-8c27-4621-beb1-c8dd63bdaa50"
        val e = EntityDto(id = id, code = CloudCodes.CLOUD_FOLDER, values = null)
        val synthetic = EntityMeta.fallbackName(id, CloudCodes.CLOUD_FOLDER)
        assertThat(CloudInteractiveListing.isSyntheticDisplayName(e, synthetic)).isTrue()
        val name = CloudInteractiveListing.resolveRowName(e) { remote ->
            if (remote.equals(id, ignoreCase = true)) "dev ops" else null
        }
        assertThat(name).isEqualTo("dev ops")
    }

    @Test
    fun listingComparator_foldersFirstThenNewerThenName() {
        data class Row(val folder: Boolean, val t: Long, val n: String)
        val rows = listOf(
            Row(false, 100, "z.txt"),
            Row(true, 50, "films"),
            Row(true, 80, "ef commands"),
            Row(true, 80, "docusign"),
            Row(false, 200, "a.txt")
        )
        val sorted = rows.sortedWith(
            CloudInteractiveListing.listingComparator({ it.folder }, { it.t }, { it.n })
        )
        assertThat(sorted.map { it.n }).containsExactly(
            "docusign", "ef commands", "films", "a.txt", "z.txt"
        ).inOrder()
    }

    @Test
    fun buildSoftReadyRows_filtersParentAndOrdersFoldersFirst() {
        val folder = "898c1453-4034-4b9f-96f6-430b4ceca476"
        val kids = listOf(
            SyncItemMeta("m", "f1", folder, "z.txt", false, 1, Instant.parse("2026-11-18T12:00:00Z")),
            SyncItemMeta("m", "d1", folder, "dev ops", true, 0, Instant.parse("2026-11-17T02:44:38Z")),
            SyncItemMeta("m", "d2", folder, "ef commands", true, 0, Instant.parse("2026-11-17T02:44:39Z")),
            SyncItemMeta("m", "other", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "skip", true, 0, null)
        )
        val rows = CloudInteractiveListing.buildSoftReadyRows(folder, kids, maxRows = 80)
        assertThat(rows.map { it.name }).containsExactly("ef commands", "dev ops", "z.txt").inOrder()
        assertThat(rows).hasSize(3)
    }

    @Test
    fun dedupeById_keepsFirst() {
        val items = listOf("A" to 1, "a" to 2, "b" to 3)
        val deduped = CloudInteractiveListing.dedupeById(items) { it.first }
        assertThat(deduped.map { it.second }).containsExactly(1, 3).inOrder()
    }

    @Test
    fun folderChildCount_ignoresCloudFiles() {
        val kids = listOf(
            named("1", CloudCodes.CLOUD_FOLDER, "dev ops"),
            named("2", CloudCodes.CLOUD_FOLDER, "docusign"),
            named("3", CloudCodes.CLOUD_FILE, "junk.txt"),
            named("4", CloudCodes.CLOUD_FILE, "more.txt")
        )
        assertThat(CloudInteractiveListing.folderChildCount(kids)).isEqualTo(2)
    }

    @Test
    fun folderChildCount_prefersFolderRichCanonicalOverJunkTwin() {
        // Live: twin 0bc549dd ~132 files vs 898c1453 ~24 folders — raw .size wrongly picks twin.
        val twinKids = (1..132).map {
            named("t$it", CloudCodes.CLOUD_FILE, "junk-$it.txt")
        }
        val canonicalKids = (1..24).map {
            named("c$it", CloudCodes.CLOUD_FOLDER, "folder-$it")
        } + (1..21).map {
            named("f$it", CloudCodes.CLOUD_FILE, "file-$it.txt")
        }
        assertThat(CloudInteractiveListing.folderChildCount(twinKids)).isEqualTo(0)
        assertThat(CloudInteractiveListing.folderChildCount(canonicalKids)).isEqualTo(24)
        assertThat(CloudInteractiveListing.folderChildCount(canonicalKids))
            .isGreaterThan(CloudInteractiveListing.folderChildCount(twinKids))
    }

    @Test
    fun interactiveListing_pagesCoverMyNotesFolderScale() {
        // FAIL: MaxPages=1 Take=80 dropped structural folders past API CreationTime page.
        assertThat(CloudInteractiveListing.PAGE_SIZE).isEqualTo(80)
        assertThat(CloudInteractiveListing.MAX_PAGES).isEqualTo(5)
        assertThat(CloudInteractiveListing.MAX_ROWS).isEqualTo(400)
        assertThat(CloudInteractiveListing.MAX_ROWS).isAtLeast(115)
    }

    @Test
    fun multiPageFetch_thenFoldersFirst_keepsStructuralFoldersPastFirst80() {
        // Simulate API OrderBy CreationTime (newest files first) across 2×80 pages.
        val files = (1..86).map { i ->
            named(
                "f%08d-aaaa-4aaa-8aaa-aaaaaaaaaaaa".format(i),
                CloudCodes.CLOUD_FILE,
                "junk-$i.txt",
                update = "2026-11-19T15:00:%02dZ".format(i % 60)
            )
        }
        val folders = listOf("dev ops", "docusign", "ef commands", "films").mapIndexed { i, n ->
            named(
                "d%08d-bbbb-4bbb-8bbb-bbbbbbbbbbbb".format(i + 1),
                CloudCodes.CLOUD_FOLDER,
                n,
                update = "2026-11-17T02:44:%02dZ".format(i)
            )
        }
        val apiOrder = files + folders // first page = 80 files; folders on page 2
        val page1 = apiOrder.take(CloudInteractiveListing.PAGE_SIZE)
        val page2 = apiOrder.drop(CloudInteractiveListing.PAGE_SIZE)
            .take(CloudInteractiveListing.PAGE_SIZE)
        assertThat(page1.none { it.code == CloudCodes.CLOUD_FOLDER }).isTrue()
        val fetched = page1 + page2
        data class Row(val id: String, val name: String, val folder: Boolean, val t: Long)
        val painted = fetched.map {
            Row(
                it.id,
                CloudInteractiveListing.resolveRowName(it),
                it.code == CloudCodes.CLOUD_FOLDER,
                parseUpdateMillis(it.updateTime)
            )
        }.sortedWith(
            CloudInteractiveListing.listingComparator({ it.folder }, { it.t }, { it.name })
        )
        assertThat(painted.map { it.name }).containsAtLeast("dev ops", "docusign", "ef commands", "films")
        assertThat(painted.count { it.folder }).isEqualTo(4)
        // Truncated first page alone would have dropped all folders:
        assertThat(page1.map { CloudInteractiveListing.resolveRowName(it) })
            .containsNoneOf("dev ops", "docusign", "ef commands")
    }

    @Test
    fun mergeHydrateById_keepsLightFoldersWhenHydrateShorter() {
        data class Row(val id: String, val name: String)
        val light = listOf(
            Row("d1", "dev ops"),
            Row("d2", "docusign"),
            Row("f1", "file-1")
        )
        val hydrated = listOf(Row("f1", "file-1-named"), Row("d1", "dev ops"))
        val merged = CloudInteractiveListing.mergeHydrateById(
            light,
            hydrated,
            id = { it.id },
            merge = { _, h -> h }
        )
        assertThat(merged.map { it.name }).containsExactly("dev ops", "docusign", "file-1-named").inOrder()
    }

    @Test
    fun healRichnessScore_nearCloneJunkTwinLosesToCanonical() {
        // Live ADY-LX9: twin ≈29 folders + 86 dumps vs 898 ≈28 folders + 25 files.
        val twin = CloudInteractiveListing.healRichnessScore(29, 86)
        val canonical = CloudInteractiveListing.healRichnessScore(28, 25)
        assertThat(canonical).isGreaterThan(twin)
        assertThat(CloudInteractiveListing.healRichnessScore(24, 0))
            .isGreaterThan(CloudInteractiveListing.healRichnessScore(0, 132))
    }

    @Test
    fun preferApiOrSoftReady_emptyBrowseUsesSoftReadyStructural() {
        // CLOUD_BROWSE_EMPTY_DESPITE_API_KIDS: values-light empty / timeout while Api has kids —
        // Soft Ready meta for canonical 898 must paint structural folders (not lasting 0 items).
        val folder = "898c1453-4034-4b9f-96f6-430b4ceca476"
        val soft = CloudInteractiveListing.buildSoftReadyRows(
            folderRemoteId = folder,
            children = listOf(
                SyncItemMeta("m", "d1", folder, "dev ops", true, 0, Instant.parse("2026-11-17T02:44:38Z")),
                SyncItemMeta("m", "d2", folder, "docusign", true, 0, Instant.parse("2026-11-17T02:44:39Z")),
                SyncItemMeta("m", "d3", folder, "ef commands", true, 0, Instant.parse("2026-11-17T02:44:40Z"))
            ),
            maxRows = CloudInteractiveListing.MAX_ROWS
        )
        val painted = CloudInteractiveListing.preferApiOrSoftReady(emptyList(), soft)
        assertThat(painted.map { it.name }).containsAtLeast("dev ops", "docusign", "ef commands")
        assertThat(CloudInteractiveListing.preferApiOrSoftReady(soft.take(1), soft)).hasSize(1)
    }

    @Test
    fun shouldApplyListingResult_rejectsStaleOrNavigatedAway() {
        assertThat(
            CloudInteractiveListing.shouldApplyListingResult(1, 2, "898", "898")
        ).isFalse()
        assertThat(
            CloudInteractiveListing.shouldApplyListingResult(1, 1, "other", "898")
        ).isFalse()
        assertThat(
            CloudInteractiveListing.shouldApplyListingResult(3, 3, "898", "898")
        ).isTrue()
    }

    @Test
    fun mergeHydrateUnionById_softReadyGainsApiKids() {
        data class Row(val id: String, val name: String)
        val softReady = listOf(Row("d1", "dev ops"), Row("d2", "docusign"))
        val hydrated = listOf(
            Row("d1", "dev ops"),
            Row("d2", "docusign"),
            Row("d3", "ef commands"),
            Row("f1", "note.txt")
        )
        val merged = CloudInteractiveListing.mergeHydrateUnionById(
            softReady,
            hydrated,
            id = { it.id },
            merge = { _, h -> h }
        )
        assertThat(merged.map { it.name })
            .containsAtLeast("dev ops", "docusign", "ef commands", "note.txt")
        // Shorter hydrate must not drop soft-ready-only folders:
        val partial = CloudInteractiveListing.mergeHydrateUnionById(
            softReady,
            listOf(Row("d1", "dev ops")),
            id = { it.id },
            merge = { _, h -> h }
        )
        assertThat(partial.map { it.name }).containsExactly("dev ops", "docusign").inOrder()
    }

    @Test
    fun looksLikeHubChromeListing_detectsHubSiblingsNotMappedKids() {
        // Live FAIL: MyNotesFolder crumb + 5 hub rows (Моё облако/Фото/Shared/Recent).
        val hubIds = listOf(
            "cloud-root-id",
            "photos-lib-id",
            CloudVirtualNodes.SHARED_WITH_ME_ID,
            CloudVirtualNodes.SHARED_BY_ME_ID,
            CloudVirtualNodes.RECENT_ID
        )
        assertThat(
            CloudInteractiveListing.looksLikeHubChromeListing(hubIds, CloudVirtualNodes::isVirtual)
        ).isTrue()
        val mappedKids = listOf("d1", "d2", "d3", "f1", "f2", "f3", "f4")
        assertThat(
            CloudInteractiveListing.looksLikeHubChromeListing(mappedKids, CloudVirtualNodes::isVirtual)
        ).isFalse()
    }

    @Test
    fun withoutVirtualHubRows_andHydrate_doesNotKeepHubChromeAsMappedKids() {
        // CLOUD_BROWSE_HUB_CHROME_INSTEAD_OF_MAP_KIDS: hydrate must not union hub chrome
        // left in state with Api kids — detect hub shape and discard light entirely
        // (My Cloud/Photos are non-virtual; strip-virtual alone leaves them).
        data class Row(val id: String, val name: String)
        val staleHub = listOf(
            Row("cloud-root", "Моё облако"),
            Row("photos", "Фото"),
            Row(CloudVirtualNodes.SHARED_WITH_ME_ID, "Доступные мне"),
            Row(CloudVirtualNodes.SHARED_BY_ME_ID, "Я поделился"),
            Row(CloudVirtualNodes.RECENT_ID, "Недавние")
        )
        assertThat(
            CloudInteractiveListing.looksLikeHubChromeListing(
                staleHub.map { it.id },
                CloudVirtualNodes::isVirtual
            )
        ).isTrue()
        val light = if (CloudInteractiveListing.looksLikeHubChromeListing(
                staleHub.map { it.id },
                CloudVirtualNodes::isVirtual
            )
        ) {
            emptyList()
        } else {
            CloudInteractiveListing.withoutVirtualHubRows(
                staleHub,
                id = { it.id },
                isVirtual = CloudVirtualNodes::isVirtual
            )
        }
        val hydrated = listOf(
            Row("d1", "dev ops"),
            Row("d2", "docusign"),
            Row("d3", "ef commands")
        )
        val merged = CloudInteractiveListing.mergeHydrateUnionById(
            light,
            hydrated,
            id = { it.id },
            merge = { _, h -> h }
        )
        assertThat(merged.map { it.name })
            .containsExactly("dev ops", "docusign", "ef commands").inOrder()
        assertThat(merged.map { it.name })
            .containsNoneOf("Моё облако", "Фото", "Доступные мне", "Я поделился", "Недавние")
        assertThat(
            CloudInteractiveListing.looksLikeHubChromeListing(
                merged.map { it.id },
                CloudVirtualNodes::isVirtual
            )
        ).isFalse()
    }

    @Test
    fun softReadyNavigate_replacesHubChromeWithMappedStructural() {
        // beginFolderListingLoad Soft Ready: hub chrome must not last after open map 898.
        val folder = "898c1453-4034-4b9f-96f6-430b4ceca476"
        val hubIds = listOf(
            "cloud-root",
            CloudVirtualNodes.SHARED_WITH_ME_ID,
            CloudVirtualNodes.SHARED_BY_ME_ID,
            CloudVirtualNodes.RECENT_ID
        )
        assertThat(
            CloudInteractiveListing.looksLikeHubChromeListing(hubIds, CloudVirtualNodes::isVirtual)
        ).isTrue()
        val soft = CloudInteractiveListing.buildSoftReadyRows(
            folderRemoteId = folder,
            children = listOf(
                SyncItemMeta("m", "d1", folder, "dev ops", true, 0, Instant.parse("2026-11-17T02:44:38Z")),
                SyncItemMeta("m", "d2", folder, "docusign", true, 0, Instant.parse("2026-11-17T02:44:39Z")),
                SyncItemMeta("m", "d3", folder, "ef commands", true, 0, Instant.parse("2026-11-17T02:44:40Z"))
            ),
            maxRows = CloudInteractiveListing.MAX_ROWS
        )
        // Soft Ready replaces hub (prefer Soft when navigating before Api Ready).
        val painted = CloudInteractiveListing.preferApiOrSoftReady(emptyList(), soft)
        assertThat(painted.map { it.name }).containsAtLeast("dev ops", "docusign", "ef commands")
        assertThat(
            CloudInteractiveListing.looksLikeHubChromeListing(
                painted.map { it.id },
                CloudVirtualNodes::isVirtual
            )
        ).isFalse()
    }

    private fun parseUpdateMillis(update: String?): Long {
        if (update.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(update).toEpochMilli() }.getOrDefault(0L)
    }
}

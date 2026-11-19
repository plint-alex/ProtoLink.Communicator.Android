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
}

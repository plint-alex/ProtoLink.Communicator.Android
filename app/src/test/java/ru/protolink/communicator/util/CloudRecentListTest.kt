package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** C6 Recent MRU helpers — pure, no IO. */
class CloudRecentListTest {
    @Test
    fun push_prependsNewest_andDedupesById() {
        val a = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val b = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val t0 = 1_000L
        val t1 = 2_000L
        val t2 = 3_000L

        var list = CloudRecentList.push(
            emptyList(),
            CloudRecentList.Entry(a, "A", true, t0)
        )
        list = CloudRecentList.push(list, CloudRecentList.Entry(b, "B", false, t1))
        list = CloudRecentList.push(list, CloudRecentList.Entry(a, "A-renamed", true, t2))

        assertThat(list).hasSize(2)
        assertThat(list[0].entityId).isEqualTo(a)
        assertThat(list[0].name).isEqualTo("A-renamed")
        assertThat(list[0].openedUtcMillis).isEqualTo(t2)
        assertThat(list[1].entityId).isEqualTo(b)
    }

    @Test
    fun push_capsAtTwenty_dropsOldest() {
        var list = emptyList<CloudRecentList.Entry>()
        var first: String? = null
        for (i in 0 until CloudRecentList.DEFAULT_CAPACITY + 3) {
            val id = "%08x-0000-4000-8000-000000000000".format(i)
            if (i == 0) first = id
            list = CloudRecentList.push(
                list,
                CloudRecentList.Entry(id, "n$i", i % 2 == 0, 1_000L + i)
            )
        }

        assertThat(list).hasSize(CloudRecentList.DEFAULT_CAPACITY)
        assertThat(list.none { it.entityId == first }).isTrue()
        val newest = "%08x-0000-4000-8000-000000000000".format(CloudRecentList.DEFAULT_CAPACITY + 2)
        assertThat(list[0].entityId).isEqualTo(newest)
    }

    @Test
    fun push_ignoresVirtualAndEmptyIds() {
        val real = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        var list = CloudRecentList.push(
            emptyList(),
            CloudRecentList.Entry(CloudVirtualNodes.RECENT_ID, "Recent", true, 1L)
        )
        assertThat(list).isEmpty()

        list = CloudRecentList.push(
            list,
            CloudRecentList.Entry("", "x", false, 1L)
        )
        assertThat(list).isEmpty()

        list = CloudRecentList.push(
            list,
            CloudRecentList.Entry(CloudVirtualNodes.HUB_ID, "Hub", true, 1L)
        )
        assertThat(list).isEmpty()

        list = CloudRecentList.push(
            list,
            CloudRecentList.Entry(real, "R", true, 1L)
        )
        assertThat(list).hasSize(1)
        assertThat(list[0].entityId).isEqualTo(real)
    }

    @Test
    fun keepOnly_preservesOrder_andDropsMissing() {
        val a = "11111111-1111-4111-8111-111111111111"
        val b = "22222222-2222-4222-8222-222222222222"
        val c = "33333333-3333-4333-8333-333333333333"
        val list = listOf(
            CloudRecentList.Entry(a, "A", true, 1L),
            CloudRecentList.Entry(b, "B", false, 1L),
            CloudRecentList.Entry(c, "C", true, 1L)
        )

        val kept = CloudRecentList.keepOnly(list, setOf(a, c))
        assertThat(kept).hasSize(2)
        assertThat(kept[0].entityId).isEqualTo(a)
        assertThat(kept[1].entityId).isEqualTo(c)
    }

    @Test
    fun remove_dropsMatchingId() {
        val a = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val b = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val list = listOf(
            CloudRecentList.Entry(a, "A", true, 1L),
            CloudRecentList.Entry(b, "B", false, 1L)
        )
        val after = CloudRecentList.remove(list, a)
        assertThat(after).hasSize(1)
        assertThat(after[0].entityId).isEqualTo(b)
    }

    @Test
    fun shouldRecordOpen_skipsVirtualAndCloudRoot() {
        val folder = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val root = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        assertThat(CloudRecentList.shouldRecordOpen(folder, root)).isTrue()
        assertThat(CloudRecentList.shouldRecordOpen(root, root)).isFalse()
        assertThat(CloudRecentList.shouldRecordOpen(CloudVirtualNodes.RECENT_ID, root)).isFalse()
        assertThat(CloudRecentList.shouldRecordOpen(CloudVirtualNodes.SHARED_WITH_ME_ID, root)).isFalse()
        assertThat(CloudRecentList.shouldRecordOpen("", root)).isFalse()
    }

    @Test
    fun recent_strings_enRuParity() {
        assertThat(ShareSheetCloudStrings.recent("en")).isEqualTo("Recent")
        assertThat(ShareSheetCloudStrings.recent("ru-RU")).isEqualTo("Недавние")
        assertThat(ShareSheetCloudStrings.emptyRecentTitle("en")).isEqualTo("No recent items yet")
        assertThat(ShareSheetCloudStrings.emptyRecentBody("ru")).contains("облаке")
    }
}

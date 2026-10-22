package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** E6 hub CTA + Notes nudge visibility (AT-MAP-1 / AT-MAP-4 / AT-MAP-5). */
class MapLocalFolderUiTest {
    private val myCloud = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    @Test
    fun hubCta_visibleOnCloudHub_zeroMappings() {
        assertThat(
            MapLocalFolderUi.showHubCta(
                authenticated = true,
                currentFolderId = CloudVirtualNodes.HUB_ID,
                breadcrumbIds = listOf(CloudVirtualNodes.HUB_ID),
                mappedFolderIds = emptySet(),
                shareRootCanWrite = emptyMap()
            )
        ).isTrue()
    }

    @Test
    fun hubCta_visibleOnMyCloudWritable() {
        assertThat(
            MapLocalFolderUi.showHubCta(
                authenticated = true,
                currentFolderId = myCloud,
                breadcrumbIds = listOf(CloudVirtualNodes.HUB_ID, myCloud),
                mappedFolderIds = emptySet(),
                shareRootCanWrite = emptyMap()
            )
        ).isTrue()
    }

    @Test
    fun hubCta_hiddenOnSharedListing() {
        assertThat(
            MapLocalFolderUi.showHubCta(
                authenticated = true,
                currentFolderId = CloudVirtualNodes.SHARED_WITH_ME_ID,
                breadcrumbIds = listOf(CloudVirtualNodes.HUB_ID, CloudVirtualNodes.SHARED_WITH_ME_ID),
                mappedFolderIds = emptySet(),
                shareRootCanWrite = emptyMap()
            )
        ).isFalse()
        assertThat(
            MapLocalFolderUi.showHubCta(
                authenticated = true,
                currentFolderId = CloudVirtualNodes.RECENT_ID,
                breadcrumbIds = listOf(CloudVirtualNodes.HUB_ID, CloudVirtualNodes.RECENT_ID),
                mappedFolderIds = emptySet(),
                shareRootCanWrite = emptyMap()
            )
        ).isFalse()
    }

    @Test
    fun hubCta_hiddenOnBrowseOnlyShare() {
        val readRoot = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        assertThat(
            MapLocalFolderUi.showHubCta(
                authenticated = true,
                currentFolderId = readRoot,
                breadcrumbIds = listOf(
                    CloudVirtualNodes.HUB_ID,
                    CloudVirtualNodes.SHARED_WITH_ME_ID,
                    readRoot
                ),
                mappedFolderIds = emptySet(),
                shareRootCanWrite = mapOf(readRoot.lowercase() to false)
            )
        ).isFalse()
    }

    @Test
    fun hubCta_hiddenWhenUnauthenticated() {
        assertThat(
            MapLocalFolderUi.showHubCta(
                authenticated = false,
                currentFolderId = CloudVirtualNodes.HUB_ID,
                breadcrumbIds = listOf(CloudVirtualNodes.HUB_ID),
                mappedFolderIds = emptySet(),
                shareRootCanWrite = emptyMap()
            )
        ).isFalse()
    }

    @Test
    fun notesNudge_whenRootNotMapped() {
        assertThat(
            MapLocalFolderUi.showNotesNudge(
                "content://tree/notes",
                listOf("content://tree/other")
            )
        ).isTrue()
    }

    @Test
    fun notesNudge_goneWhenCovered() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ANotes"
        assertThat(MapLocalFolderUi.showNotesNudge(uri, listOf(uri))).isFalse()
    }

    @Test
    fun strings_en_ru() {
        assertThat(MapLocalFolderStrings.hubCta("en")).isEqualTo("Add local folder to Cloud…")
        assertThat(MapLocalFolderStrings.hubCta("ru")).contains("облако")
        assertThat(MapLocalFolderStrings.notesNudgeText("en-US")).contains("Add this folder to Cloud")
    }
}

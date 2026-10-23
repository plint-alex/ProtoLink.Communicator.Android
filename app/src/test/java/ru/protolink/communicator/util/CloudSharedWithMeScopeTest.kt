package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** AT-S17 / AT-S42 Shared with me scope + UX §7 strings (offline). */
class CloudSharedWithMeScopeTest {
    @Test
    fun isInSharedWithMeScope_true_whenBreadcrumbContainsSharedNode() {
        val ids = listOf(
            CloudVirtualNodes.HUB_ID,
            CloudVirtualNodes.SHARED_WITH_ME_ID,
            "11111111-1111-4111-8111-111111111111"
        )
        assertThat(CloudSharedScope.isInSharedWithMeScope(ids)).isTrue()
    }

    @Test
    fun isInSharedWithMeScope_false_underMyCloud() {
        val ids = listOf(CloudVirtualNodes.HUB_ID, "22222222-2222-4222-8222-222222222222")
        assertThat(CloudSharedScope.isInSharedWithMeScope(ids)).isFalse()
    }

    @Test
    fun tryGetActiveShareRootId_returnsFirstAfterSharedNode() {
        val shareRoot = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val child = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val ids = listOf(
            CloudVirtualNodes.HUB_ID,
            CloudVirtualNodes.SHARED_WITH_ME_ID,
            shareRoot,
            child
        )
        assertThat(CloudSharedScope.tryGetActiveShareRootId(ids)).isEqualTo(shareRoot)
        assertThat(
            CloudSharedScope.tryGetActiveShareRootId(
                listOf(CloudVirtualNodes.HUB_ID, CloudVirtualNodes.SHARED_WITH_ME_ID)
            )
        ).isNull()
        assertThat(
            CloudSharedScope.tryGetActiveShareRootId(listOf(CloudVirtualNodes.HUB_ID))
        ).isNull()
    }

    @Test
    fun canMap_writeShareRoot_true_readShareRoot_false() {
        val writeRoot = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val readRoot = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val canWrite = mapOf(
            writeRoot.lowercase() to true,
            readRoot.lowercase() to false
        )
        val emptyMapped = emptySet<String>()

        val writeCrumbs = listOf(
            CloudVirtualNodes.HUB_ID,
            CloudVirtualNodes.SHARED_WITH_ME_ID,
            writeRoot
        )
        assertThat(
            CloudSharedScope.canMapCurrentFolder(writeRoot, writeCrumbs, emptyMapped, canWrite)
        ).isTrue()
        assertThat(
            CloudSharedScope.showSharedBrowseOnlyHint(writeRoot, writeCrumbs, canWrite)
        ).isFalse()

        val readCrumbs = listOf(
            CloudVirtualNodes.HUB_ID,
            CloudVirtualNodes.SHARED_WITH_ME_ID,
            readRoot
        )
        assertThat(
            CloudSharedScope.canMapCurrentFolder(readRoot, readCrumbs, emptyMapped, canWrite)
        ).isFalse()
        assertThat(
            CloudSharedScope.showSharedBrowseOnlyHint(readRoot, readCrumbs, canWrite)
        ).isTrue()
    }

    @Test
    fun canMap_writeSurvivesDescendantNavigation() {
        val writeRoot = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        val child = "ffffffff-ffff-4fff-8fff-ffffffffffff"
        val canWrite = mapOf(writeRoot.lowercase() to true)
        val crumbs = listOf(
            CloudVirtualNodes.HUB_ID,
            CloudVirtualNodes.SHARED_WITH_ME_ID,
            writeRoot,
            child
        )
        assertThat(CloudSharedScope.tryGetActiveShareRootId(crumbs)).isEqualTo(writeRoot)
        assertThat(
            CloudSharedScope.canMapCurrentFolder(child, crumbs, emptySet(), canWrite)
        ).isTrue()
        assertThat(
            CloudSharedScope.showSharedBrowseOnlyHint(child, crumbs, canWrite)
        ).isFalse()
    }

    @Test
    fun canMap_myCloud_unchanged() {
        val myCloud = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val crumbs = listOf(CloudVirtualNodes.HUB_ID, myCloud)
        assertThat(
            CloudSharedScope.canMapCurrentFolder(
                myCloud,
                crumbs,
                emptySet(),
                emptyMap()
            )
        ).isTrue()
        assertThat(
            CloudSharedScope.showSharedBrowseOnlyHint(myCloud, crumbs, emptyMap())
        ).isFalse()
    }

    @Test
    fun canMap_shareRootFromListing_usesFolderIdWhenNoActiveRoot() {
        val writeRoot = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val listingCrumbs = listOf(
            CloudVirtualNodes.HUB_ID,
            CloudVirtualNodes.SHARED_WITH_ME_ID
        )
        val canWrite = mapOf(writeRoot.lowercase() to true)
        assertThat(
            CloudSharedScope.allowsMapUnderSharedScope(listingCrumbs, writeRoot, canWrite)
        ).isTrue()
        assertThat(
            CloudSharedScope.allowsMapUnderSharedScope(
                listingCrumbs,
                "99999999-9999-4999-8999-999999999999",
                canWrite
            )
        ).isFalse()
    }

    @Test
    fun virtualNodeIds_areDistinctAndFlagged() {
        assertThat(CloudVirtualNodes.HUB_ID).isNotEqualTo(CloudVirtualNodes.SHARED_WITH_ME_ID)
        assertThat(CloudVirtualNodes.HUB_ID).isNotEqualTo(CloudVirtualNodes.RECENT_ID)
        assertThat(CloudVirtualNodes.SHARED_WITH_ME_ID).isNotEqualTo(CloudVirtualNodes.RECENT_ID)
        assertThat(CloudVirtualNodes.isVirtual(CloudVirtualNodes.HUB_ID)).isTrue()
        assertThat(CloudVirtualNodes.isVirtual(CloudVirtualNodes.SHARED_WITH_ME_ID)).isTrue()
        assertThat(CloudVirtualNodes.isVirtual(CloudVirtualNodes.RECENT_ID)).isTrue()
        assertThat(CloudVirtualNodes.isVirtual("33333333-3333-4333-8333-333333333333")).isFalse()
    }

    @Test
    fun sharedWithMe_strings_enRuParity() {
        assertThat(ShareSheetCloudStrings.sharedWithMe("en")).isEqualTo("Shared with me")
        assertThat(ShareSheetCloudStrings.sharedWithMe("ru-RU")).isEqualTo("Доступные мне")
        assertThat(ShareSheetCloudStrings.myCloud("en-US")).isEqualTo("My Cloud")
        assertThat(ShareSheetCloudStrings.myCloud("ru")).isEqualTo("Моё облако")

        assertThat(ShareSheetCloudStrings.emptySharedTitle("en"))
            .isEqualTo("Nothing shared with you yet")
        assertThat(ShareSheetCloudStrings.emptySharedBody("en"))
            .isEqualTo("When someone gives you access, it shows up here.")
        assertThat(ShareSheetCloudStrings.emptySharedTitle("ru"))
            .isEqualTo("Вам пока ничего не открыли")
        assertThat(ShareSheetCloudStrings.emptySharedBody("ru")).contains("здесь")

        val en = ShareSheetCloudStrings.sharedRowSubtitle("en", "Ivan", canWrite = false)
        assertThat(en).contains("Shared by Ivan")
        assertThat(en).contains("Can view")

        val ruEdit = ShareSheetCloudStrings.sharedRowSubtitle("ru", "Анна", canWrite = true)
        assertThat(ruEdit).contains("Поделился(ась) Анна")
        assertThat(ruEdit).contains("Редактирование")

        assertThat(ShareSheetCloudStrings.browseOnlyHint("en")).contains("Browse only")
        assertThat(ShareSheetCloudStrings.browseOnlyHint("ru")).contains("просмотр")

        assertThat(ShareSheetCloudStrings.leave("en")).isEqualTo("Leave")
        assertThat(ShareSheetCloudStrings.leave("ru-RU")).isEqualTo("Покинуть")
        assertThat(ShareSheetCloudStrings.leaveMenu("en")).isEqualTo("Remove from Shared with me")
        assertThat(ShareSheetCloudStrings.leaveMenu("ru")).isEqualTo("Убрать из «Доступные мне»")
        assertThat(ShareSheetCloudStrings.leaveConfirm("en")).contains("lose access")
        assertThat(ShareSheetCloudStrings.leaveConfirm("ru")).contains("пропадет")
        assertThat(ShareSheetCloudStrings.leaveSuccess("en")).isEqualTo("Removed from Shared with me")
        assertThat(ShareSheetCloudStrings.leaveSuccess("ru")).isEqualTo("Убрано из «Доступные мне»")
        assertThat(ShareSheetCloudStrings.leaveFailed("en")).isEqualTo("Couldn't leave. Try again.")
        assertThat(ShareSheetCloudStrings.leaveFailed("ru")).isEqualTo("Не удалось покинуть. Повторите.")

        // UX-LS-3: post-leave listing reload must not clobber §7b success with "Cloud loaded".
        val leaveEn = ShareSheetCloudStrings.leaveSuccess("en")
        val leaveRu = ShareSheetCloudStrings.leaveSuccess("ru")
        assertThat(CloudListingStatus.afterReload(leaveEn)).isEqualTo(leaveEn)
        assertThat(CloudListingStatus.afterReload(leaveRu)).isEqualTo(leaveRu)
        assertThat(CloudListingStatus.afterReload(null))
            .isEqualTo(CloudListingStatus.DEFAULT_LOADED)
        assertThat(CloudListingStatus.afterReload("   "))
            .isEqualTo(CloudListingStatus.DEFAULT_LOADED)
        assertThat(CloudListingStatus.afterReload(leaveEn))
            .isNotEqualTo(CloudListingStatus.DEFAULT_LOADED)
    }

    @Test
    fun canLeave_sharedListRow_and_openShareRoot() {
        val shareRoot = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val known = mapOf(shareRoot.lowercase() to false)
        assertThat(
            CloudSharedScope.canLeaveSharedListRow(
                isSharedWithMeListing = true,
                isShareRoot = true,
                isVirtualEntry = false
            )
        ).isTrue()
        assertThat(
            CloudSharedScope.canLeaveSharedListRow(
                isSharedWithMeListing = false,
                isShareRoot = true,
                isVirtualEntry = false
            )
        ).isFalse()
        assertThat(
            CloudSharedScope.canLeaveSharedListRow(
                isSharedWithMeListing = true,
                isShareRoot = false,
                isVirtualEntry = false
            )
        ).isFalse()
        assertThat(
            CloudSharedScope.canLeaveSharedListRow(
                isSharedWithMeListing = true,
                isShareRoot = true,
                isVirtualEntry = true
            )
        ).isFalse()

        val openCrumbs = listOf(
            CloudVirtualNodes.HUB_ID,
            CloudVirtualNodes.SHARED_WITH_ME_ID,
            shareRoot
        )
        assertThat(
            CloudSharedScope.canLeaveOpenShareRoot(shareRoot, openCrumbs, known)
        ).isTrue()
        assertThat(
            CloudSharedScope.canLeaveOpenShareRoot(
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                openCrumbs,
                known
            )
        ).isFalse()
        assertThat(
            CloudSharedScope.canLeaveOpenShareRoot(
                shareRoot,
                listOf(CloudVirtualNodes.HUB_ID, CloudVirtualNodes.SHARED_WITH_ME_ID),
                known
            )
        ).isFalse()
        assertThat(
            CloudSharedScope.canLeaveOpenShareRoot(shareRoot, openCrumbs, emptyMap())
        ).isFalse()
    }

    @Test
    fun canMapFolder_false_onVirtualHub() {
        assertThat(
            CloudSharedScope.canMapCurrentFolder(
                currentFolderId = CloudVirtualNodes.HUB_ID,
                breadcrumbIds = listOf(CloudVirtualNodes.HUB_ID),
                mappedFolderIds = emptySet(),
                shareRootCanWrite = emptyMap()
            )
        ).isFalse()
    }

    @Test
    fun showSharedBrowseOnlyHint_false_onSharedListingNode() {
        val crumbs = listOf(
            CloudVirtualNodes.HUB_ID,
            CloudVirtualNodes.SHARED_WITH_ME_ID
        )
        assertThat(
            CloudSharedScope.showSharedBrowseOnlyHint(
                CloudVirtualNodes.SHARED_WITH_ME_ID,
                crumbs,
                emptyMap()
            )
        ).isFalse()
    }
}

package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** E9f My Cloud first / no hub landing helpers. */
class CloudBrowseRootTest {
    private val myCloud = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    @Test
    fun coldOpenFolderId_isMyCloud_notHub() {
        assertThat(CloudBrowseRoot.coldOpenFolderId(myCloud)).isEqualTo(myCloud)
        assertThat(CloudBrowseRoot.isHubLanding(CloudBrowseRoot.coldOpenFolderId(myCloud))).isFalse()
        assertThat(CloudBrowseRoot.coldOpenFolderId(CloudVirtualNodes.HUB_ID)).isNull()
        assertThat(CloudBrowseRoot.coldOpenFolderId(null)).isNull()
        assertThat(CloudBrowseRoot.coldOpenFolderId("")).isNull()
    }

    @Test
    fun isHubLanding_onlyVirtualHub() {
        assertThat(CloudBrowseRoot.isHubLanding(CloudVirtualNodes.HUB_ID)).isTrue()
        assertThat(CloudBrowseRoot.isHubLanding(myCloud)).isFalse()
        assertThat(CloudBrowseRoot.isHubLanding(CloudVirtualNodes.SHARED_WITH_ME_ID)).isFalse()
        assertThat(CloudBrowseRoot.isHubLanding(null)).isFalse()
    }

    @Test
    fun myCloudCrumb_singleRoot_noHub() {
        val crumbs = CloudBrowseRoot.myCloudCrumb(myCloud, "My Cloud")
        assertThat(crumbs).containsExactly(myCloud to "My Cloud")
        assertThat(crumbs.none { CloudBrowseRoot.isHubLanding(it.first) }).isTrue()
    }

    @Test
    fun virtualLocationCrumb_single_noHubParent() {
        val swm = CloudBrowseRoot.virtualLocationCrumb(
            CloudVirtualNodes.SHARED_WITH_ME_ID,
            "Shared with me"
        )
        assertThat(swm).hasSize(1)
        assertThat(swm[0].first).isEqualTo(CloudVirtualNodes.SHARED_WITH_ME_ID)
        assertThat(swm.none { CloudBrowseRoot.isHubLanding(it.first) }).isTrue()
    }

    @Test
    fun withoutHubCrumb_stripsHubKeepsMyCloud() {
        val crumbs = listOf(
            CloudVirtualNodes.HUB_ID to "Cloud",
            myCloud to "My Cloud",
            "cccccccc-cccc-4ccc-8ccc-cccccccccccc" to "Child"
        )
        assertThat(CloudBrowseRoot.withoutHubCrumb(crumbs).map { it.first })
            .containsExactly(myCloud, "cccccccc-cccc-4ccc-8ccc-cccccccccccc")
            .inOrder()
    }
}

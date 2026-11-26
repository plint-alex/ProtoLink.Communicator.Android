package ru.protolink.communicator.util

/**
 * E9f pure helpers: cold open = My Cloud (never virtual hub listing).
 * Parity with Windows CloudEntityRestore.ColdOpenFolderId / IsHubLanding.
 */
object CloudBrowseRoot {
    /** E9f cold open: start folder is canonical My Cloud — never the virtual hub. */
    fun coldOpenFolderId(myCloudRootId: String?): String? =
        myCloudRootId?.takeIf { it.isNotBlank() && !CloudVirtualNodes.isVirtual(it) }

    /** E9f: hub listing must not be the user-facing start folder. */
    fun isHubLanding(folderId: String?): Boolean =
        !folderId.isNullOrBlank() &&
            folderId.equals(CloudVirtualNodes.HUB_ID, ignoreCase = true)

    fun myCloudCrumb(myCloudRootId: String, myCloudName: String): List<Pair<String, String>> =
        listOf(myCloudRootId to myCloudName)

    /** SWM / SBM / Recent as secondary Locations — single crumb, no hub parent. */
    fun virtualLocationCrumb(virtualId: String, name: String): List<Pair<String, String>> =
        listOf(virtualId to name)

    fun withoutHubCrumb(crumbs: List<Pair<String, String>>): List<Pair<String, String>> =
        crumbs.filterNot { it.first.equals(CloudVirtualNodes.HUB_ID, ignoreCase = true) }
}

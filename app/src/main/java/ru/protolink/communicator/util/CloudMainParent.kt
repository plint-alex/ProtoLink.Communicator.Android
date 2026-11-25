package ru.protolink.communicator.util

import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.CloudEntityClassify
import ru.protolink.communicator.data.api.EntityDto

/**
 * AT-PC5 — pick MainParent axis parent (same rule as web photos-nav-helpers pickParentEntity /
 * Windows CloudMainParent).
 */
object CloudMainParent {
    /**
     * Prefer the parent whose id equals [mainParentId]; else first navigable Cloud folder/root;
     * else first row.
     */
    fun pickParent(mainParentId: String?, parents: List<EntityDto>): EntityDto? {
        if (parents.isEmpty()) return null

        val mp = mainParentId?.takeIf { it.isNotBlank() }
        if (mp != null) {
            for (p in parents) {
                if (p.id.equals(mp, ignoreCase = true)) return p
            }
        }

        for (p in parents) {
            if (isCloudFolderOrRoot(p.code)) return p
        }

        return parents[0]
    }

    fun isCloudFolderOrRoot(code: String?): Boolean =
        CloudEntityClassify.isFolder(typeEntityId = null, code = code)

    fun isCloudFolderOrRoot(typeEntityId: String?, code: String?): Boolean =
        CloudEntityClassify.isFolder(typeEntityId, code)
}

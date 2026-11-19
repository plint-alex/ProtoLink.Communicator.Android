package ru.protolink.communicator.util

import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.api.EntityDto
import ru.protolink.communicator.sync.model.SyncItemMeta

/**
 * Win-parity interactive Cloud folder listing policy
 * (Windows Services.Cloud.CloudInteractiveListing).
 *
 * First paint: values-light + smaller Take; names from Values when present else sync-meta leaf
 * else synthetic folder-/file-{id}. Sort folders first → updateTime desc → name.
 */
object CloudInteractiveListing {
    /** Win CloudApiService.InteractiveListingPageSize. */
    const val PAGE_SIZE = 80

    /** Win CloudApiService.InteractiveListingMaxPages — first paint / hydrate. */
    const val MAX_PAGES = 1

    fun leafName(relativePath: String): String {
        val n = relativePath.replace('\\', '/').trim('/')
        if (n.isEmpty()) return ""
        val i = n.lastIndexOf('/')
        return if (i < 0) n else n.substring(i + 1)
    }

    fun isSyntheticDisplayName(entity: EntityDto, displayName: String): Boolean {
        if (EntityMeta.isSyntheticCloudFileDisplayName(entity, displayName)) return true
        return (entity.code == CloudCodes.CLOUD_FOLDER || entity.code == CloudCodes.CLOUD_ROOT) &&
            displayName.equals(EntityMeta.fallbackName(entity.id, entity.code), ignoreCase = true)
    }

    /**
     * Prefer Values name; else sync-meta RelativePath leaf; else synthetic.
     * [syncMetaLeafNameByRemoteId] may be null (unmapped / no meta).
     */
    fun resolveRowName(
        entity: EntityDto,
        syncMetaLeafNameByRemoteId: ((String) -> String?)? = null
    ): String {
        val fromValues = EntityMeta.displayName(entity)
        if (!isSyntheticDisplayName(entity, fromValues)) return fromValues
        val fromMeta = syncMetaLeafNameByRemoteId?.invoke(entity.id)?.trim()?.takeIf { it.isNotEmpty() }
        if (!fromMeta.isNullOrBlank()) return fromMeta
        return fromValues
    }

    data class SoftReadyRow(
        val id: String,
        val name: String,
        val isFolder: Boolean,
        val updateTimeMillis: Long
    )

    /**
     * Soft Ready rows from sync-meta children of [folderRemoteId] (mapped Notes).
     * Empty when meta absent — caller still needs GetEntities for unmapped folders.
     */
    fun buildSoftReadyRows(
        folderRemoteId: String,
        children: Iterable<SyncItemMeta>,
        maxRows: Int
    ): List<SoftReadyRow> {
        if (folderRemoteId.isBlank() || maxRows < 1) return emptyList()
        return children
            .asSequence()
            .filter {
                it.parentRemoteId.equals(folderRemoteId, ignoreCase = true) &&
                    it.remoteId.isNotBlank()
            }
            .map { c ->
                SoftReadyRow(
                    id = c.remoteId,
                    name = leafName(c.relativePath).ifBlank { c.remoteId.replace("-", "").take(8) },
                    isFolder = c.isFolder,
                    updateTimeMillis = c.remoteUpdateTime?.toEpochMilli() ?: 0L
                )
            }
            .sortedWith(listingComparator({ it.isFolder }, { it.updateTimeMillis }, { it.name }))
            .take(maxRows)
            .toList()
    }

    /** Win list order: folders first, then newer UpdateTime, then name. */
    fun <T> listingComparator(
        isFolder: (T) -> Boolean,
        updateTimeMillis: (T) -> Long,
        name: (T) -> String
    ): Comparator<T> =
        compareByDescending<T> { isFolder(it) }
            .thenByDescending { updateTimeMillis(it) }
            .thenBy { name(it).lowercase() }

    /** Dedupe by id (case-insensitive); keep first occurrence. */
    fun <T> dedupeById(items: List<T>, id: (T) -> String): List<T> {
        if (items.size < 2) return items
        val seen = linkedSetOf<String>()
        val out = ArrayList<T>(items.size)
        for (item in items) {
            val key = id(item).lowercase()
            if (key.isEmpty() || !seen.add(key)) continue
            out.add(item)
        }
        return out
    }

    /** Count only CloudFolder/CloudRoot — junk CloudFiles must not win SameNamed heal. */
    fun folderChildCount(children: List<EntityDto>): Int =
        children.count {
            it.code == CloudCodes.CLOUD_FOLDER || it.code == CloudCodes.CLOUD_ROOT
        }
}

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

    /**
     * Win CloudApiService.InteractiveListingMaxPages.
     * Live FAIL CLOUD_LISTING_DIVERGE_FOLDER_SET: MaxPages=1 Take=80 truncated twin
     * MyNotesFolder (115 kids) on API CreationTime order (files first) — client folders-first
     * sort never saw structural CloudFolders past the page. 5×80 covers MyNotesFolder-scale
     * (≤~380) without unbounded Drive walk.
     */
    const val MAX_PAGES = 5

    /** Soft-ready / paint budget for one interactive folder load. */
    const val MAX_ROWS = PAGE_SIZE * MAX_PAGES

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

    /** CloudFile children only — used with [healRichnessScore] for near-clone junk twins. */
    fun fileChildCount(children: List<EntityDto>): Int =
        children.count { it.code == CloudCodes.CLOUD_FILE }

    /**
     * SameNamed heal score: folders win, but junk-file bloat loses near-clone ties.
     * Live: twin 0bc549dd ≈29 folders + 86 dumps scored above 898c1453 ≈28+25 on
     * folderChildCount alone — owner contract requires 898.
     */
    fun healRichnessScore(folderCount: Int, fileCount: Int): Int {
        val folders = folderCount.coerceAtLeast(0)
        val files = fileCount.coerceAtLeast(0)
        return folders * 10 - files
    }

    /**
     * Overlay IncludeValues hydrate onto values-light rows by id — never drop light
     * CloudFolder rows when hydrate returns a shorter page (timeout / partial).
     */
    fun <T> mergeHydrateById(
        light: List<T>,
        hydrated: List<T>,
        id: (T) -> String,
        merge: (lightRow: T, hydratedRow: T) -> T
    ): List<T> {
        if (hydrated.isEmpty()) return light
        if (light.isEmpty()) return hydrated
        val byId = linkedMapOf<String, T>()
        for (row in hydrated) {
            val key = id(row).lowercase()
            if (key.isNotEmpty()) byId[key] = row
        }
        return light.map { row ->
            val key = id(row).lowercase()
            val h = byId[key] ?: return@map row
            merge(row, h)
        }
    }

    /**
     * Soft Ready / partial light + fuller hydrate: overlay matching ids, then append
     * hydrate-only rows (Win MergeListing reset:false). Never drops light-only rows when
     * hydrate is shorter; never leaves Soft Ready stuck when hydrate has Api kids.
     */
    fun <T> mergeHydrateUnionById(
        light: List<T>,
        hydrated: List<T>,
        id: (T) -> String,
        merge: (lightRow: T, hydratedRow: T) -> T
    ): List<T> {
        if (hydrated.isEmpty()) return light
        if (light.isEmpty()) return hydrated
        val overlaid = mergeHydrateById(light, hydrated, id, merge)
        val lightIds = overlaid.map { id(it).lowercase() }.filter { it.isNotEmpty() }.toHashSet()
        val extras = hydrated.filter { id(it).lowercase() !in lightIds }
        return if (extras.isEmpty()) overlaid else overlaid + extras
    }

    /**
     * CLOUD_BROWSE_EMPTY_DESPITE_API_KIDS: when values-light is empty (timeout / race /
     * cold map), Soft Ready sync-meta rows must paint if present — Win
     * TryApplySyncMetaSoftReady. API rows always win when non-empty.
     */
    fun <T> preferApiOrSoftReady(apiRows: List<T>, softReadyRows: List<T>): List<T> =
        if (apiRows.isNotEmpty()) apiRows else softReadyRows

    /**
     * Win CloudViewModel listingLoadGeneration: stale Refresh / navigate-away must not
     * apply (including empty) over a newer in-flight load.
     */
    fun shouldApplyListingResult(
        loadGen: Int,
        currentGen: Int,
        listedFolderId: String?,
        folderId: String
    ): Boolean {
        if (loadGen != currentGen) return false
        if (folderId.isBlank()) return false
        return listedFolderId != null && listedFolderId.equals(folderId, ignoreCase = true)
    }
}

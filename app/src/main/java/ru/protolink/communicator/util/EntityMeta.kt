package ru.protolink.communicator.util

import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.CloudEntityClassify
import ru.protolink.communicator.data.api.EntityDto

/** Display helpers for Cloud entities used by messenger attachment / unfurl cards + Sync walk. */
object EntityMeta {
    fun displayName(entity: EntityDto): String {
        val values = entity.values ?: return fallbackName(entity)
        for (v in values) {
            val s = JsonValues.asText(v.value)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (v.parents?.any { it.equals(CloudCodes.NAME_TYPE_ID, true) } == true) return s
        }
        for (v in values) {
            val s = JsonValues.asText(v.value)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (v.parents?.any { it.equals(CloudCodes.MIME_TYPE_ID, true) } == true) continue
            return s
        }
        return fallbackName(entity)
    }

    fun mimeType(entity: EntityDto): String? {
        val values = entity.values ?: return null
        for (v in values) {
            if (v.parents?.any { it.equals(CloudCodes.MIME_TYPE_ID, true) } != true) continue
            return JsonValues.asText(v.value)?.trim()?.takeIf { it.isNotEmpty() }
        }
        return null
    }

    fun typeLabel(entity: EntityDto): String = when {
        CloudEntityClassify.isFolder(entity.typeEntityId, entity.code) -> "Folder"
        CloudEntityClassify.isFile(entity.typeEntityId, entity.code) -> "File"
        else -> entity.code?.takeIf { it.isNotBlank() } ?: "Entity"
    }

    /** @deprecated Prefer [typeLabel] with EntityDto (TypeEntityId-aware). */
    fun typeLabel(code: String?): String = when (code) {
        CloudCodes.CLOUD_FOLDER, CloudCodes.CLOUD_ROOT -> "Folder"
        CloudCodes.CLOUD_FILE -> "File"
        else -> code?.takeIf { it.isNotBlank() } ?: "Entity"
    }

    /**
     * True when [displayName] is the synthetic fallback for a CloudFile with no stored name
     * (`file-{id}`). Sync must not treat that as a real on-disk name (Win parity —
     * [CloudApiService.IsSyntheticCloudFileDisplayName]). Folders keep `folder-{id}` and are walked.
     */
    fun isSyntheticCloudFileDisplayName(entity: EntityDto, displayName: String): Boolean =
        CloudEntityClassify.isFile(entity.typeEntityId, entity.code) &&
            displayName.equals(fallbackName(entity.id, CloudCodes.CLOUD_FILE), ignoreCase = true)

    fun fallbackName(entity: EntityDto): String {
        val code = entity.code
        // Code=display name (Api) — do not replace with folder-/file-{id}.
        if (!code.isNullOrBlank() &&
            code != CloudCodes.CLOUD_FILE &&
            code != CloudCodes.CLOUD_FOLDER &&
            code != CloudCodes.CLOUD_ROOT
        ) {
            return code
        }
        val compact = entity.id.replace("-", "")
        return when {
            CloudEntityClassify.isFolder(entity.typeEntityId, code) &&
                !CloudEntityClassify.isCloudRootCode(code) -> "folder-$compact"
            CloudEntityClassify.isFile(entity.typeEntityId, code) -> "file-$compact"
            CloudEntityClassify.isCloudRootCode(code) -> "folder-$compact"
            else -> "file-$compact"
        }
    }

    /** Win-aligned: folders → `folder-{id}`; files → `file-{id}` when no name slot. */
    fun fallbackName(id: String, code: String?): String {
        val compact = id.replace("-", "")
        return when (code) {
            CloudCodes.CLOUD_FOLDER, CloudCodes.CLOUD_ROOT -> "folder-$compact"
            CloudCodes.CLOUD_FILE -> "file-$compact"
            else -> {
                if (!code.isNullOrBlank()) code else "file-$compact"
            }
        }
    }

    /**
     * Win [CloudApiService.ShouldReassertDisplayName]: real user name to write/re-write as
     * NAME_TYPE — not blank and not the synthetic folder-/file-{id} fallback.
     */
    fun shouldReassertDisplayName(entityId: String, displayName: String?, isFolder: Boolean): Boolean {
        val trimmed = displayName?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val code = if (isFolder) CloudCodes.CLOUD_FOLDER else CloudCodes.CLOUD_FILE
        return !trimmed.equals(fallbackName(entityId, code), ignoreCase = true)
    }
}

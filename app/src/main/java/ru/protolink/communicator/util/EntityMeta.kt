package ru.protolink.communicator.util

import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.api.EntityDto

/** Display helpers for Cloud entities used by messenger attachment / unfurl cards + Sync walk. */
object EntityMeta {
    fun displayName(entity: EntityDto): String {
        val values = entity.values ?: return fallbackName(entity.id, entity.code)
        for (v in values) {
            val s = JsonValues.asText(v.value)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (v.parents?.any { it.equals(CloudCodes.NAME_TYPE_ID, true) } == true) return s
        }
        for (v in values) {
            val s = JsonValues.asText(v.value)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (v.parents?.any { it.equals(CloudCodes.MIME_TYPE_ID, true) } == true) continue
            return s
        }
        return fallbackName(entity.id, entity.code)
    }

    fun mimeType(entity: EntityDto): String? {
        val values = entity.values ?: return null
        for (v in values) {
            if (v.parents?.any { it.equals(CloudCodes.MIME_TYPE_ID, true) } != true) continue
            return JsonValues.asText(v.value)?.trim()?.takeIf { it.isNotEmpty() }
        }
        return null
    }

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
        entity.code == CloudCodes.CLOUD_FILE &&
            displayName.equals(fallbackName(entity.id, CloudCodes.CLOUD_FILE), ignoreCase = true)

    /** Win-aligned: folders → `folder-{id}`; files → `file-{id}` when no name slot. */
    fun fallbackName(id: String, code: String?): String {
        val compact = id.replace("-", "")
        return when (code) {
            CloudCodes.CLOUD_FOLDER, CloudCodes.CLOUD_ROOT -> "folder-$compact"
            else -> "file-$compact"
        }
    }
}

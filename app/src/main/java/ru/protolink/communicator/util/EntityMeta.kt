package ru.protolink.communicator.util

import ru.protolink.communicator.data.CloudCodes
import ru.protolink.communicator.data.api.EntityDto

/** Display helpers for Cloud entities used by messenger attachment / unfurl cards. */
object EntityMeta {
    fun displayName(entity: EntityDto): String {
        val values = entity.values ?: return fallbackName(entity.id)
        for (v in values) {
            val s = JsonValues.asText(v.value)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (v.parents?.any { it.equals(CloudCodes.NAME_TYPE_ID, true) } == true) return s
        }
        for (v in values) {
            val s = JsonValues.asText(v.value)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (v.parents?.any { it.equals(CloudCodes.MIME_TYPE_ID, true) } == true) continue
            return s
        }
        return fallbackName(entity.id)
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

    private fun fallbackName(id: String): String = "file-${id.replace("-", "")}"
}

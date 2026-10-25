package ru.protolink.communicator.util

import com.google.gson.JsonElement
import ru.protolink.communicator.data.api.EntityDto
import ru.protolink.communicator.data.api.EntityValueDto

/**
 * E7 Photos library pointer on Photos host (`photosfolder:{guid}`).
 * Parity with Windows PhotosLibrary.
 */
object PhotosLibrary {
    const val HOST_CODE = "Photos"
    const val FOLDER_PREFIX = "photosfolder:"

    /** Parse library CloudFolder id from Photos host string values. */
    fun parseFolderId(values: List<EntityValueDto>?): String? {
        if (values.isNullOrEmpty()) return null
        for (v in values) {
            val raw = valueAsString(v.value) ?: continue
            if (!raw.startsWith(FOLDER_PREFIX, ignoreCase = true)) continue
            val idPart = raw.substring(FOLDER_PREFIX.length).trim()
            if (isGuid(idPart)) return idPart
        }
        return null
    }

    /** Find Photos host among user children and return its library folder id. */
    fun resolveLibraryIdFromUserChildren(userChildren: List<EntityDto>?): String? {
        if (userChildren.isNullOrEmpty()) return null
        for (e in userChildren) {
            if (!e.code.equals(HOST_CODE, ignoreCase = false)) continue
            val id = parseFolderId(e.values)
            if (id != null) return id
        }
        return null
    }

    private fun valueAsString(value: JsonElement?): String? =
        JsonValues.asText(value)?.trim()?.takeIf { it.isNotEmpty() }

    private fun isGuid(s: String): Boolean {
        if (s.isBlank()) return false
        // Accept standard Guid D form (with hyphens).
        val parts = s.split('-')
        if (parts.size != 5) return false
        return parts[0].length == 8 &&
            parts[1].length == 4 &&
            parts[2].length == 4 &&
            parts[3].length == 4 &&
            parts[4].length == 12 &&
            s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == '-' }
    }
}

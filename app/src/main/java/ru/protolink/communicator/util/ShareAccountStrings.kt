package ru.protolink.communicator.util

import java.util.UUID

/** Account / share chrome (share-sheet-ux §4 Copy my ID). Parity with Windows ShareAccountStrings. */
object ShareAccountStrings {
    fun copyMyIdLabel(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang)) "Скопировать мой ID" else "Copy my ID"

    fun copyMyIdHint(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang))
            "Отправьте это тем, кто хочет дать вам доступ"
        else
            "Share this with people who want to give you access"

    fun copiedLabel(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang)) "Скопировано" else "Copied"

    /** Messenger: copy another contact’s user Guid (not own Copy my ID). */
    fun copyContactIdLabel(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang)) "Скопировать ID контакта" else "Copy contact id"

    /** Messenger: open contact’s public user home in the browser. */
    fun contactInformationLabel(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang)) "Информация о контакте" else "Contact information"

    /** Cloud / Notes: copy entity Guid (raw D), not the canonical URL. */
    fun copyIdLabel(lang: String?): String =
        if (ShareSheetCloudStrings.isRussian(lang)) "Копировать id" else "Copy id"

    /** D-format Guid for Add contact / Share paste / Cloud Copy id; null when unresolved. */
    fun formatUserIdForClipboard(userId: String?): String? {
        val raw = userId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            val uuid = UUID.fromString(raw)
            if (uuid.mostSignificantBits == 0L && uuid.leastSignificantBits == 0L) null
            else uuid.toString()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Entity Guid D for Cloud Copy id; same gate as [formatUserIdForClipboard]. */
    fun formatEntityIdForClipboard(entityId: String?): String? = formatUserIdForClipboard(entityId)
}

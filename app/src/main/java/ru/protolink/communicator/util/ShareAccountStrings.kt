package ru.protolink.communicator.util

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
}

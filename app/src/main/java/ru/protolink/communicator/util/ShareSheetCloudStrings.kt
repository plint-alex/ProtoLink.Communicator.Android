package ru.protolink.communicator.util

/**
 * RU/EN Cloud Shared-with-me strings (UX §7 / AT-S17 / AT-S42).
 * Parity with Windows ShareSheetStrings Shared-with-me block.
 */
object ShareSheetCloudStrings {
    fun isRussian(lang: String?): Boolean {
        if (lang.isNullOrBlank()) return false
        val t = lang.trim().lowercase()
        return t == "ru" || t.startsWith("ru-") || t.startsWith("ru_")
    }

    fun sharedWithMe(lang: String?): String =
        if (isRussian(lang)) "Доступные мне" else "Shared with me"

    fun myCloud(lang: String?): String =
        if (isRussian(lang)) "Моё облако" else "My Cloud"

    fun cloudHub(lang: String?): String =
        if (isRussian(lang)) "Облако" else "Cloud"

    fun emptySharedTitle(lang: String?): String =
        if (isRussian(lang)) "Вам пока ничего не открыли" else "Nothing shared with you yet"

    fun emptySharedBody(lang: String?): String =
        if (isRussian(lang))
            "Когда кто-то даст вам доступ, это появится здесь."
        else
            "When someone gives you access, it shows up here."

    fun emptyFolderTitle(lang: String?): String =
        if (isRussian(lang)) "Папка пуста" else "This folder is empty"

    fun emptyFolderBody(lang: String?): String =
        if (isRussian(lang))
            "Загрузите файл или создайте папку, чтобы начать."
        else
            "Upload a file or create a folder to get started."

    fun canView(lang: String?): String =
        if (isRussian(lang)) "Просмотр" else "Can view"

    fun canEdit(lang: String?): String =
        if (isRussian(lang)) "Редактирование" else "Can edit"

    fun sharedBy(lang: String?, name: String): String =
        if (isRussian(lang)) "Поделился(ась) $name" else "Shared by $name"

    fun sharedRowSubtitle(lang: String?, creatorName: String, canWrite: Boolean): String {
        val by = sharedBy(lang, creatorName)
        val level = if (canWrite) canEdit(lang) else canView(lang)
        return "$by · $level"
    }

    /** Device wording (Android); Windows uses "this PC". */
    fun browseOnlyHint(lang: String?): String =
        if (isRussian(lang))
            "Только просмотр — синхронизация общих папок с устройством пока недоступна."
        else
            "Browse only — mapping shared folders to this device is not available yet."

    fun mapFolder(lang: String?): String =
        if (isRussian(lang)) "Привязать папку" else "Map folder"

    fun unmap(lang: String?): String =
        if (isRussian(lang)) "Отвязать" else "Unmap"
}

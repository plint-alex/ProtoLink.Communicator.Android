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

    /** Shared by me hub row (AT-S54 / shared-by-me spec). */
    fun sharedByMe(lang: String?): String =
        if (isRussian(lang)) "Я поделился" else "Shared by me"

    fun myCloud(lang: String?): String =
        if (isRussian(lang)) "Моё облако" else "My Cloud"

    fun cloudHub(lang: String?): String =
        if (isRussian(lang)) "Облако" else "Cloud"

    /** Cloud hub row → Photos library folder (E7 / AT-PC). */
    fun photos(lang: String?): String =
        if (isRussian(lang)) "Фото" else "Photos"

    /** Persistent folder tree heading (AT-PC1 / AT-PC8). */
    fun foldersTree(lang: String?): String =
        if (isRussian(lang)) "Папки" else "Folders"

    /** C6 Recent hub row (EN/RU). */
    fun recent(lang: String?): String =
        if (isRussian(lang)) "Недавние" else "Recent"

    fun emptySharedTitle(lang: String?): String =
        if (isRussian(lang)) "Вам пока ничего не открыли" else "Nothing shared with you yet"

    fun emptySharedBody(lang: String?): String =
        if (isRussian(lang))
            "Когда кто-то даст вам доступ, это появится здесь."
        else
            "When someone gives you access, it shows up here."

    /** Shared by me empty (AC-SO Communicator copy). */
    fun emptySharedByMeTitle(lang: String?): String =
        if (isRussian(lang)) "Пока ничем не поделились" else "Nothing shared yet"

    fun emptySharedByMeBody(lang: String?): String =
        if (isRussian(lang))
            "Когда вы откроете доступ, это появится здесь."
        else
            "When you give someone access, it shows up here."

    fun sharedByMeLoadFailed(lang: String?): String =
        if (isRussian(lang)) "Не удалось загрузить «Я поделился»." else "Couldn't load Shared by me."

    fun emptyRecentTitle(lang: String?): String =
        if (isRussian(lang)) "Пока нет недавних" else "No recent items yet"

    fun emptyRecentBody(lang: String?): String =
        if (isRussian(lang))
            "Откройте папку в облаке — она появится здесь."
        else
            "Open a Cloud folder and it will show up here."

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

    // --- Leave Shared with me (UX §7b / UX-LS-*) ---

    fun leave(lang: String?): String =
        if (isRussian(lang)) "Покинуть" else "Leave"

    /** Long form for header overflow when space is tight on the row. */
    fun leaveMenu(lang: String?): String =
        if (isRussian(lang)) "Убрать из «Доступные мне»" else "Remove from Shared with me"

    fun leaveConfirm(lang: String?): String =
        if (isRussian(lang))
            "Покинуть общий элемент? Доступ пропадет, пока вам снова не откроют."
        else
            "Leave this shared item? You lose access until someone shares it again."

    fun leaveSuccess(lang: String?): String =
        if (isRussian(lang)) "Убрано из «Доступные мне»" else "Removed from Shared with me"

    fun leaveFailed(lang: String?): String =
        if (isRussian(lang)) "Не удалось покинуть. Повторите." else "Couldn't leave. Try again."
}

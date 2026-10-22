package ru.protolink.communicator.util

/**
 * RU/EN labels for E6 “Add local folder to Cloud…” wizard / hub CTA / Notes nudge.
 * Parity with Windows MapLocalFolderStrings.
 */
object MapLocalFolderStrings {
    fun isRussian(lang: String?): Boolean = ShareSheetCloudStrings.isRussian(lang)

    fun hubCta(lang: String?): String =
        if (isRussian(lang)) "Добавить локальную папку в облако…" else "Add local folder to Cloud…"

    fun mapThisFolder(lang: String?): String =
        if (isRussian(lang))
            "Сопоставить эту папку облака с локальной…"
        else
            "Map this Cloud folder to a local folder…"

    fun notesNudgeText(lang: String?): String =
        if (isRussian(lang))
            "Ещё не в облаке — добавить эту папку в Cloud…"
        else
            "Not in Cloud yet — Add this folder to Cloud…"

    fun wizardTitle(lang: String?): String =
        if (isRussian(lang)) "Локальная папка → Cloud" else "Local folder → Cloud"

    fun localFolderLabel(lang: String?): String =
        if (isRussian(lang)) "Локальная папка" else "Local folder"

    fun changeFolder(lang: String?): String =
        if (isRussian(lang)) "Изменить…" else "Change…"

    fun destinationLabel(lang: String?): String =
        if (isRussian(lang)) "Папка в Cloud" else "Cloud destination"

    fun createNewOption(lang: String?): String =
        if (isRussian(lang)) "Создать новую папку" else "Create new Cloud folder"

    fun pickExistingOption(lang: String?): String =
        if (isRussian(lang)) "Выбрать существующую" else "Pick existing unmapped folder"

    fun folderNameLabel(lang: String?): String =
        if (isRussian(lang)) "Имя папки" else "Folder name"

    fun confirmWarn(lang: String?): String =
        if (isRussian(lang))
            "Первая синхронизация сверит обе стороны. Конфликты решаются через «Загрузить всё» / «Скачать всё»."
        else
            "First sync reconciles both sides. Conflicts may need Upload all / Download all."

    fun confirm(lang: String?): String =
        if (isRussian(lang)) "Сопоставить и синхронизировать" else "Map and sync"

    fun cancel(lang: String?): String =
        if (isRussian(lang)) "Отмена" else "Cancel"

    fun localMissing(lang: String?): String =
        if (isRussian(lang))
            "Папка не найдена или недоступна."
        else
            "Folder does not exist or is not accessible."

    fun noUnmappedFolders(lang: String?): String =
        if (isRussian(lang))
            "Нет незамапленных папок здесь. Создайте новую."
        else
            "No unmapped folders here. Create a new one."

    fun emptyMappingsHint(lang: String?): String =
        if (isRussian(lang))
            "Нет сопоставлений. Cloud → «Добавить локальную папку в облако…»"
        else
            "No mapped folders. Cloud → Add local folder to Cloud…"

    fun suggestedFolderNameFromUri(uriString: String): String {
        val uri = runCatching { android.net.Uri.parse(uriString) }.getOrNull() ?: return "Folder"
        val last = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        if (!last.isNullOrBlank()) {
            return try {
                java.net.URLDecoder.decode(last, Charsets.UTF_8.name())
            } catch (_: Exception) {
                last
            }
        }
        return "Folder"
    }
}

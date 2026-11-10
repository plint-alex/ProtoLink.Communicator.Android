package ru.protolink.communicator.util

/**
 * RU/EN contentDescriptions for Android Notes selection format bar (UX-N-A4).
 * Mirrors Windows NotesFormatBarStrings without desktop shortcut suffixes.
 */
object NotesFormatBarStrings {
    private fun ru(lang: String?) = ShareSheetCloudStrings.isRussian(lang)

    fun bold(lang: String?) = if (ru(lang)) "Жирный" else "Bold"

    fun italic(lang: String?) = if (ru(lang)) "Курсив" else "Italic"

    fun underline(lang: String?) = if (ru(lang)) "Подчёркнутый" else "Underline"

    fun strikethrough(lang: String?) = if (ru(lang)) "Зачёркнутый" else "Strikethrough"

    fun textStyle(lang: String?) = if (ru(lang)) "Стиль текста" else "Text style"

    fun bulletList(lang: String?) = if (ru(lang)) "Маркированный список" else "Bullet list"

    fun numberedList(lang: String?) = if (ru(lang)) "Нумерованный список" else "Numbered list"

    fun checkboxList(lang: String?) = if (ru(lang)) "Список с флажками" else "Checkbox list"

    fun outdent(lang: String?) = if (ru(lang)) "Уменьшить отступ" else "Decrease indent"

    fun indent(lang: String?) = if (ru(lang)) "Увеличить отступ" else "Increase indent"

    fun insertLink(lang: String?) = if (ru(lang)) "Вставить ссылку" else "Insert link"
}

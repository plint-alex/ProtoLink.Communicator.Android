package ru.protolink.communicator.util

/**
 * RU/EN messenger attach-menu labels (messenger-media UX §7).
 * Order on Android: Camera · Gallery · File… · Choose from Cloud…
 */
object MessengerAttachMenuStrings {
    private fun ru(lang: String?) = ShareSheetCloudStrings.isRussian(lang)

    fun camera(lang: String?) = if (ru(lang)) "Камера" else "Camera"

    fun gallery(lang: String?) = if (ru(lang)) "Галерея" else "Gallery"

    fun file(lang: String?) = if (ru(lang)) "Файл…" else "File…"

    fun chooseFromCloud(lang: String?) =
        if (ru(lang)) "Выбрать из облака…" else "Choose from Cloud…"

    /** Ordered menu keys for unit tests / wiring checks. */
    fun orderedLabels(lang: String?): List<String> =
        listOf(camera(lang), gallery(lang), file(lang), chooseFromCloud(lang))
}

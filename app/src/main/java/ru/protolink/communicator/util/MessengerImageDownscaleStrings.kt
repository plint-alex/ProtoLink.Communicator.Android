package ru.protolink.communicator.util

/** Settings toggle copy for cycle-2 M6 image downscale (EN / RU). */
object MessengerImageDownscaleStrings {
    private fun ru(lang: String?): Boolean = ShareSheetCloudStrings.isRussian(lang)

    fun toggleTitle(lang: String?): String =
        if (ru(lang)) "Уменьшать фото перед отправкой" else "Reduce photo size before send"

    fun toggleHint(lang: String?): String =
        if (ru(lang))
            "Камера, Галерея и изображения из Файл… — до 2048 px, JPEG"
        else
            "Camera, Gallery, and File… images — max 2048 px, JPEG"
}

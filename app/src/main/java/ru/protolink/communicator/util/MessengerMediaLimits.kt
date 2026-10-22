package ru.protolink.communicator.util

/** Client pre-checks from messenger-media client-contract §7 / M11. */
object MessengerMediaLimits {
    /** 100 MiB — matches server default Files:MaxUploadBytes. */
    const val MaxAttachmentBytes = 104_857_600L

    const val MaxAttachmentsPerMessage = 10

    /** M11 / UX §7 — refuse before upload. */
    const val OversizeMessageEn = "Files up to 100 MB can be sent"

    const val OfflineSendFailedEn = "Couldn't send"
    const val UploadFailedEn = "Couldn't send"
    const val CapRefuseEn = "Up to 10 files per message"
    const val CameraPermissionDeniedEn = "Camera access is needed to take a photo"

    /** Bubble thumbnail long side (logical px); multiply by device density at request time. */
    const val BubbleImageLongSideLogical = 480

    /** Lightbox preview long side (logical px). */
    const val LightboxImageLongSideLogical = 1600

    fun isOversize(byteLength: Long): Boolean = byteLength > MaxAttachmentBytes

    fun formatAttachmentValue(entityId: String): String =
        "${MessageStringValues.ATTACHMENT_PREFIX}${entityId.lowercase()}"
}

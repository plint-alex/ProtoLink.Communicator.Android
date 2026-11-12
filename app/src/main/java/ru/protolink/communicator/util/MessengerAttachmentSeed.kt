package ru.protolink.communicator.util

/**
 * Client-side seed for a just-sent attachment so the bubble can show a File row
 * (name + size + Download) before / without waiting on attachment GetEntities settle.
 */
data class MessengerAttachmentSeed(
    val fileName: String,
    val mimeType: String?,
    val byteLength: Long?
)

object MessengerAttachmentSeedLogic {
    /**
     * Non-image seeds start as [MessengerAttachmentVisualKind.File] immediately so File…
     * PDF send never flashes an empty Loading box after Sent.
     * Image seeds stay Loading until getImage probe (thumb required).
     */
    fun initialKind(seed: MessengerAttachmentSeed?, forceFileRow: Boolean = false): MessengerAttachmentVisualKind {
        if (seed == null) return MessengerAttachmentVisualKind.Loading
        return MessengerAttachmentRender.initialKind(seed.mimeType, forceFileRow = forceFileRow)
    }

    fun displayFileName(seed: MessengerAttachmentSeed?, fallback: String = "…"): String {
        val name = seed?.fileName?.trim().orEmpty()
        return name.ifBlank { fallback }
    }
}

package ru.protolink.communicator.data.messenger

/**
 * Local compose draft for one attachment (camera / gallery / File… SAF / M3 Cloud pick).
 * [localUri] is a content:// or file:// the app can read until send completes
 * (empty for [isCloudPick] — entity already in Cloud).
 */
data class PendingAttachmentDraft(
    val localUri: String,
    val fileName: String,
    val mimeType: String,
    val byteLength: Long,
    var cloudFileEntityId: String? = null,
    var uploadCompleted: Boolean = false,
    /** Local path for camera captures (also mirrored in localUri as file://). */
    val localFilePath: String? = null,
    /** M3 — existing CloudFile referenced in place (no addFile / no chat-folder parent). */
    val isCloudPick: Boolean = false
)

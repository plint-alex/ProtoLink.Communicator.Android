package ru.protolink.communicator.data

object CloudCodes {
    const val CLOUD_ROOT = "CloudRoot"
    const val CLOUD_FOLDER = "CloudFolder"
    const val CLOUD_FILE = "CloudFile"
    const val NAME_TYPE_ID = "00010003-0000-0000-0000-000000000000"
    const val MIME_TYPE_ID = "00010012-0000-0000-0000-000000000000"
}

object SystemEntities {
    const val CONTACTS = "00020000-0000-0000-0000-000000000000"
    const val MESSAGE = "00020001-0000-0000-0000-000000000000"
    const val SENT = "00020002-0000-0000-0000-000000000000"
    const val RECEIVED = "00020003-0000-0000-0000-000000000000"
}

data class TokenData(
    val accessToken: String,
    val refreshToken: String?,
    val expirationTime: String?,
    val userId: String,
    val login: String
)

data class AppSettings(
    val theme: String = "Light",
    val apiBaseAddress: String = "http://protolink.ru/",
    val publicSiteBaseUrl: String = "https://protolink.ru/",
    val notesRootUri: String? = null,
    /** Cache: "ownerUserId:partnerUserId" → chat folder entity id (client-contract §4). */
    val messengerChatFolderIds: Map<String, String> = emptyMap(),
    /**
     * Cycle-2 M6 — reduce Camera/Gallery/File… images before upload (default ON).
     * Gson missing-key → false; [SettingsStore.load] migrates absent key to true.
     */
    val reducePhotoSizeBeforeSend: Boolean = true,
    /**
     * Win LastOpenCloudEntityId parity — preferred My Cloud when duplicate CloudRoots exist
     * under the user (page-1 empty twin vs healthy AT root on later GetEntities pages).
     */
    val lastOpenCloudEntityId: String? = null
)

data class CloudSyncMappingDto(
    val cloudFolderId: String,
    val localPath: String,
    val cloudFolderName: String = ""
)

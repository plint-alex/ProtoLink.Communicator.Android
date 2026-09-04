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
    /** When true, sync compares local/remote size and update time at start and chooses upload (write) or download (read). */
    val compareSizeAndTimeOnSync: Boolean = true
)

data class CloudSyncMappingDto(
    val cloudFolderId: String,
    val localPath: String,
    val cloudFolderName: String = ""
)

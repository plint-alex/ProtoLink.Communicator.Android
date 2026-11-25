package ru.protolink.communicator.data

/**
 * Folder vs file from typeEntityId (Code = display name). Legacy Code discriminators remain fallback.
 * Parity with Windows CloudEntityClassify.
 */
object CloudEntityClassify {
    /** Api seed typeCloudFolder (CloudTreeSeedIds.CloudFolderTypeEntityId). */
    const val TYPE_CLOUD_FOLDER_ID = "b0000005-0000-4000-8000-000000000001"

    /** Api seed typeCloudFile (CloudTreeSeedIds.CloudFileTypeEntityId). */
    const val TYPE_CLOUD_FILE_ID = "b0000005-0000-4000-8000-000000000002"

    fun isFolder(typeEntityId: String?, code: String?): Boolean {
        val tid = typeEntityId?.trim()?.lowercase().orEmpty()
        if (tid == TYPE_CLOUD_FOLDER_ID) return true
        if (tid == TYPE_CLOUD_FILE_ID) return false
        return code == CloudCodes.CLOUD_FOLDER || code == CloudCodes.CLOUD_ROOT
    }

    fun isFile(typeEntityId: String?, code: String?): Boolean {
        val tid = typeEntityId?.trim()?.lowercase().orEmpty()
        if (tid == TYPE_CLOUD_FILE_ID) return true
        if (tid == TYPE_CLOUD_FOLDER_ID) return false
        return code.equals(CloudCodes.CLOUD_FILE, ignoreCase = true)
    }

    /** Cloud folder or file row (sync/delete walk). Skips unrelated entities. */
    fun isCloudFolderOrFile(typeEntityId: String?, code: String?): Boolean =
        isFolder(typeEntityId, code) || isFile(typeEntityId, code)

    fun isCloudRootCode(code: String?): Boolean =
        code.equals(CloudCodes.CLOUD_ROOT, ignoreCase = true)
}

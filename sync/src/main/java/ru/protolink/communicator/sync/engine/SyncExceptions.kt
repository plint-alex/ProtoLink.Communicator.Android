package ru.protolink.communicator.sync.engine

open class SyncException(
    message: String,
    val relativePath: String? = null,
    val localHash: String? = null,
    val remoteHash: String? = null,
    val metaHash: String? = null,
    cause: Throwable? = null
) : Exception(format(message, relativePath, localHash, remoteHash, metaHash), cause) {
    companion object {
        private fun format(
            message: String,
            relativePath: String?,
            localHash: String?,
            remoteHash: String?,
            metaHash: String?
        ): String {
            val parts = mutableListOf(message)
            if (!relativePath.isNullOrEmpty()) parts += "path=$relativePath"
            if (!localHash.isNullOrEmpty()) parts += "local=${localHash.take(12)}…"
            if (!remoteHash.isNullOrEmpty()) parts += "remote=${remoteHash.take(12)}…"
            if (!metaHash.isNullOrEmpty()) parts += "meta=${metaHash.take(12)}…"
            return parts.joinToString(" | ")
        }
    }
}

class SyncConflictException(
    relativePath: String,
    localHash: String,
    remoteHash: String,
    metaHash: String?,
    reason: String
) : SyncException(reason, relativePath, localHash, remoteHash, metaHash)

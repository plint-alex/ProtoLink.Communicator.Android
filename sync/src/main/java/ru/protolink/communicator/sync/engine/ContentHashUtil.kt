package ru.protolink.communicator.sync.engine

import java.security.MessageDigest
import java.time.Instant

object ContentHashUtil {
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Hash local bytes, or null when the path is already gone (NFR-SYNC-1).
     * Does not swallow other IO errors.
     */
    fun trySha256Hex(readBytes: () -> ByteArray): String? =
        try {
            sha256Hex(readBytes())
        } catch (e: Exception) {
            if (SyncMissingLocalPath.isMissing(e)) null else throw e
        }

    /** True when meta already has a hash and local bytes differ. */
    fun isLocalContentChanged(metaHash: String?, localHash: String?): Boolean {
        if (metaHash.isNullOrEmpty() || localHash.isNullOrEmpty()) return false
        return !metaHash.equals(localHash, ignoreCase = true)
    }

    /**
     * Skip getFile probe when local bytes still match the last-sync baseline and the remote
     * listing is not newer. Win [CanSkipRemoteContentDownload] parity.
     */
    fun canSkipRemoteContentDownload(
        localHash: String?,
        metaHash: String?,
        remoteUpdateTime: Instant?,
        metaUpdateTime: Instant?
    ): Boolean {
        if (localHash.isNullOrEmpty() || metaHash.isNullOrEmpty()) return false
        if (!localHash.equals(metaHash, ignoreCase = true)) return false
        return !isRemoteListingNewer(remoteUpdateTime, metaUpdateTime)
    }

    /**
     * ApplyCompare fast path: size still matches meta, baseline hash present, remote listing not
     * newer → skip SHA-256 of local bytes (and getFile). Win [CanSkipCompareWithoutHash] parity.
     */
    fun canSkipCompareWithoutHash(
        localSize: Long,
        metaSize: Long,
        metaHash: String?,
        remoteUpdateTime: Instant?,
        metaUpdateTime: Instant?
    ): Boolean {
        if (localSize != metaSize) return false
        if (metaHash.isNullOrEmpty()) return false
        return !isRemoteListingNewer(remoteUpdateTime, metaUpdateTime)
    }

    /**
     * True when the remote listing UpdateTime is strictly newer than the meta baseline.
     * Null remote UpdateTime → not newer (trust baseline; Force Download repairs).
     */
    fun isRemoteListingNewer(remoteUpdateTime: Instant?, metaUpdateTime: Instant?): Boolean {
        val remote = remoteUpdateTime ?: return false
        val meta = metaUpdateTime ?: return true
        return remote.isAfter(meta)
    }

    /**
     * Convergence decision once both local and remote content hashes are known.
     * Never call with an empty remoteHash — download first; on download failure soft-fail (caller).
     * Ambiguous cases (empty meta, both diverged) → Read (cloud wins) so every device
     * mapped to the same CloudFolderId ends with the same bytes (delete/overwrite OK).
     */
    internal fun decideByHash(
        localHash: String?,
        remoteHash: String,
        metaHash: String?,
        remoteUpdateTime: Instant? = null,
        metaUpdateTime: Instant? = null
    ): SyncDirectionDecide.Action {
        if (localHash.isNullOrEmpty()) return SyncDirectionDecide.Action.Read
        require(remoteHash.isNotEmpty()) { "remoteHash required; download remote bytes before decideByHash" }

        if (localHash.equals(remoteHash, ignoreCase = true)) return SyncDirectionDecide.Action.Skip

        val metaEmpty = metaHash.isNullOrEmpty()
        val localMatchesMeta = !metaEmpty && localHash.equals(metaHash, ignoreCase = true)
        val remoteMatchesMeta = !metaEmpty && remoteHash.equals(metaHash, ignoreCase = true)

        if (localMatchesMeta && !remoteMatchesMeta) return SyncDirectionDecide.Action.Read
        if (remoteMatchesMeta && !localMatchesMeta) return SyncDirectionDecide.Action.Write

        // Empty baseline or both sides diverged — cloud is the shared truth across devices.
        @Suppress("UNUSED_VARIABLE")
        val unusedTimes = remoteUpdateTime to metaUpdateTime
        return SyncDirectionDecide.Action.Read
    }
}

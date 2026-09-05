package ru.protolink.communicator.sync.engine

import java.security.MessageDigest
import java.time.Instant

object ContentHashUtil {
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** True when meta already has a hash and local bytes differ. */
    fun isLocalContentChanged(metaHash: String?, localHash: String?): Boolean {
        if (metaHash.isNullOrEmpty() || localHash.isNullOrEmpty()) return false
        return !metaHash.equals(localHash, ignoreCase = true)
    }

    /**
     * Convergence decision once both local and remote content hashes are known.
     * Never call with an empty remoteHash — download first; on download failure throw.
     * Ambiguous cases (empty meta, both diverged) → Conflict (no silent LWW / Write bias).
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

        // Empty baseline or both sides diverged — do not guess.
        @Suppress("UNUSED_VARIABLE")
        val unusedTimes = remoteUpdateTime to metaUpdateTime
        return SyncDirectionDecide.Action.Conflict
    }
}

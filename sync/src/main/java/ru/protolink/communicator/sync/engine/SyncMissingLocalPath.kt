package ru.protolink.communicator.sync.engine

import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException

/**
 * NFR-SYNC-1: mapped Notes/Cloud item whose local path vanished mid-pass
 * (FileNotFound / DirectoryNotFound / SAF Missing) must soft-fail per item — not abort the sync.
 * Win parity: [SyncMissingLocalPath] / [ContentHashUtil.TrySha256HexFile].
 */
object SyncMissingLocalPath {
    fun isMissing(ex: Throwable): Boolean = when (ex) {
        is FileNotFoundException, is NoSuchFileException -> true
        is IllegalStateException -> {
            val m = ex.message.orEmpty()
            m.startsWith("Missing ") || m.startsWith("Missing file ")
        }
        else -> false
    }

    fun log(phase: String, relativePath: String, fullPath: String, ex: Throwable? = null) {
        val detail = when {
            ex == null -> "path gone"
            else -> "${ex.javaClass.simpleName}: ${ex.message}"
        }
        System.err.println(
            "[SyncEngine] soft-fail missing local ($phase): relative=$relativePath full=$fullPath — $detail"
        )
    }
}

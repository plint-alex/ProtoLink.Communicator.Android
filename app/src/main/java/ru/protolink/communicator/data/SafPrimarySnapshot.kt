package ru.protolink.communicator.data

import ru.protolink.communicator.sync.engine.ContentHashUtil
import ru.protolink.communicator.sync.engine.PathUtil
import ru.protolink.communicator.sync.model.FsEntry
import java.io.File

/**
 * Win [SyncEngine.SnapshotFs] cost model for primary-external mapped roots.
 *
 * Windows uses `Directory.GetDirectories` / `GetFiles(…, AllDirectories)` + [FileInfo.Length].
 * Android still walked every folder via DocumentsContract queries during Sync/Force — on
 * ~10k MyNotesFolder that never reached first Api (`Syncing 1 folder(s)…` sticky /
 * `FORCE_UPLOAD_STUCK_NO_PROGRESS` after tip 1.0.105 size-only COLUMN_SIZE).
 *
 * When [SafPrimaryPath] can resolve the tree, snapshot via [File] + [SafPrimaryPath.listChildNames]
 * (Os.listdir under scoped storage) — same decision table, SAF peculiarity only.
 */
object SafPrimarySnapshot {
    fun snapshot(root: File, computeContentHashes: Boolean): List<FsEntry> {
        if (!root.isDirectory && !root.exists()) return emptyList()
        val folderSizes = linkedMapOf<String, Long>()
        val files = mutableListOf<FsEntry>()

        fun rememberFolder(rel: String) {
            if (rel.isNotEmpty()) folderSizes.putIfAbsent(rel, 0L)
        }

        fun rollSizeIntoAncestors(fileRel: String, size: Long) {
            var parent = PathUtil.parentOf(fileRel)
            while (parent.isNotEmpty()) {
                folderSizes[parent] = (folderSizes[parent] ?: 0L) + size
                parent = PathUtil.parentOf(parent)
            }
        }

        fun isDirectoryLike(f: File): Boolean {
            if (f.isDirectory) return true
            if (f.isFile) return false
            // Os.listdir may return a name while File metadata is briefly stale.
            return runCatching {
                SafPrimaryPath.listChildNames(f).isNotEmpty() && !f.isFile
            }.getOrDefault(false)
        }

        fun walk(dir: File, rel: String) {
            if (rel.isNotEmpty()) rememberFolder(rel)
            val names = SafPrimaryPath.listChildNames(dir)
            for (rawName in names) {
                if (rawName == "." || rawName == "..") continue
                val nameKey = rawName.lowercase()
                val child = File(dir, rawName)
                val childRel = if (rel.isEmpty()) nameKey else "$rel/$nameKey"
                if (isDirectoryLike(child) && !child.isFile) {
                    rememberFolder(childRel)
                    walk(child, childRel)
                } else if (child.isFile) {
                    if (computeContentHashes) {
                        val hash = ContentHashUtil.trySha256Hex { child.readBytes() }
                        if (hash == null) continue
                        val len = child.length()
                        files.add(FsEntry(childRel, false, len, hash))
                        rollSizeIntoAncestors(childRel, len)
                    } else {
                        val len = child.length()
                        files.add(FsEntry(childRel, false, len, ""))
                        rollSizeIntoAncestors(childRel, len)
                    }
                } else {
                    // Name visible but neither file nor readable dir — folder stub (Win keeps dirs).
                    rememberFolder(childRel)
                }
            }
        }

        walk(root, "")
        val out = ArrayList<FsEntry>(files.size + folderSizes.size)
        out.addAll(files)
        for ((rel, size) in folderSizes) {
            out.add(FsEntry(rel, true, size))
        }
        return out
    }
}

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
 *
 * **No ghost folder stubs** (tip 1.0.112): Win GetDirectories/GetFiles never invent entries for
 * names that are neither a real dir nor a readable file. Promoting those names to folder stubs
 * blocked [LocalChange.Removed] after shell/SAF delete (tester FAIL_CLASS
 * `LOCAL_DELETE_NOT_REMOTE_DELETE` on 1.0.111) and made Force Upload see “folders only”.
 *
 * [skipRelativePath] matches Win SnapshotFs skip (AutoPush Agents queue exclude).
 */
object SafPrimarySnapshot {
    fun snapshot(
        root: File,
        computeContentHashes: Boolean,
        skipRelativePath: ((String) -> Boolean)? = null
    ): List<FsEntry> {
        if (!root.isDirectory && !root.exists()) return emptyList()
        val folderSizes = linkedMapOf<String, Long>()
        val files = mutableListOf<FsEntry>()

        fun rememberFolder(rel: String) {
            if (rel.isNotEmpty()) folderSizes.putIfAbsent(rel, 0L)
        }

        fun rollSizeIntoAncestors(fileRel: String, size: Long) {
            var parent = PathUtil.parentOf(fileRel)
            while (parent.isNotEmpty()) {
                if (skipRelativePath?.invoke(parent) == true) break
                folderSizes[parent] = (folderSizes[parent] ?: 0L) + size
                parent = PathUtil.parentOf(parent)
            }
        }

        fun addFile(childRel: String, child: File) {
            if (computeContentHashes) {
                val hash = ContentHashUtil.trySha256Hex { child.readBytes() }
                if (hash == null) return
                val len = child.length()
                files.add(FsEntry(childRel, false, len, hash))
                rollSizeIntoAncestors(childRel, len)
            } else {
                val len = child.length()
                files.add(FsEntry(childRel, false, len, ""))
                rollSizeIntoAncestors(childRel, len)
            }
        }

        fun walk(dir: File, rel: String) {
            if (rel.isNotEmpty()) {
                if (skipRelativePath?.invoke(rel) == true) return
                rememberFolder(rel)
            }
            val names = SafPrimaryPath.listChildNames(dir)
            for (rawName in names) {
                if (rawName == "." || rawName == "..") continue
                val nameKey = rawName.lowercase()
                val child = File(dir, rawName)
                val childRel = if (rel.isEmpty()) nameKey else "$rel/$nameKey"
                if (skipRelativePath?.invoke(childRel) == true) continue
                when (SafPrimaryPath.classifyPrimaryChild(child)) {
                    SafPrimaryPath.PrimaryChildKind.Directory -> {
                        rememberFolder(childRel)
                        walk(child, childRel)
                    }
                    SafPrimaryPath.PrimaryChildKind.File -> addFile(childRel, child)
                    SafPrimaryPath.PrimaryChildKind.Omit -> {
                        // Win SnapshotFs omits unlistable names — do not stub as folders.
                    }
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

package ru.protolink.communicator.sync.fakes

import ru.protolink.communicator.sync.engine.ContentHashUtil
import ru.protolink.communicator.sync.engine.PathUtil
import ru.protolink.communicator.sync.model.FsEntry
import ru.protolink.communicator.sync.model.RemoteEntry
import ru.protolink.communicator.sync.model.SyncItemMeta
import ru.protolink.communicator.sync.ports.LocalFileSystem
import ru.protolink.communicator.sync.ports.MetadataStore
import ru.protolink.communicator.sync.ports.RemoteCloud
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryMetadataStore : MetadataStore {
    private val items = ConcurrentHashMap<String, SyncItemMeta>() // key mappingId|path
    private val lastSync = ConcurrentHashMap<String, Instant>()

    private fun key(mappingId: String, path: String) = "$mappingId|${PathUtil.normalize(path)}"

    override fun getAll(mappingId: String): List<SyncItemMeta> =
        items.values.filter { it.mappingId == mappingId }

    override fun getAllRemoteIds(): Set<String> = items.values.map { it.remoteId }.toSet()

    override fun findByRemoteId(remoteId: String): SyncItemMeta? =
        items.values.firstOrNull { it.remoteId == remoteId }

    override fun upsert(item: SyncItemMeta) {
        items[key(item.mappingId, item.relativePath)] = item.copy(relativePath = PathUtil.normalize(item.relativePath))
    }

    override fun delete(mappingId: String, relativePath: String) {
        items.remove(key(mappingId, relativePath))
    }

    override fun deleteByRemoteId(remoteId: String) {
        items.entries.removeIf { it.value.remoteId == remoteId }
    }

    override fun getLastSyncUtc(mappingId: String): Instant? = lastSync[mappingId]

    override fun setLastSyncUtc(mappingId: String, time: Instant) {
        lastSync[mappingId] = time
    }

    override fun clearMapping(mappingId: String) {
        items.entries.removeIf { it.value.mappingId == mappingId }
        lastSync.remove(mappingId)
    }
}

/** In-memory FS. Folder sizeBytes = sum of descendant file lengths. */
class InMemoryFileSystem : LocalFileSystem {
    private val files = ConcurrentHashMap<String, ByteArray>() // root|rel -> bytes
    private val dirs = ConcurrentHashMap.newKeySet<String>() // root|rel

    private fun k(root: String, rel: String) = "${root.trimEnd('/', '\\')}|${PathUtil.normalize(rel)}"

    override fun snapshot(rootPath: String): List<FsEntry> {
        val prefix = "${rootPath.trimEnd('/', '\\')}|"
        val fileRels = files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        val dirRels = dirs.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.filter { it.isNotEmpty() }

        val result = mutableListOf<FsEntry>()
        for (rel in fileRels) {
            val bytes = files.getValue(prefix + rel)
            result.add(FsEntry(rel, false, bytes.size.toLong(), ContentHashUtil.sha256Hex(bytes)))
        }
        for (rel in dirRels) {
            val size = fileRels.filter { it == rel || it.startsWith("$rel/") }
                .sumOf { files.getValue(prefix + it).size.toLong() }
            result.add(FsEntry(rel, true, size))
        }
        return result
    }

    override fun createDirectory(rootPath: String, relativePath: String) {
        val n = PathUtil.normalize(relativePath)
        if (n.isEmpty()) return
        var acc = ""
        for (part in n.split('/')) {
            acc = if (acc.isEmpty()) part else "$acc/$part"
            dirs.add(k(rootPath, acc))
        }
    }

    override fun writeFile(rootPath: String, relativePath: String, bytes: ByteArray) {
        val parent = PathUtil.parentOf(relativePath)
        if (parent.isNotEmpty()) createDirectory(rootPath, parent)
        files[k(rootPath, relativePath)] = bytes
    }

    override fun readFile(rootPath: String, relativePath: String): ByteArray =
        files[k(rootPath, relativePath)] ?: error("Missing file $relativePath")

    override fun delete(rootPath: String, relativePath: String, isFolder: Boolean) {
        val n = PathUtil.normalize(relativePath)
        val prefix = k(rootPath, n)
        if (isFolder) {
            files.keys.filter { it == prefix || it.startsWith("$prefix/") }.forEach { files.remove(it) }
            dirs.filter { it == prefix || it.startsWith("$prefix/") }.forEach { dirs.remove(it) }
        } else {
            files.remove(prefix)
        }
    }

    override fun move(rootPath: String, fromRelative: String, toRelative: String, isFolder: Boolean) {
        val from = PathUtil.normalize(fromRelative)
        val to = PathUtil.normalize(toRelative)
        if (isFolder) {
            val fromKey = k(rootPath, from)
            val kids = files.filterKeys { it == fromKey || it.startsWith("$fromKey/") }
            for ((oldK, bytes) in kids) {
                val rel = oldK.substringAfter('|')
                val newRel = to + rel.removePrefix(from)
                writeFile(rootPath, newRel, bytes)
                files.remove(oldK)
            }
            val dirKids = dirs.filter { it == fromKey || it.startsWith("$fromKey/") }
            for (old in dirKids) {
                val rel = old.substringAfter('|')
                val newRel = to + rel.removePrefix(from)
                createDirectory(rootPath, newRel)
                dirs.remove(old)
            }
            createDirectory(rootPath, to)
            dirs.remove(fromKey)
        } else {
            val bytes = readFile(rootPath, from)
            writeFile(rootPath, to, bytes)
            files.remove(k(rootPath, from))
        }
    }

    override fun exists(rootPath: String, relativePath: String): Boolean {
        val key = k(rootPath, relativePath)
        return files.containsKey(key) || dirs.contains(key)
    }

    fun seedRoot(rootPath: String) {
        // root itself is implicit
    }
}

class InMemoryRemoteCloud : RemoteCloud {
    data class Node(
        val id: String,
        var parentId: String,
        var name: String,
        val isFolder: Boolean,
        var bytes: ByteArray? = null,
        var updateTime: Instant = Instant.now()
    )

    private val nodes = ConcurrentHashMap<String, Node>()

    fun seedFolder(id: String, parentId: String, name: String) {
        nodes[id] = Node(id, parentId, name, true)
    }

    fun seedFile(id: String, parentId: String, name: String, bytes: ByteArray) {
        nodes[id] = Node(id, parentId, name, false, bytes)
    }

    fun get(id: String): Node? = nodes[id]

    fun childrenOf(parentId: String): List<Node> = nodes.values.filter { it.parentId == parentId }

    override suspend fun listChildrenPaged(parentId: String): List<RemoteEntry> =
        childrenOf(parentId).map {
            RemoteEntry(
                id = it.id,
                parentId = it.parentId,
                name = it.name,
                isFolder = it.isFolder,
                sizeBytes = it.bytes?.size?.toLong(),
                updateTime = it.updateTime
            )
        }

    override suspend fun addFolder(parentId: String, name: String): String {
        val id = UUID.randomUUID().toString()
        nodes[id] = Node(id, parentId, name, true)
        return id
    }

    override suspend fun addFile(parentId: String, name: String, bytes: ByteArray, mimeType: String): String {
        val id = UUID.randomUUID().toString()
        nodes[id] = Node(id, parentId, name, false, bytes)
        return id
    }

    override suspend fun uploadFile(entityId: String, name: String, bytes: ByteArray, mimeType: String) {
        val n = nodes[entityId] ?: error("missing $entityId")
        n.bytes = bytes
        n.name = name
        n.updateTime = Instant.now()
    }

    override suspend fun downloadFile(entityId: String): ByteArray? = nodes[entityId]?.bytes

    override suspend fun fileSize(entityId: String): Long? = nodes[entityId]?.bytes?.size?.toLong()

    override suspend fun rename(entityId: String, newName: String) {
        val n = nodes[entityId] ?: error("missing")
        n.name = newName
        n.updateTime = Instant.now()
    }

    override suspend fun move(entityId: String, oldParentId: String, newParentId: String) {
        val n = nodes[entityId] ?: error("missing")
        n.parentId = newParentId
        n.updateTime = Instant.now()
    }

    override suspend fun delete(entityId: String) {
        val toRemove = mutableListOf(entityId)
        var i = 0
        while (i < toRemove.size) {
            val id = toRemove[i++]
            toRemove.addAll(nodes.values.filter { it.parentId == id }.map { it.id })
        }
        toRemove.forEach { nodes.remove(it) }
    }
}

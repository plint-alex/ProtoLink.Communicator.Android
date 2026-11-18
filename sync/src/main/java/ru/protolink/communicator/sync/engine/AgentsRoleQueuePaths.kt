package ru.protolink.communicator.sync.engine

/**
 * Force/Sync must not thrash AgentTeam open role queues when MyNotesFolder maps over
 * `Protolink.ru/Agents` (ops/agents-queue-cloud-map · NFR-SYNC-1).
 */
object AgentsRoleQueuePaths {
    private val openQueues = setOf("todo", "progress", "hold")

    fun matchesOpenQueueOrAgentsRolesAncestor(relativePath: String): Boolean {
        val parts = PathUtil.normalize(relativePath)
            .split('/')
            .filter { it.isNotEmpty() }
        var i = 0
        while (i < parts.size) {
            if (!parts[i].equals("Agents", ignoreCase = true)) {
                i++
                continue
            }
            if (i + 1 >= parts.size) return true
            if (!parts[i + 1].equals("roles", ignoreCase = true)) {
                i++
                continue
            }
            if (i + 2 >= parts.size) return true
            if (i + 3 >= parts.size) return true
            return parts[i + 3].lowercase() in openQueues
        }
        return false
    }

    /**
     * Skip mkdir-only Force materialize for task shells under open queues.
     */
    fun shouldSkipFolderMaterialize(relativePath: String): Boolean {
        val parts = PathUtil.normalize(relativePath)
            .split('/')
            .filter { it.isNotEmpty() }
        var i = 0
        while (i < parts.size) {
            if (!parts[i].equals("Agents", ignoreCase = true)) {
                i++
                continue
            }
            if (i + 1 >= parts.size || !parts[i + 1].equals("roles", ignoreCase = true)) {
                i++
                continue
            }
            if (i + 3 >= parts.size) return false
            if (parts[i + 3].lowercase() !in openQueues) return false
            return parts.size >= i + 5
        }
        return false
    }

    /**
     * True when [relativePath] is an open-queue path/ancestor, or any [localRelativePaths]
     * under it is (covers Protolink.ru wiping nested Agents roles open queues).
     */
    fun isForcePruneProtected(
        relativePath: String,
        localRelativePaths: Collection<String>? = null
    ): Boolean {
        if (matchesOpenQueueOrAgentsRolesAncestor(relativePath)) return true
        if (localRelativePaths == null) return false
        val norm = PathUtil.normalize(relativePath)
        val prefix = if (norm.isEmpty()) "" else "$norm/"
        return localRelativePaths.any { candidate ->
            val p = PathUtil.normalize(candidate)
            val under =
                norm.isEmpty() || p == norm || p.startsWith(prefix)
            under && matchesOpenQueueOrAgentsRolesAncestor(p)
        }
    }
}

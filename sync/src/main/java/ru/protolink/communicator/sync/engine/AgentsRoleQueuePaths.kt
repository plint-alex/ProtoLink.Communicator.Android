package ru.protolink.communicator.sync.engine

/**
 * Force/Sync must not thrash AgentTeam role queues or mailbox queues when MyNotesFolder maps over
 * `Protolink.ru/Agents` (ops/agents-queue-cloud-map · NFR-SYNC-1 · ADR inbox-outbox-todo-done-only).
 *
 * Live 1.0.77 FORCE_MID_INVENTORY: open-queue shell skip cleared wave16 stall, but Force still
 * BFS-listed every `done/<taskShell>` (+ evidence trees) → waves 17–70 / frontier hundreds past
 * 15m. Force also skips `done` task shells (same shape); local done content stays prune-protected.
 *
 * Incident 2026-11-21: role-only exclude left `Agents/inbox` wipeable — mailbox queues are the
 * same Force class as role queues.
 */
object AgentsRoleQueuePaths {
    /** Open queues AgentTeam mutates live + done (Force throughput — evidence BFS unbounded). */
    private val forceExcludedQueues = setOf("todo", "progress", "hold", "done")

    /** Structural human mailboxes under Agents (ADR inbox-outbox-todo-done-only). */
    private val mailboxRoots = setOf("inbox", "outbox")

    /** Only todo+done under inbox/outbox — no progress/hold. */
    private val mailboxExcludedQueues = setOf("todo", "done")

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

            if (parts[i + 1].equals("roles", ignoreCase = true)) {
                if (i + 2 >= parts.size) return true
                if (i + 3 >= parts.size) return true
                return parts[i + 3].lowercase() in forceExcludedQueues
            }

            if (parts[i + 1].lowercase() in mailboxRoots) {
                if (i + 2 >= parts.size) return true
                return parts[i + 2].lowercase() in mailboxExcludedQueues
            }

            i++
        }
        return false
    }

    /**
     * AutoPush must not upload AgentTeam queue churn under a mapped MyNotesFolder
     * (Win 1.0.118: continuous Uploading… + LastSync missing while roles/inbox rewrite).
     * Same path class as Force prune protection.
     */
    fun shouldSkipLocalPush(relativePath: String): Boolean =
        matchesOpenQueueOrAgentsRolesAncestor(relativePath)

    /**
     * Skip mkdir-only Force materialize for task/ask shells under role or mailbox queues
     * (`…/Agents/roles/<role>/todo|…/<task>`, `…/Agents/inbox|outbox/todo|done/<ask>`).
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
            if (i + 1 >= parts.size) {
                i++
                continue
            }

            if (parts[i + 1].equals("roles", ignoreCase = true)) {
                if (i + 3 >= parts.size) return false
                if (parts[i + 3].lowercase() !in forceExcludedQueues) return false
                return parts.size >= i + 5
            }

            if (parts[i + 1].lowercase() in mailboxRoots) {
                if (i + 2 >= parts.size) return false
                if (parts[i + 2].lowercase() !in mailboxExcludedQueues) return false
                return parts.size >= i + 4
            }

            i++
        }
        return false
    }

    /**
     * True when [relativePath] is an excluded-queue path/ancestor, or any [localRelativePaths]
     * under it is (covers Protolink.ru wiping nested Agents roles/mailbox queues).
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

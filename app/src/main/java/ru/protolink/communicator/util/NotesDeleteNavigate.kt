package ru.protolink.communicator.util

/**
 * Post-delete Notes navigation: previous sibling → parent → notes root.
 * Spec: Agents docs/features/entity-navigation/notes-delete-navigate (AT-ND-1…4).
 * Call [resolveNextRelativePath] before deleting so sibling lists still include the removed folder.
 *
 * Relative paths use `/`, no leading/trailing slash. Empty string = notes root.
 */
object NotesDeleteNavigate {

    fun normalize(relativePath: String?): String =
        relativePath?.replace('\\', '/')?.trim('/').orEmpty()

    /** True when open path is the deleted folder or a descendant of it. */
    fun isOpenPageAffected(openRelativePath: String?, deletedRelativePath: String): Boolean {
        val open = normalize(openRelativePath)
        val deleted = normalize(deletedRelativePath)
        if (open.isEmpty() || deleted.isEmpty()) return false
        return open.equals(deleted, ignoreCase = true) ||
            open.startsWith("$deleted/", ignoreCase = true)
    }

    fun parentRelativePath(relativePath: String): String {
        val n = normalize(relativePath)
        val i = n.lastIndexOf('/')
        return if (i <= 0) "" else n.substring(0, i)
    }

    /**
     * Resolves where the editor should land after [deletedRelativePath] is removed.
     *
     * @param siblingFolderNamesUnderParent direct child folder names of the deleted node's parent,
     *   including the deleted folder (order ignored; sorted case-insensitively like the tree).
     * @return next relative path when open is affected (`""` = notes root); otherwise the
     *   normalized open path unchanged (may be empty when nothing was open).
     */
    fun resolveNextRelativePath(
        deletedRelativePath: String,
        openRelativePath: String?,
        siblingFolderNamesUnderParent: List<String>
    ): String {
        val deleted = normalize(deletedRelativePath)
        val open = normalize(openRelativePath)
        if (deleted.isEmpty()) return open
        if (!isOpenPageAffected(open, deleted)) return open

        val parent = parentRelativePath(deleted)
        val deletedName = deleted.substringAfterLast('/')
        val siblings = siblingFolderNamesUnderParent
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

        val index = siblings.indexOfFirst { it.equals(deletedName, ignoreCase = true) }
        if (index >= 0) {
            if (index > 0) return join(parent, siblings[index - 1])
            // No sibling above — nearest remaining after delete is the next sibling (if any).
            if (index + 1 < siblings.size) return join(parent, siblings[index + 1])
        }

        // Parent page if still under notes root; empty parent = notes root.
        return parent
    }

    private fun join(parent: String, name: String): String =
        if (parent.isEmpty()) name else "$parent/$name"
}

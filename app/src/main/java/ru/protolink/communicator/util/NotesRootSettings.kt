package ru.protolink.communicator.util

import ru.protolink.communicator.data.AppSettings

/**
 * E11: ordered Notes SAF tree roots + last-selected in AppSettings.
 * Migrates legacy single [AppSettings.notesRootUri] to a one-item list.
 * Pure helpers (no Android Context) for JVM unit tests.
 */
object NotesRootSettings {
    fun tryNormalize(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        return LocalUriCoverage.tryNormalize(uri.trim())
    }

    fun urisEqual(a: String?, b: String?): Boolean {
        val na = tryNormalize(a) ?: return false
        val nb = tryNormalize(b) ?: return false
        return na.equals(nb, ignoreCase = true)
    }

    /**
     * Ordered unique tree URIs. Prefers [AppSettings.notesRootUris];
     * if empty, falls back to legacy [AppSettings.notesRootUri].
     */
    fun resolveRoots(settings: AppSettings): List<String> {
        val result = ArrayList<String>()
        val seen = HashSet<String>()

        fun add(raw: String?) {
            val full = tryNormalize(raw) ?: return
            if (!seen.add(full.lowercase())) return
            result.add(full)
        }

        val multi = settings.notesRootUris
        if (!multi.isNullOrEmpty()) {
            for (u in multi) add(u)
        } else {
            add(settings.notesRootUri)
        }
        return result
    }

    /** Last-selected when in [roots]; else legacy; else first. */
    fun resolveSelected(settings: AppSettings, roots: List<String>? = null): String? {
        val list = roots ?: resolveRoots(settings)
        if (list.isEmpty()) return null

        tryNormalize(settings.lastSelectedNotesRootUri)?.let { last ->
            list.firstOrNull { it.equals(last, ignoreCase = true) }?.let { return it }
        }
        tryNormalize(settings.notesRootUri)?.let { legacy ->
            list.firstOrNull { it.equals(legacy, ignoreCase = true) }?.let { return it }
        }
        return list[0]
    }

    /**
     * Writes multi-root shape and mirrors selected into legacy [AppSettings.notesRootUri].
     */
    fun write(settings: AppSettings, roots: Iterable<String?>, selected: String?): AppSettings {
        val list = ArrayList<String>()
        val seen = HashSet<String>()
        for (r in roots) {
            val full = tryNormalize(r) ?: continue
            if (!seen.add(full.lowercase())) continue
            list.add(full)
        }

        var sel: String? = null
        tryNormalize(selected)?.let { selFull ->
            sel = list.firstOrNull { it.equals(selFull, ignoreCase = true) }
        }
        if (sel == null && list.isNotEmpty()) sel = list[0]

        return settings.copy(
            notesRootUris = list,
            lastSelectedNotesRootUri = sel,
            notesRootUri = sel
        )
    }

    /** Neighbor preference after remove: previous index, else next, else null. */
    fun preferNeighborAfterRemove(rootsBefore: List<String>, removedIndex: Int): String? {
        if (rootsBefore.size <= 1) return null
        if (removedIndex < 0 || removedIndex >= rootsBefore.size) return rootsBefore[0]
        if (removedIndex > 0) return rootsBefore[removedIndex - 1]
        return rootsBefore[1]
    }

    /** True when settings still need multi-root fields persisted (legacy-only or mirror drift). */
    fun needsPersistMigration(settings: AppSettings): Boolean {
        val roots = resolveRoots(settings)
        val selected = resolveSelected(settings, roots)
        if (roots.isEmpty()) {
            return !settings.notesRootUris.isNullOrEmpty()
                || !settings.lastSelectedNotesRootUri.isNullOrBlank()
                || !settings.notesRootUri.isNullOrBlank()
        }
        if (settings.notesRootUris.isNullOrEmpty()) return true
        if (settings.notesRootUris.size != roots.size) return true
        if (!urisEqual(settings.lastSelectedNotesRootUri, selected)) return true
        if (!urisEqual(settings.notesRootUri, selected)) return true
        for (i in roots.indices) {
            if (!urisEqual(settings.notesRootUris.getOrNull(i), roots[i])) return true
        }
        return false
    }
}

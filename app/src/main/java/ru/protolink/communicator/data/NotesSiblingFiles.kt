package ru.protolink.communicator.data

/**
 * Pure enumerator for Notes E5/E5a (Android SAF): direct-child **files** under the open
 * note folder, excluding Notes-owned `index.html` / `index.htm` (case-insensitive).
 *
 * Directories are omitted — the left Notes tree already owns folder navigation.
 * Same mental model as Windows `NotesSiblingFiles` (cannot share code across C#/Kotlin repos).
 */
object NotesSiblingFiles {

    data class ChildEntry(
        val name: String,
        val isDirectory: Boolean,
        val documentId: String = "",
        val uri: String = ""
    )

    data class SiblingFile(
        val name: String,
        val documentId: String,
        val uri: String
    )

    fun isNotesOwnedFileName(fileName: String?): Boolean {
        if (fileName.isNullOrEmpty()) return false
        return fileName.equals("index.html", ignoreCase = true)
            || fileName.equals("index.htm", ignoreCase = true)
    }

    /**
     * Filtered list of direct-child files. Empty when there are no qualifying files
     * (panel should hide). Child directories alone never keep the panel open (E5a).
     */
    fun listSiblingFiles(children: List<ChildEntry>): List<SiblingFile> =
        children
            .asSequence()
            .filter { !it.isDirectory && !isNotesOwnedFileName(it.name) }
            .sortedBy { it.name.lowercase() }
            .map { SiblingFile(name = it.name, documentId = it.documentId, uri = it.uri) }
            .toList()
}

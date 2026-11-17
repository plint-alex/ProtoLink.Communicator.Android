package ru.protolink.communicator.data

/**
 * Resolve the next SAF path segment without [androidx.documentfile.provider.DocumentFile.listFiles].
 * [androidx.documentfile.provider.SingleDocumentFile.listFiles] throws
 * [UnsupportedOperationException] — that aborted Force after heal (Adapters.find → exists).
 */
object SafChildResolve {
    /**
     * @param listedNameToId children from [SafTreeLister.listChildren] as (displayName → documentId)
     * @param part path segment to match (case-insensitive)
     * @param parentDocumentId DocumentsContract id of the parent folder
     * @param guessExists probe whether a guessed child document id is openable/exists
     * @return child document id, or null if not found
     */
    fun nextChildDocumentId(
        listedNameToId: Iterable<Pair<String, String>>,
        part: String,
        parentDocumentId: String,
        guessExists: (String) -> Boolean
    ): String? {
        listedNameToId.firstOrNull { it.first.equals(part, ignoreCase = true) }?.let { return it.second }
        val base = parentDocumentId.trimEnd('/')
        val candidates = listOf(
            "$base/$part",
            "$base%2F$part",
            "$parentDocumentId/$part"
        ).distinct()
        return candidates.firstOrNull(guessExists)
    }
}

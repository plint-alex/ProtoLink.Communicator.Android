package ru.protolink.communicator.ui

/**
 * Policy for Android Notes autosave while a note is open in the WebView editor.
 *
 * The live `#editor` is source of truth for body HTML. Pushing our own save payload back into
 * Compose `notesContent` remounts/applies innerHTML and can wipe keystrokes typed after
 * `getHtml` (race with the 300ms JS debounce + 5s save timer).
 */
object NotesAutosavePolicy {

    /**
     * @return true when [cleanedHtml] matches the last successful disk fingerprint and the
     * editor is not dirty — skip scheduling another write.
     */
    fun shouldSkipSave(
        cleanedHtmlFingerprint: String,
        diskFingerprint: String?,
        notesDirty: Boolean
    ): Boolean = diskFingerprint != null &&
        cleanedHtmlFingerprint == diskFingerprint &&
        !notesDirty

    /**
     * User reverted the open note back to the last saved bytes while still marked dirty —
     * clear dirty without rewriting disk.
     */
    fun shouldClearDirtyWithoutWrite(
        cleanedHtmlFingerprint: String,
        diskFingerprint: String?,
        notesDirty: Boolean
    ): Boolean = notesDirty &&
        diskFingerprint != null &&
        cleanedHtmlFingerprint == diskFingerprint

    /** After a successful local save, do not echo HTML into `notesContent`. */
    fun shouldEchoNotesContentAfterLocalSave(): Boolean = false
}

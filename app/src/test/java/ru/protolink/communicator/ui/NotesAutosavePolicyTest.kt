package ru.protolink.communicator.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotesAutosavePolicyTest {

    @Test
    fun skipSave_whenFingerprintMatchesAndNotDirty() {
        assertThat(
            NotesAutosavePolicy.shouldSkipSave(
                cleanedHtmlFingerprint = "abc",
                diskFingerprint = "abc",
                notesDirty = false
            )
        ).isTrue()
    }

    @Test
    fun doNotSkip_whenDirtyEvenIfFingerprintMatches() {
        assertThat(
            NotesAutosavePolicy.shouldSkipSave(
                cleanedHtmlFingerprint = "abc",
                diskFingerprint = "abc",
                notesDirty = true
            )
        ).isFalse()
    }

    @Test
    fun doNotSkip_whenContentChanged() {
        assertThat(
            NotesAutosavePolicy.shouldSkipSave(
                cleanedHtmlFingerprint = "new",
                diskFingerprint = "old",
                notesDirty = false
            )
        ).isFalse()
    }

    @Test
    fun clearDirtyWithoutWrite_whenRevertedToDisk() {
        assertThat(
            NotesAutosavePolicy.shouldClearDirtyWithoutWrite(
                cleanedHtmlFingerprint = "abc",
                diskFingerprint = "abc",
                notesDirty = true
            )
        ).isTrue()
        assertThat(
            NotesAutosavePolicy.shouldClearDirtyWithoutWrite(
                cleanedHtmlFingerprint = "abc",
                diskFingerprint = "abc",
                notesDirty = false
            )
        ).isFalse()
    }

    @Test
    fun neverEchoNotesContentAfterLocalSave() {
        assertThat(NotesAutosavePolicy.shouldEchoNotesContentAfterLocalSave()).isFalse()
    }
}

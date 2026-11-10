package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** UX-N-A1…A3 — format chrome only on selection (or pinned dialog). */
class NotesFormatBarVisibilityTest {
    @Test
    fun idle_hides() {
        assertThat(NotesFormatBarVisibility.shouldShow(hasSelection = false, formatChromePinned = false))
            .isFalse()
    }

    @Test
    fun selection_shows() {
        assertThat(NotesFormatBarVisibility.shouldShow(hasSelection = true, formatChromePinned = false))
            .isTrue()
    }

    @Test
    fun pinnedDialog_keepsChrome() {
        assertThat(NotesFormatBarVisibility.shouldShow(hasSelection = false, formatChromePinned = true))
            .isTrue()
    }
}

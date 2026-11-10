package ru.protolink.communicator.util

/**
 * Pattern 2 — show Notes format chrome only while text is selected
 * (or while a format dialog keeps the chrome pinned).
 */
object NotesFormatBarVisibility {
    fun shouldShow(hasSelection: Boolean, formatChromePinned: Boolean): Boolean =
        hasSelection || formatChromePinned
}

package ru.protolink.communicator.syncadapt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Live 1.0.66: forceRunning true (Download all disabled) while status showed
 * "No local changes" / "Full sync queued…" — coalesce must not clobber Force UI.
 * Live 1.0.73: "Cloud loaded" from listing reload must not clobber either.
 */
class ForceStatusGuardTest {

    @Test
    fun ownsStatus_whenForceRunning() {
        assertThat(ForceStatusGuard.ownsStatus(forceRunning = true, forcePreferred = false)).isTrue()
    }

    @Test
    fun ownsStatus_whenForcePreferred() {
        assertThat(ForceStatusGuard.ownsStatus(forceRunning = false, forcePreferred = true)).isTrue()
    }

    @Test
    fun ownsStatus_whenIdle() {
        assertThat(ForceStatusGuard.ownsStatus(forceRunning = false, forcePreferred = false)).isFalse()
    }

    @Test
    fun statusOrNull_preservesWhenForceOwns() {
        assertThat(
            ForceStatusGuard.statusOrNull(
                forceRunning = true,
                forcePreferred = false,
                proposed = "No local changes"
            )
        ).isNull()
        assertThat(
            ForceStatusGuard.statusOrNull(
                forceRunning = false,
                forcePreferred = true,
                proposed = "Full sync queued…"
            )
        ).isNull()
    }

    @Test
    fun statusOrNull_allowsWhenIdle() {
        assertThat(
            ForceStatusGuard.statusOrNull(
                forceRunning = false,
                forcePreferred = false,
                proposed = "No local changes"
            )
        ).isEqualTo("No local changes")
    }

    @Test
    fun statusOrNull_preservesForceOverCloudLoaded() {
        assertThat(
            ForceStatusGuard.statusOrNull(
                forceRunning = true,
                forcePreferred = false,
                proposed = "Cloud loaded"
            )
        ).isNull()
        assertThat(
            ForceStatusGuard.statusOrNull(
                forceRunning = false,
                forcePreferred = true,
                proposed = "Cloud loaded"
            )
        ).isNull()
    }
}

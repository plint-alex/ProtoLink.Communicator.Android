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

    @Test
    fun statusOrNull_preservesForceOverContacts() {
        // Live 1.0.74: loadContacts enrich wrote Contacts:1 while Force download… cleared.
        assertThat(
            ForceStatusGuard.statusOrNull(
                forceRunning = true,
                forcePreferred = false,
                proposed = "Contacts: 1"
            )
        ).isNull()
        assertThat(
            ForceStatusGuard.statusOrNull(
                forceRunning = false,
                forcePreferred = true,
                proposed = "Contacts: 1"
            )
        ).isNull()
    }

    @Test
    fun statusOrNull_preservesSyncingOverCloudLoaded() {
        // Live 1.0.99 three-way: listing Cloud loaded while syncing made Sync look idle.
        assertThat(ForceStatusGuard.ownsStatus(false, false, syncing = true)).isTrue()
        assertThat(
            ForceStatusGuard.statusOrNull(
                forceRunning = false,
                forcePreferred = false,
                proposed = "Cloud loaded",
                syncing = true
            )
        ).isNull()
        assertThat(
            ForceStatusGuard.statusOrNull(
                forceRunning = false,
                forcePreferred = false,
                proposed = "Cloud loaded",
                syncing = false
            )
        ).isEqualTo("Cloud loaded")
    }

    @Test
    fun statusOrNull_preservesSyncingOverContacts() {
        // Live 1.0.101: Contacts:2 overwrote Syncing… when syncing= omitted.
        assertThat(
            ForceStatusGuard.statusOrNull(
                forceRunning = false,
                forcePreferred = false,
                proposed = "Contacts: 2",
                syncing = true
            )
        ).isNull()
    }

    @Test
    fun statusOrNull_preservesUploadingWhenSyncing_blocksFullSyncQueued() {
        // Live 1.0.116: AutoPush syncing=true made setStatusUnlessForce("Full sync queued…")
        // a no-op — chrome stayed Uploading while deferredFull orphaned download/delete.
        // requestFullSync coalesce must write "Full sync queued…" without ForceStatusGuard
        // when only syncing (not Force) owns the banner.
        assertThat(
            ForceStatusGuard.statusOrNull(
                forceRunning = false,
                forcePreferred = false,
                proposed = "Full sync queued…",
                syncing = true
            )
        ).isNull()
        assertThat(
            ForceStatusGuard.statusOrNull(
                forceRunning = false,
                forcePreferred = false,
                proposed = "Full sync queued…",
                syncing = false
            )
        ).isEqualTo("Full sync queued…")
    }
}

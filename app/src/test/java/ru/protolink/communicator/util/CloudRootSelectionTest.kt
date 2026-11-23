package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.protolink.communicator.data.CloudCodes

/** Offline Win-parity CloudRootSelection + richness pick for ANDROID_SOFTREADY_EMPTY_LISTING. */
class CloudRootSelectionTest {
    private val healthy = "8aec679c-a389-46b9-8105-8a5b238ee979"
    private val drive = "3e5a2d6e-27ca-4c8b-89e6-e3cb253c05d6"
    private val emptyTwin = "ba9266e9-ac45-46f3-9b6d-cdde2f4e2d35"

    @Test
    fun pickCanonical_singleRoot_returnsIt() {
        val roots = listOf(CloudRootSelection.Candidate(healthy, "2024-01-01T00:00:00"))
        assertThat(CloudRootSelection.pickCanonical(roots)).isEqualTo(healthy)
    }

    @Test
    fun pickCanonical_prefersNonMappedWhenDuplicate() {
        val roots = listOf(
            CloudRootSelection.Candidate(drive, "2023-01-01T00:00:00"),
            CloudRootSelection.Candidate(healthy, "2024-06-01T00:00:00")
        )
        assertThat(CloudRootSelection.pickCanonical(roots, setOf(drive))).isEqualTo(healthy)
    }

    @Test
    fun pickCanonical_whenMappingsEmpty_prefersLastOpenOverEarliestDrive() {
        val roots = listOf(
            CloudRootSelection.Candidate(drive, "2023-01-01T00:00:00"),
            CloudRootSelection.Candidate(healthy, "2024-06-01T00:00:00")
        )
        assertThat(CloudRootSelection.pickCanonical(roots, emptySet())).isEqualTo(drive)
        assertThat(CloudRootSelection.pickCanonical(roots, emptySet(), preferredId = healthy))
            .isEqualTo(healthy)
    }

    @Test
    fun pickCanonicalPreferringRichness_emptyFirstPageTwinLosesToHealthyKids() {
        // Live: take=200 crowns emptyTwin; healthy AT root with kids is on page 2.
        val roots = listOf(
            CloudRootSelection.Candidate(emptyTwin, "2026-11-18T13:58:53"),
            CloudRootSelection.Candidate(healthy, "2024-06-01T00:00:00"),
            CloudRootSelection.Candidate(drive, "2023-01-01T00:00:00")
        )
        val counts = mapOf(
            emptyTwin.lowercase() to 0,
            healthy.lowercase() to 4,
            drive.lowercase() to 0
        )
        assertThat(
            CloudRootSelection.pickCanonicalPreferringRichness(
                roots,
                mappedCloudFolderIds = emptySet(),
                preferredId = null,
                folderChildCountByRemoteId = counts
            )
        ).isEqualTo(healthy)
    }

    @Test
    fun pickCanonicalPreferringRichness_keepsPreferredEvenIfLessRich() {
        val roots = listOf(
            CloudRootSelection.Candidate(emptyTwin, "2026-11-18T13:58:53"),
            CloudRootSelection.Candidate(healthy, "2024-06-01T00:00:00")
        )
        val counts = mapOf(emptyTwin.lowercase() to 0, healthy.lowercase() to 4)
        assertThat(
            CloudRootSelection.pickCanonicalPreferringRichness(
                roots,
                preferredId = emptyTwin,
                folderChildCountByRemoteId = counts
            )
        ).isEqualTo(emptyTwin)
    }

    @Test
    fun resolvePreferredCloudRootId_prefersLastOpenOverStaleMyCloud() {
        val known = setOf(healthy, drive)
        assertThat(
            CloudRootSelection.resolvePreferredCloudRootId(known, healthy, drive)
        ).isEqualTo(healthy)
        assertThat(
            CloudRootSelection.resolvePreferredCloudRootId(known, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", drive)
        ).isEqualTo(drive)
    }

    @Test
    fun pickWizardCreateParentRoot_prefersNewestNonMapped() {
        val roots = listOf(
            CloudRootSelection.Candidate(drive, "2023-01-01T00:00:00"),
            CloudRootSelection.Candidate(healthy, "2024-06-01T00:00:00")
        )
        assertThat(CloudRootSelection.pickWizardCreateParentRoot(roots)).isEqualTo(healthy)
    }

    @Test
    fun shouldRefuseMapping_cloudRootCodeOrKnownId() {
        assertThat(CloudRootSelection.shouldRefuseMapping(CloudCodes.CLOUD_ROOT, "x")).isTrue()
        assertThat(CloudRootSelection.shouldRefuseMapping(CloudCodes.CLOUD_FOLDER, "x")).isFalse()
        assertThat(
            CloudRootSelection.shouldRefuseMapping(null, drive, setOf(drive))
        ).isTrue()
    }

    @Test
    fun shouldSkipLastOpenCloudRoot_onlyNonCanonicalWhenDuplicates() {
        assertThat(
            CloudRootSelection.shouldSkipLastOpenCloudRoot(
                CloudCodes.CLOUD_ROOT, drive, healthy, cloudRootCountUnderUser = 2
            )
        ).isTrue()
        assertThat(
            CloudRootSelection.shouldSkipLastOpenCloudRoot(
                CloudCodes.CLOUD_ROOT, healthy, healthy, cloudRootCountUnderUser = 2
            )
        ).isFalse()
        assertThat(
            CloudRootSelection.shouldSkipLastOpenCloudRoot(
                CloudCodes.CLOUD_ROOT, drive, healthy, cloudRootCountUnderUser = 1
            )
        ).isFalse()
    }
}

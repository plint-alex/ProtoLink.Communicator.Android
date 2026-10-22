package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.protolink.communicator.data.api.ShareGrantDto

/** AT-S50 / AC-NS-4 — external-share honesty (parity with Windows NotesTreeShareTests). */
class NotesTreeShareTest {
    private val me = "11111111-1111-1111-1111-111111111111"
    private val other = "22222222-2222-2222-2222-222222222222"

    @Test
    fun onlySelf_false() {
        val grants = listOf(
            ShareGrantDto(principalKind = "user", permissionForId = me, canWrite = true)
        )
        assertThat(NotesTreeShare.hasExternalShare(grants, liveGuestLinkCount = 0, me)).isFalse()
    }

    @Test
    fun otherUser_true() {
        val grants = listOf(
            ShareGrantDto(principalKind = "user", permissionForId = me, canWrite = true),
            ShareGrantDto(principalKind = "user", permissionForId = other, canWrite = false)
        )
        assertThat(NotesTreeShare.hasExternalShare(grants, liveGuestLinkCount = 0, me)).isTrue()
    }

    @Test
    fun liveGuestLink_true() {
        assertThat(
            NotesTreeShare.hasExternalShare(emptyList(), liveGuestLinkCount = 1, me)
        ).isTrue()
    }

    @Test
    fun publicGrant_true() {
        val grants = listOf(
            ShareGrantDto(principalKind = "user", permissionForId = me, canWrite = true),
            ShareGrantDto(principalKind = "public", permissionForId = null, canWrite = false)
        )
        assertThat(NotesTreeShare.hasExternalShare(grants, liveGuestLinkCount = 0, me)).isTrue()
    }

    @Test
    fun guestLinkPrincipal_ignored_onlyLiveCounts() {
        val grants = listOf(
            ShareGrantDto(principalKind = "user", permissionForId = me, canWrite = true),
            ShareGrantDto(principalKind = "guest_link", permissionForId = other)
        )
        assertThat(NotesTreeShare.hasExternalShare(grants, liveGuestLinkCount = 0, me)).isFalse()
        assertThat(NotesTreeShare.hasExternalShare(grants, liveGuestLinkCount = 1, me)).isTrue()
    }

    @Test
    fun unknownMe_twoUsers_true() {
        val grants = listOf(
            ShareGrantDto(principalKind = "user", permissionForId = me),
            ShareGrantDto(principalKind = "user", permissionForId = other)
        )
        assertThat(NotesTreeShare.hasExternalShare(grants, liveGuestLinkCount = 0, null)).isTrue()
        assertThat(
            NotesTreeShare.hasExternalShare(
                listOf(ShareGrantDto(principalKind = "user", permissionForId = me)),
                liveGuestLinkCount = 0,
                null
            )
        ).isFalse()
    }
}

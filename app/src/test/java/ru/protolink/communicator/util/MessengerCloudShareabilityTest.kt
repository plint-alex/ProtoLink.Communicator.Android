package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.protolink.communicator.data.api.EntityPermissionDto

class MessengerCloudShareabilityTest {
    private val me = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val peer = "11111111-2222-3333-4444-555555555555"

    @Test
    fun canSelect_matrix() {
        assertThat(MessengerCloudShareability.canSelect(true, false)).isTrue()
        assertThat(MessengerCloudShareability.canSelect(false, true)).isTrue()
        assertThat(MessengerCloudShareability.canSelect(true, true)).isTrue()
        assertThat(MessengerCloudShareability.canSelect(false, false)).isFalse()
    }

    @Test
    fun disabledSubtitles_matchWindowsWeb() {
        assertThat(MessengerCloudShareability.DisabledShareSubtitleEn)
            .isEqualTo("You can't share this item")
        assertThat(MessengerCloudShareability.disabledShareSubtitle("en"))
            .isEqualTo(MessengerCloudShareability.DisabledShareSubtitleEn)
        assertThat(MessengerCloudShareability.disabledShareSubtitle("ru-RU"))
            .isEqualTo(MessengerCloudShareability.DisabledShareSubtitleRu)
        assertThat(MessengerCloudShareability.PickerPageSize).isEqualTo(200)
    }

    @Test
    fun canSelectFromPermissionRows_senderWrite() {
        assertThat(
            MessengerCloudShareability.canSelectFromPermissionRows(
                listOf(EntityPermissionDto(permissionForId = me, canWrite = true)),
                me,
                peer
            )
        ).isTrue()
    }

    @Test
    fun canSelectFromPermissionRows_peerAlreadyHasRead() {
        assertThat(
            MessengerCloudShareability.canSelectFromPermissionRows(
                listOf(
                    EntityPermissionDto(permissionForId = me, canWrite = false),
                    EntityPermissionDto(permissionForId = peer, canWrite = false)
                ),
                me,
                peer
            )
        ).isTrue()
    }

    @Test
    fun canSelectFromPermissionRows_failClosed() {
        assertThat(
            MessengerCloudShareability.canSelectFromPermissionRows(
                listOf(EntityPermissionDto(permissionForId = me, canWrite = false)),
                me,
                peer
            )
        ).isFalse()
        assertThat(
            MessengerCloudShareability.canSelectFromPermissionRows(null, me, peer)
        ).isFalse()
    }

    @Test
    fun canSelectFromPermissionRows_selfChat() {
        assertThat(
            MessengerCloudShareability.canSelectFromPermissionRows(emptyList(), me, me)
        ).isTrue()
    }
}

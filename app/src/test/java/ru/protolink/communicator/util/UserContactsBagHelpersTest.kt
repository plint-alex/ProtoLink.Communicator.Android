package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.protolink.communicator.data.api.EntityDto

class UserContactsBagHelpersTest {
    @Test
    fun shouldCreateBag_whenEmpty() {
        assertThat(UserContactsBagHelpers.shouldCreateBag(null)).isTrue()
        assertThat(UserContactsBagHelpers.shouldCreateBag(emptyList())).isTrue()
    }

    @Test
    fun shouldCreateBag_falseWhenAnyId() {
        val bags = listOf(EntityDto(id = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", code = "UserContacts"))
        assertThat(UserContactsBagHelpers.shouldCreateBag(bags)).isFalse()
    }

    @Test
    fun pickBagId_oldestWhenNoCounts() {
        val a = UserContactsBagHelpers.BagSnapshot(
            id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            creationTime = "2024-01-02T00:00:00Z"
        )
        val b = UserContactsBagHelpers.BagSnapshot(
            id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            creationTime = "2024-01-01T00:00:00Z"
        )
        assertThat(UserContactsBagHelpers.pickBagId(listOf(a, b)))
            .isEqualTo("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    }

    @Test
    fun pickBagId_prefersRicherWhenCountsKnown() {
        val thin = UserContactsBagHelpers.BagSnapshot(
            id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            creationTime = "2024-01-01T00:00:00Z",
            personRowCount = 1
        )
        val rich = UserContactsBagHelpers.BagSnapshot(
            id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            creationTime = "2024-01-02T00:00:00Z",
            personRowCount = 5
        )
        assertThat(UserContactsBagHelpers.pickBagId(listOf(thin, rich)))
            .isEqualTo("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
    }
}

package ru.protolink.communicator.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatFolderNamingTest {
    @Test
    fun chooseFolderName_preferredWhenFree() {
        val name = ChatFolderNaming.chooseFolderName(
            "Ivan Petrov",
            "a1b2c3d4-0000-0000-0000-000000000001",
            emptyList()
        )
        assertThat(name).isEqualTo("Ivan Petrov")
    }

    @Test
    fun chooseFolderName_suffixOnCollision() {
        val id = "a1b2c3d4-0000-0000-0000-000000000001"
        val name = ChatFolderNaming.chooseFolderName(
            "Ivan Petrov",
            id,
            listOf("Ivan Petrov")
        )
        assertThat(name).isEqualTo("Ivan Petrov (a1b2c3d4)")
    }

    @Test
    fun distinctNamesForCollision_twoDistinct() {
        val (a, b) = ChatFolderNaming.distinctNamesForCollision(
            "Alex",
            "11111111-0000-0000-0000-000000000001",
            "22222222-0000-0000-0000-000000000002"
        )
        assertThat(a).isEqualTo("Alex")
        assertThat(b).isEqualTo("Alex (22222222)")
        assertThat(a).isNotEqualTo(b)
    }
}

package ru.protolink.communicator.sync.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.protolink.communicator.sync.model.RemoteEntry
import java.time.Instant

class SameNamedCloudSiblingTest {

    private val parent = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

    @Test
    fun preferExisting_sameNameFile_reusesNewest() {
        val older = RemoteEntry(
            id = "11111111-1111-4111-8111-111111111111",
            parentId = parent,
            name = "index.html",
            isFolder = false,
            updateTime = Instant.parse("2026-01-01T00:00:00Z")
        )
        val newer = RemoteEntry(
            id = "22222222-2222-4222-8222-222222222222",
            parentId = parent,
            name = "index.html",
            isFolder = false,
            updateTime = Instant.parse("2026-11-17T00:00:00Z")
        )
        val pick = SameNamedCloudSibling.preferExisting(
            parent, "index.html", isFolder = false, listOf(older, newer)
        )
        assertThat(pick?.id).isEqualTo(newer.id)
    }

    @Test
    fun preferExisting_sameNameFolder_reuses() {
        val folder = RemoteEntry(
            id = "44444444-4444-4444-8444-444444444444",
            parentId = parent,
            name = "Agents",
            isFolder = true,
            updateTime = Instant.now()
        )
        val fileTwin = RemoteEntry(
            id = "55555555-5555-4555-8555-555555555555",
            parentId = parent,
            name = "Agents",
            isFolder = false,
            updateTime = Instant.now()
        )
        val pick = SameNamedCloudSibling.preferExisting(
            parent, "agents", isFolder = true, listOf(folder, fileTwin)
        )
        assertThat(pick?.id).isEqualTo(folder.id)
        assertThat(pick?.isFolder).isTrue()
    }

    @Test
    fun preferExisting_skipsSyntheticFileName() {
        val id = "66666666-6666-4666-8666-666666666666"
        val synthetic = RemoteEntry(
            id = id,
            parentId = parent,
            name = "file-${id.replace("-", "")}",
            isFolder = false,
            updateTime = Instant.now()
        )
        assertThat(
            SameNamedCloudSibling.preferExisting(
                parent, synthetic.name, isFolder = false, listOf(synthetic)
            )
        ).isNull()
    }
}

package ru.protolink.communicator.sync.fakes

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.protolink.communicator.sync.model.SyncItemMeta

/** E10 / AT-CFD: sync-meta purge so Soft Ready cannot resurrect deleted Cloud rows. */
class MetadataStoreRemoveByRemoteIdsTest {
    @Test
    fun removeByRemoteIds_dashInsensitive_dropsSoftReadyChildren() {
        val store = InMemoryMetadataStore()
        val parent = "11111111-1111-4111-8111-111111111111"
        val keep = "22222222-2222-4222-8222-222222222222"
        val gone = "33333333-3333-4333-8333-333333333333"
        store.upsert(
            SyncItemMeta(
                mappingId = "map1",
                remoteId = keep,
                parentRemoteId = parent,
                relativePath = "Keep",
                isFolder = true,
                sizeBytes = 0
            )
        )
        store.upsert(
            SyncItemMeta(
                mappingId = "map1",
                remoteId = gone,
                parentRemoteId = parent,
                relativePath = "Gone",
                isFolder = true,
                sizeBytes = 0
            )
        )
        store.upsert(
            SyncItemMeta(
                mappingId = "map1",
                remoteId = "44444444-4444-4444-8444-444444444444",
                parentRemoteId = gone,
                relativePath = "Gone/nested.txt",
                isFolder = false,
                sizeBytes = 1
            )
        )

        val removed = store.removeByRemoteIds(
            listOf(
                gone.uppercase(),
                "44444444444444448444444444444444"
            )
        )
        assertThat(removed).isEqualTo(2)
        val left = store.getAll("map1")
        assertThat(left).hasSize(1)
        assertThat(left[0].remoteId).isEqualTo(keep)
        assertThat(store.findByRemoteId(gone)).isNull()
    }
}

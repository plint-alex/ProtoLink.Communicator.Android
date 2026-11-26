package ru.protolink.communicator.sync.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.protolink.communicator.sync.ports.RemoteCreateItem

/** Offline: AddEntities batch helpers (Sync ApplyLocal parent+children). */
class CloudBatchCreateTest {
    @Test
    fun shouldUseBatch_twoOrMore() {
        assertFalse(CloudBatchCreate.shouldUseBatch(0))
        assertFalse(CloudBatchCreate.shouldUseBatch(1))
        assertTrue(CloudBatchCreate.shouldUseBatch(2))
    }

    @Test
    fun parentsBeforeChildren_validAndForwardRef() {
        val folder = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val file = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        val root = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        assertTrue(
            CloudBatchCreate.parentsBeforeChildren(
                listOf(
                    RemoteCreateItem(folder, root, "a", isFolder = true),
                    RemoteCreateItem(file, folder, "b", isFolder = false)
                )
            )
        )
        assertFalse(
            CloudBatchCreate.parentsBeforeChildren(
                listOf(
                    RemoteCreateItem(file, folder, "b", isFolder = false),
                    RemoteCreateItem(folder, root, "a", isFolder = true)
                )
            )
        )
    }
}

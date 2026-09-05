package ru.protolink.communicator.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.protolink.communicator.sync.model.SyncItemMeta
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class RoomMetadataStoreContentHashTest {
    private lateinit var db: AppDatabase
    private lateinit var store: RoomMetadataStore

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomMetadataStore(db.syncMetaDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsert_roundTripsContentHash() {
        val hash = "abcdef0123456789"
        store.upsert(
            SyncItemMeta(
                mappingId = "m1",
                remoteId = "r1",
                parentRemoteId = "root",
                relativePath = "films/index.html",
                isFolder = false,
                sizeBytes = 4,
                remoteUpdateTime = Instant.EPOCH,
                contentHash = hash
            )
        )
        val loaded = store.getAll("m1").single()
        assertThat(loaded.contentHash).isEqualTo(hash)
        assertThat(loaded.relativePath).isEqualTo("films/index.html")
    }
}

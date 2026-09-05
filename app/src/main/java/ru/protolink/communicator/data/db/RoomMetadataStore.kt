package ru.protolink.communicator.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ru.protolink.communicator.sync.model.SyncItemMeta
import ru.protolink.communicator.sync.ports.MetadataStore
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val mappingId: String,
    val remoteId: String,
    val parentRemoteId: String,
    val relativePath: String,
    val isFolder: Boolean,
    val sizeBytes: Long,
    val remoteUpdateTimeEpochMs: Long?,
    val contentHash: String = ""
)

@Entity(tableName = "sync_root")
data class SyncRootEntity(
    @PrimaryKey val mappingId: String,
    val lastSyncEpochMs: Long
)

@Dao
interface SyncMetaDao {
    @Query("SELECT * FROM sync_meta WHERE mappingId = :mappingId")
    suspend fun getAll(mappingId: String): List<SyncMetaEntity>

    @Query("SELECT * FROM sync_meta")
    suspend fun getAll(): List<SyncMetaEntity>

    @Query("SELECT * FROM sync_meta WHERE remoteId = :remoteId LIMIT 1")
    suspend fun findByRemoteId(remoteId: String): SyncMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncMetaEntity)

    @Query("DELETE FROM sync_meta WHERE mappingId = :mappingId AND relativePath = :relativePath")
    suspend fun delete(mappingId: String, relativePath: String)

    @Query("DELETE FROM sync_meta WHERE remoteId = :remoteId")
    suspend fun deleteByRemoteId(remoteId: String)

    @Query("DELETE FROM sync_meta WHERE mappingId = :mappingId")
    suspend fun clearMapping(mappingId: String)

    @Query("SELECT * FROM sync_root WHERE mappingId = :mappingId LIMIT 1")
    suspend fun getRoot(mappingId: String): SyncRootEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoot(entity: SyncRootEntity)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sync_meta ADD COLUMN contentHash TEXT NOT NULL DEFAULT ''")
    }
}

@Database(entities = [SyncMetaEntity::class, SyncRootEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncMetaDao(): SyncMetaDao
}

@Singleton
class RoomMetadataStore @Inject constructor(
    private val dao: SyncMetaDao
) : MetadataStore {
    private fun key(mappingId: String, path: String) = "$mappingId|$path"

    override fun getAll(mappingId: String): List<SyncItemMeta> =
        kotlinx.coroutines.runBlocking { dao.getAll(mappingId).map { it.toModel() } }

    override fun getAllRemoteIds(): Set<String> =
        kotlinx.coroutines.runBlocking { dao.getAll().map { it.remoteId }.toSet() }

    override fun findByRemoteId(remoteId: String): SyncItemMeta? =
        kotlinx.coroutines.runBlocking { dao.findByRemoteId(remoteId)?.toModel() }

    override fun upsert(item: SyncItemMeta) {
        kotlinx.coroutines.runBlocking {
            dao.upsert(
                SyncMetaEntity(
                    key = key(item.mappingId, item.relativePath),
                    mappingId = item.mappingId,
                    remoteId = item.remoteId,
                    parentRemoteId = item.parentRemoteId,
                    relativePath = item.relativePath,
                    isFolder = item.isFolder,
                    sizeBytes = item.sizeBytes,
                    remoteUpdateTimeEpochMs = item.remoteUpdateTime?.toEpochMilli(),
                    contentHash = item.contentHash
                )
            )
        }
    }

    override fun delete(mappingId: String, relativePath: String) {
        kotlinx.coroutines.runBlocking { dao.delete(mappingId, relativePath) }
    }

    override fun deleteByRemoteId(remoteId: String) {
        kotlinx.coroutines.runBlocking { dao.deleteByRemoteId(remoteId) }
    }

    override fun getLastSyncUtc(mappingId: String): Instant? =
        kotlinx.coroutines.runBlocking {
            dao.getRoot(mappingId)?.lastSyncEpochMs?.let { Instant.ofEpochMilli(it) }
        }

    override fun setLastSyncUtc(mappingId: String, time: Instant) {
        kotlinx.coroutines.runBlocking {
            dao.upsertRoot(SyncRootEntity(mappingId, time.toEpochMilli()))
        }
    }

    override fun clearMapping(mappingId: String) {
        kotlinx.coroutines.runBlocking { dao.clearMapping(mappingId) }
    }

    private fun SyncMetaEntity.toModel() = SyncItemMeta(
        mappingId = mappingId,
        remoteId = remoteId,
        parentRemoteId = parentRemoteId,
        relativePath = relativePath,
        isFolder = isFolder,
        sizeBytes = sizeBytes,
        remoteUpdateTime = remoteUpdateTimeEpochMs?.let { Instant.ofEpochMilli(it) },
        contentHash = contentHash
    )
}

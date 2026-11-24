package com.hearit.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter
    fun fromSource(value: AudioSourceType): String = value.name

    @TypeConverter
    fun toSource(value: String): AudioSourceType =
        runCatching { AudioSourceType.valueOf(value) }.getOrDefault(AudioSourceType.AMBIENT_MIC)
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY lastHeardAt DESC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY lastHeardAt DESC")
    suspend fun getAllOnce(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE LOWER(title) = LOWER(:title) AND LOWER(artist) = LOWER(:artist) LIMIT 1")
    suspend fun findByTitleAndArtist(title: String, artist: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: TrackEntity): Long

    @Update
    suspend fun update(track: TrackEntity)

    @Query("UPDATE tracks SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert
    suspend fun enqueuePending(pending: PendingSignatureEntity): Long

    @Query("SELECT * FROM pending_signatures ORDER BY capturedAt ASC")
    suspend fun getPending(): List<PendingSignatureEntity>

    @Query("SELECT COUNT(*) FROM pending_signatures")
    fun observePendingCount(): Flow<Int>

    @Query("DELETE FROM pending_signatures WHERE id = :id")
    suspend fun deletePending(id: Long)
}

@Database(
    entities = [TrackEntity::class, PendingSignatureEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class HearItDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        @Volatile
        private var INSTANCE: HearItDatabase? = null

        fun get(context: Context): HearItDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HearItDatabase::class.java,
                    "hearit_library.db"
                ).build().also { INSTANCE = it }
            }
    }
}

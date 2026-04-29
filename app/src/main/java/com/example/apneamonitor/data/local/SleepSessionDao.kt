package com.example.apneamonitor.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SleepSessionEntity)

    @Query("SELECT * FROM sleep_sessions ORDER BY startTimeStamp DESC LIMIT 1")
    fun getLatestSessionFlow(): Flow<SleepSessionEntity?>

    @Query("SELECT * FROM sleep_sessions WHERE isActive = 1 LIMIT 1")
    fun getActiveSessionFlow(): Flow<SleepSessionEntity?>

    @Query("SELECT * FROM sleep_sessions WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveSession(): SleepSessionEntity?

    @Query("UPDATE sleep_sessions SET isActive = 0 WHERE isActive = 1")
    suspend fun deactivateAllSessions()

    // Weekly trend tuple avoiding pulling massive string blobs
    @Query("SELECT dateString, totalApneaEvents FROM sleep_sessions ORDER BY startTimeStamp DESC LIMIT 7")
    fun getWeeklyTrendFlow(): Flow<List<WeeklyTrendTuple>>
}

data class WeeklyTrendTuple(
    val dateString: String,
    val totalApneaEvents: Int
)

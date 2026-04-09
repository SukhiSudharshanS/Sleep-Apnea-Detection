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

    // Flow automatically updates the UI when DB changes
    @Query("SELECT * FROM sleep_sessions ORDER BY startTimeStamp DESC LIMIT 1")
    fun getLatestSessionFlow(): Flow<SleepSessionEntity?>

    // Weekly trend tuple avoiding pulling massive string blobs
    @Query("SELECT dateString, totalApneaEvents FROM sleep_sessions ORDER BY startTimeStamp DESC LIMIT 7")
    fun getWeeklyTrendFlow(): Flow<List<WeeklyTrendTuple>>
}

data class WeeklyTrendTuple(
    val dateString: String,
    val totalApneaEvents: Int
)

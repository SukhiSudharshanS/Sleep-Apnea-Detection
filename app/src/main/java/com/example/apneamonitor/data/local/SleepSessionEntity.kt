package com.example.apneamonitor.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sleep_sessions")
data class SleepSessionEntity(
    @PrimaryKey val dateString: String, // e.g., "2026-03-24"
    val startTimeStamp: Long,
    val endTimeStamp: Long,
    val totalApneaEvents: Int,
    val totalRestlessEvents: Int,
    val avgSpO2: Int,
    val lowestSpO2: Int,
    val avgBpm: Int,
    // JSON strings via TypeConverters to compress data and maintain fast UX querying
    val spO2Array: List<Int>, 
    val bpmArray: List<Int>,
    val movementArray: List<Int>,
    val audioArray: List<Int>,
    val apneaAlertTimestamps: List<Long>,
    val isActive: Boolean = false // Track if this is a live recording session
)

package com.example.apneamonitor.data.repository

import com.example.apneamonitor.data.local.SleepSessionDao
import com.example.apneamonitor.data.local.SleepSessionEntity
import com.example.apneamonitor.data.local.WeeklyTrendTuple
import com.example.apneamonitor.ml.ApneaFusionModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SleepDataRepository(private val sleepSessionDao: SleepSessionDao) {
    // Exposed Flows to ViewModel
    val latestSessionFlow: Flow<SleepSessionEntity?> = sleepSessionDao.getLatestSessionFlow()
    val activeSessionFlow: Flow<SleepSessionEntity?> = sleepSessionDao.getActiveSessionFlow()
    val weeklyTrendFlow: Flow<List<WeeklyTrendTuple>> = sleepSessionDao.getWeeklyTrendFlow()

    suspend fun getActiveSession() = sleepSessionDao.getActiveSession()

    /**
     * Updates the persistent 'Active' session record. If no active session exists, it starts one.
     * Enforces the Single Source of Truth by storing live samples directly in Room.
     */
    suspend fun updateActiveSession(
        spo2: Int,
        bpm: Int,
        movement: Int,
        audio: Int,
        isApneaEvent: Boolean = false,
        isRestlessEvent: Boolean = false,
        appendSample: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateStr = sdf.format(Date())
        
        val currentActive = sleepSessionDao.getActiveSession()
        
        val updatedSession = if (currentActive == null) {
            // Initialize new clinical session
            SleepSessionEntity(
                dateString = dateStr,
                startTimeStamp = System.currentTimeMillis(),
                endTimeStamp = System.currentTimeMillis(),
                totalApneaEvents = if (isApneaEvent) 1 else 0,
                totalRestlessEvents = if (isRestlessEvent) 1 else 0,
                avgSpO2 = spo2,
                lowestSpO2 = if (spo2 > 0) spo2 else 100,
                avgBpm = bpm,
                spO2Array = if (spo2 > 0) listOf(spo2) else emptyList(),
                bpmArray = if (bpm > 0) listOf(bpm) else emptyList(),
                movementArray = listOf(movement),
                audioArray = listOf(audio),
                apneaAlertTimestamps = if (isApneaEvent) listOf(System.currentTimeMillis()) else emptyList(),
                isActive = true
            )
        } else {
            // Append to existing session (if flag is true)
            val newSpO2List = if (appendSample && spo2 > 0) currentActive.spO2Array + spo2 else currentActive.spO2Array
            val newBpmList = if (appendSample && bpm > 0) currentActive.bpmArray + bpm else currentActive.bpmArray
            val newMovementList = if (appendSample) currentActive.movementArray + movement else currentActive.movementArray
            val newAudioList = if (appendSample) currentActive.audioArray + audio else currentActive.audioArray
            val newAlerts = if (isApneaEvent) currentActive.apneaAlertTimestamps + System.currentTimeMillis() else currentActive.apneaAlertTimestamps

            currentActive.copy(
                endTimeStamp = System.currentTimeMillis(),
                totalApneaEvents = if (isApneaEvent) currentActive.totalApneaEvents + 1 else currentActive.totalApneaEvents,
                totalRestlessEvents = if (isRestlessEvent) currentActive.totalRestlessEvents + 1 else currentActive.totalRestlessEvents,
                avgSpO2 = if (newSpO2List.isNotEmpty()) newSpO2List.average().toInt() else 0,
                lowestSpO2 = if (spo2 in 1 until currentActive.lowestSpO2) spo2 else currentActive.lowestSpO2,
                avgBpm = if (newBpmList.isNotEmpty()) newBpmList.average().toInt() else 0,
                spO2Array = newSpO2List,
                bpmArray = newBpmList,
                movementArray = newMovementList,
                audioArray = newAudioList,
                apneaAlertTimestamps = newAlerts
            )
        }
        
        sleepSessionDao.insertSession(updatedSession)
    }

    suspend fun finalizeSession() = withContext(Dispatchers.IO) {
        sleepSessionDao.deactivateAllSessions()
    }

    // Process edge arrays (Historical Sync)
    suspend fun processAndCacheBulkNightData(
        spO2Data: List<Int>,
        bpmData: List<Int>,
        apneaAlerts: List<Long>,
        movementData: List<Int>
    ) = withContext(Dispatchers.Default) {
        if (spO2Data.isEmpty() || bpmData.isEmpty()) return@withContext

        val avgSpo2 = spO2Data.filter { it > 0 }.let { if (it.isEmpty()) 0 else it.average().toInt() }
        val lowestSpo2 = spO2Data.filter { it > 0 }.minOrNull() ?: 100
        val avgBpm = bpmData.filter { it > 0 }.let { if (it.isEmpty()) 0 else it.average().toInt() }
        val restlessEpochs = movementData.count { it > 6 } 

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateStr = sdf.format(Date())

        val sessionEntity = SleepSessionEntity(
            dateString = dateStr,
            startTimeStamp = System.currentTimeMillis() - (8 * 3600 * 1000), // Defaulting to 8h for sync data
            endTimeStamp = System.currentTimeMillis(),
            totalApneaEvents = apneaAlerts.size,
            totalRestlessEvents = restlessEpochs,
            avgSpO2 = avgSpo2,
            lowestSpO2 = lowestSpo2,
            avgBpm = avgBpm,
            spO2Array = spO2Data,
            bpmArray = bpmData,
            movementArray = movementData,
            audioArray = List(spO2Data.size) { 0 }, // Bulk data from legacy ring doesn't have audio
            apneaAlertTimestamps = apneaAlerts,
            isActive = false
        )

        withContext(Dispatchers.IO) {
            sleepSessionDao.insertSession(sessionEntity)
        }
    }
}

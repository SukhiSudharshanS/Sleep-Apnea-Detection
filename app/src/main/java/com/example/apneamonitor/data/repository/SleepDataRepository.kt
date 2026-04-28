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

    private fun isValidSpo2(spo2: Int): Boolean = spo2 in 50..100

    private fun buildSessionLabel(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    suspend fun startNewSession(startTimestamp: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        sleepSessionDao.deactivateAllSessions()
        sleepSessionDao.insertSession(
            SleepSessionEntity(
                dateString = buildSessionLabel(startTimestamp),
                startTimeStamp = startTimestamp,
                endTimeStamp = startTimestamp,
                totalApneaEvents = 0,
                totalRestlessEvents = 0,
                avgSpO2 = 0,
                lowestSpO2 = 100,
                avgBpm = 0,
                spO2Array = emptyList(),
                bpmArray = emptyList(),
                movementArray = emptyList(),
                audioArray = emptyList(),
                apneaAlertTimestamps = emptyList(),
                isActive = true
            )
        )
    }

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
        val currentActive = sleepSessionDao.getActiveSession()
        val validSpo2 = if (isValidSpo2(spo2)) spo2 else null

        val updatedSession = if (currentActive == null) {
            val startTimestamp = System.currentTimeMillis()
            SleepSessionEntity(
                dateString = buildSessionLabel(startTimestamp),
                startTimeStamp = startTimestamp,
                endTimeStamp = startTimestamp,
                totalApneaEvents = if (isApneaEvent) 1 else 0,
                totalRestlessEvents = if (isRestlessEvent) 1 else 0,
                avgSpO2 = validSpo2 ?: 0,
                lowestSpO2 = validSpo2 ?: 100,
                avgBpm = if (bpm > 0) bpm else 0,
                spO2Array = validSpo2?.let(::listOf) ?: emptyList(),
                bpmArray = if (bpm > 0) listOf(bpm) else emptyList(),
                movementArray = listOf(movement),
                audioArray = listOf(audio),
                apneaAlertTimestamps = if (isApneaEvent) listOf(System.currentTimeMillis()) else emptyList(),
                isActive = true
            )
        } else {
            // Append to existing session (if flag is true)
            val newSpO2List = if (appendSample && validSpo2 != null) currentActive.spO2Array + validSpo2 else currentActive.spO2Array
            val newBpmList = if (appendSample && bpm > 0) currentActive.bpmArray + bpm else currentActive.bpmArray
            val newMovementList = if (appendSample) currentActive.movementArray + movement else currentActive.movementArray
            val newAudioList = if (appendSample) currentActive.audioArray + audio else currentActive.audioArray
            val newAlerts = if (isApneaEvent) currentActive.apneaAlertTimestamps + System.currentTimeMillis() else currentActive.apneaAlertTimestamps
            val updatedLowestSpo2 = when {
                validSpo2 == null -> currentActive.lowestSpO2
                currentActive.lowestSpO2 == 0 -> validSpo2
                validSpo2 < currentActive.lowestSpO2 -> validSpo2
                else -> currentActive.lowestSpO2
            }

            currentActive.copy(
                endTimeStamp = System.currentTimeMillis(),
                totalApneaEvents = if (isApneaEvent) currentActive.totalApneaEvents + 1 else currentActive.totalApneaEvents,
                totalRestlessEvents = if (isRestlessEvent) currentActive.totalRestlessEvents + 1 else currentActive.totalRestlessEvents,
                avgSpO2 = if (newSpO2List.isNotEmpty()) newSpO2List.average().toInt() else 0,
                lowestSpO2 = updatedLowestSpo2,
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

        val validSpo2Data = spO2Data.filter(::isValidSpo2)
        val avgSpo2 = validSpo2Data.let { if (it.isEmpty()) 0 else it.average().toInt() }
        val lowestSpo2 = validSpo2Data.minOrNull() ?: 100
        val avgBpm = bpmData.filter { it > 0 }.let { if (it.isEmpty()) 0 else it.average().toInt() }
        val restlessEpochs = movementData.count { it > 6 } 

        val endTimestamp = System.currentTimeMillis()

        val sessionEntity = SleepSessionEntity(
            dateString = buildSessionLabel(endTimestamp),
            startTimeStamp = endTimestamp - (8 * 3600 * 1000), // Defaulting to 8h for sync data
            endTimeStamp = endTimestamp,
            totalApneaEvents = apneaAlerts.size,
            totalRestlessEvents = restlessEpochs,
            avgSpO2 = avgSpo2,
            lowestSpO2 = lowestSpo2,
            avgBpm = avgBpm,
            spO2Array = validSpo2Data,
            bpmArray = bpmData,
            movementArray = movementData,
            audioArray = List(validSpo2Data.size) { 0 }, // Bulk data from legacy ring doesn't have audio
            apneaAlertTimestamps = apneaAlerts,
            isActive = false
        )

        withContext(Dispatchers.IO) {
            sleepSessionDao.insertSession(sessionEntity)
        }
    }
}

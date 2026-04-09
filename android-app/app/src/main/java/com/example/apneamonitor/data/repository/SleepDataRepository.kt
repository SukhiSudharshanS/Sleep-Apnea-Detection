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
    private val fusionModel = ApneaFusionModel()

    // Exposed Flows to ViewModel
    val latestSessionFlow: Flow<SleepSessionEntity?> = sleepSessionDao.getLatestSessionFlow()
    val weeklyTrendFlow: Flow<List<WeeklyTrendTuple>> = sleepSessionDao.getWeeklyTrendFlow()

    // Process edge arrays, run ML hooks, and cache to Room
    suspend fun processAndCacheBulkNightData(
        spO2Data: List<Int>,
        bpmData: List<Int>,
        apneaAlerts: List<Long>,
        movementData: List<Int>
    ) = withContext(Dispatchers.Default) {
        if (spO2Data.isEmpty() || bpmData.isEmpty()) return@withContext

        // 1. Structural Pre-processing
        val avgSpo2 = spO2Data.average().toInt()
        val lowestSpo2 = spO2Data.minOrNull() ?: 0
        val avgBpm = bpmData.average().toInt()
        val totalApneas = apneaAlerts.size
        val restlessEpochs = movementData.count { it > 3 } // Parse Restless Events (> 3 intense movement threshold = restless epoch)

        // 2. Machine Learning Pipeline Execution
        // Feed statistical metrics into RF Model
        val rfOutput = fusionModel.processStatisticalFeatures(listOf(avgSpo2, lowestSpo2, avgBpm, totalApneas))
        
        // Feed raw byte array into CNN (simulated byte array payload for CNN ingestion)
        val rawBuffer = ByteArray(spO2Data.size * 2) // mock serialization
        val cnnOutput = fusionModel.processRawTimeSeries(rawBuffer)
        
        // Calculate unified Sleep Score taking Apnea penalty into account
        var sleepScore = fusionModel.calculateApneaRiskIndex(rfOutput, cnnOutput)
        sleepScore -= (totalApneas * 5) // Penalize score per apnea event
        if (sleepScore < 0) sleepScore = 0
        if (sleepScore > 100) sleepScore = 100

        // 3. Schema Formatting
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateStr = sdf.format(Date())

        val sessionEntity = SleepSessionEntity(
            dateString = dateStr,
            startTimeStamp = System.currentTimeMillis() - (8 * 3600 * 1000), // Mock 8 hours ago
            endTimeStamp = System.currentTimeMillis(),
            sleepScore = sleepScore,
            totalApneaEvents = totalApneas,
            totalRestlessEvents = restlessEpochs,
            avgSpO2 = avgSpo2,
            lowestSpO2 = lowestSpo2,
            avgBpm = avgBpm,
            spO2Array = spO2Data,
            bpmArray = bpmData,
            movementArray = movementData,
            apneaAlertTimestamps = apneaAlerts
        )

        // 4. Persistence into SQLite Room Cache safely off the Main Thread
        withContext(Dispatchers.IO) {
            sleepSessionDao.insertSession(sessionEntity)
        }
    }
}

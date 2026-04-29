package com.example.apneamonitor.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.apneamonitor.ApneaApplication
import com.example.apneamonitor.AppBluetoothManager
import com.example.apneamonitor.data.repository.SleepDataRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class ApneaSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private data class BulkPayload(
        val spo2: List<Int>,
        val bpm: List<Int>,
        val alerts: List<Long>,
        val movement: List<Int>
    )

    override suspend fun doWork(): Result {
        val appContext = applicationContext as ApneaApplication
        val repository = SleepDataRepository(appContext.database.sleepSessionDao())
        val bluetoothManager = appContext.bluetoothManager

        return try {
            if (bluetoothManager.connectionState.value != AppBluetoothManager.ConnectionState.DISCONNECTED) {
                Log.w("ApneaSyncWorker", "Skipping background sync because BLE manager is already in use.")
                return Result.success()
            }

            Log.d("ApneaSyncWorker", "Starting background sync scan...")
            val previousHistoricalCallback = bluetoothManager.onHistoricalDataReceived

            // Wait a max of 30 seconds to find the ring and sync data. 
            // If it doesn't complete, it will be cancelled safely.
            val payload = try {
                withTimeoutOrNull(30_000L) {
                    suspendCancellableCoroutine<BulkPayload> { continuation ->
                        bluetoothManager.onHistoricalDataReceived = { spo2, bpm, alerts, movement ->
                            if (continuation.isActive) {
                                continuation.resume(BulkPayload(spo2, bpm, alerts, movement))
                            }
                        }

                        // On cancellation, stop scanning or disconnect to prevent leaks
                        continuation.invokeOnCancellation {
                            bluetoothManager.stopScan()
                            bluetoothManager.disconnect()
                        }

                        bluetoothManager.startScan(isAutoSync = true)
                    }
                }
            } finally {
                bluetoothManager.onHistoricalDataReceived = previousHistoricalCallback
            }

            if (payload != null) {
                Log.d("ApneaSyncWorker", "Payload received, writing to Room...")
                repository.processAndCacheBulkNightData(
                    spO2Data = payload.spo2,
                    bpmData = payload.bpm,
                    apneaAlerts = payload.alerts,
                    movementData = payload.movement
                )
                bluetoothManager.disconnect()
                Result.success()
            } else {
                Log.w("ApneaSyncWorker", "Sync timeout: Ring not found or in range.")
                bluetoothManager.disconnect()
                // Return success anyway, we don't need to infinitely retry for a daily cron
                Result.success()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            bluetoothManager.disconnect()
            Result.failure()
        }
    }
}

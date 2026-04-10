package com.example.apneamonitor.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.apneamonitor.AppBluetoothManager
import com.example.apneamonitor.data.local.SleepSessionEntity
import com.example.apneamonitor.data.local.WeeklyTrendTuple
import com.example.apneamonitor.data.repository.SleepDataRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.Context
import com.example.apneamonitor.utils.PdfGenerator
import java.text.SimpleDateFormat
import java.util.*

class ApneaViewModel(
    private val repository: SleepDataRepository,
    private val bluetoothManager: AppBluetoothManager
) : ViewModel() {

    val riskScore = bluetoothManager.latestInferenceScore
    val timeUntilNextAssessment = MutableStateFlow(60)

    // Expose Repository flows as Compose-friendly StateFlows (Single Source of Truth)
    val latestSession: StateFlow<SleepSessionEntity?> = repository.latestSessionFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeSession: StateFlow<SleepSessionEntity?> = repository.activeSessionFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val weeklyTrend: StateFlow<List<WeeklyTrendTuple>> = repository.weeklyTrendFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Live Instantaneous Metrics (Passthrough from BLE)
    val connectionState = bluetoothManager.connectionState
    val liveSpo2 = bluetoothManager.spo2
    val liveBpm = bluetoothManager.bpm
    val liveApneaAlert = bluetoothManager.apneaAlert
    val liveMovement = bluetoothManager.liveMovement
    val liveAudioLevel = bluetoothManager.audioLevel

    private var hasRestlessSpike = false

    init {
        // Wire the Bluetooth Manager's Bulk Callback directly into the Repository processing logic
        bluetoothManager.onHistoricalDataReceived = { rawSpo2, rawBpm, rawApneaAlerts, rawMovement ->
            viewModelScope.launch {
                repository.processAndCacheBulkNightData(
                    spO2Data = rawSpo2, 
                    bpmData = rawBpm, 
                    apneaAlerts = rawApneaAlerts, 
                    movementData = rawMovement
                )
            }
        }

            // --- THE ENGINE: Persistent Telemetry Sink ---
        viewModelScope.launch {
            // Observe the 4 core telemetry streams and sync to DB every second
            // This ensures Dashboard, CSV, and PDF all read from the same source.
            combine(liveSpo2, liveBpm, liveMovement, liveAudioLevel) { spo2, bpm, movement, audio ->
                // Flag movement spikes in the current window
                if (movement > 6) {
                    hasRestlessSpike = true
                }

                // Only update if we are connected and receiving valid data
                if (connectionState.value == AppBluetoothManager.ConnectionState.CONNECTED && (spo2 > 0 || bpm > 0)) {
                    repository.updateActiveSession(
                        spo2 = spo2,
                        bpm = bpm,
                        movement = movement,
                        audio = audio,
                        isApneaEvent = false,
                        isRestlessEvent = false
                    )
                }
            }.collect { /* Triggered by combine */ }
        }

        viewModelScope.launch {
            bluetoothManager.inferenceTrigger.collect {
                timeUntilNextAssessment.value = 60
                
                val currentRisk = riskScore.value
                val currentRestless = hasRestlessSpike
                
                // Finalize detection for this 60s window
                val session = repository.getActiveSession()
                if (session != null) {
                    repository.updateActiveSession(
                        spo2 = liveSpo2.value,
                        bpm = liveBpm.value,
                        movement = liveMovement.value,
                        audio = liveAudioLevel.value,
                        isApneaEvent = currentRisk > 60,
                        isRestlessEvent = currentRestless,
                        appendSample = false
                    )
                }
                
                // Reset window flags
                hasRestlessSpike = false
            }
        }

        // Dedicated Countdown Loop
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
             while (true) {
                 if (timeUntilNextAssessment.value > 0) {
                     timeUntilNextAssessment.value -= 1
                 }
                 delay(1000)
             }
        }
    }

    // Interaction Hooks
    fun disconnect() {
        viewModelScope.launch {
            repository.finalizeSession()
            bluetoothManager.disconnect()
        }
    }

    
    // Lifecycle / Interaction Hooks
    fun connectOrSync() {
        if (connectionState.value == AppBluetoothManager.ConnectionState.DISCONNECTED) {
            if (!bluetoothManager.connectToSavedDevice()) {
                bluetoothManager.startScan(isAutoSync = true)
            }
        }
    }

    fun generateSessionSummary(context: Context): Boolean {
        // Pull the absolute latest session from the DB to ensure perfect data alignment
        val session = activeSession.value ?: latestSession.value
        
        if (session == null) return false
        
        val pdfGenerator = PdfGenerator(context)
        return pdfGenerator.generateAndSaveReport(session)
    }
    
    override fun onCleared() {
        super.onCleared()
        bluetoothManager.disconnect()
    }
}

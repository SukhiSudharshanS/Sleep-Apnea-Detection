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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

class ApneaViewModel(
    private val repository: SleepDataRepository,
    private val bluetoothManager: AppBluetoothManager
) : ViewModel() {

    val riskScore = bluetoothManager.latestInferenceScore
    val timeUntilNextAssessment = kotlinx.coroutines.flow.MutableStateFlow(60)

    // Expose Repository flows as Compose-friendly StateFlows
    val latestSession: StateFlow<SleepSessionEntity?> = repository.latestSessionFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val weeklyTrend: StateFlow<List<WeeklyTrendTuple>> = repository.weeklyTrendFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Passthrough Live Hardware States bounds from BluetoothManager
    val connectionState = bluetoothManager.connectionState
    val liveSpo2 = bluetoothManager.spo2
    val liveBpm = bluetoothManager.bpm
    val liveApneaAlert = bluetoothManager.apneaAlert
    val liveMovement = bluetoothManager.liveMovement
    val liveAudioLevel = bluetoothManager.audioLevel

    init {
        // Wire the Bluetooth Manager's Bulk Callback directly into the Repository processing logic
        bluetoothManager.onHistoricalDataReceived = { rawSpo2, rawBpm, rawApneaAlerts, rawMovement ->
            viewModelScope.launch {
                // Suspends until Room cache is updated
                repository.processAndCacheBulkNightData(
                    spO2Data = rawSpo2, 
                    bpmData = rawBpm, 
                    apneaAlerts = rawApneaAlerts, 
                    movementData = rawMovement
                )
            }
        }

        // Assessment Countdown Timer logic
        viewModelScope.launch {
            // Tiny delay to ensure all StateFlows are bound if the dispatcher is immediate
            delay(100)
            bluetoothManager.inferenceTrigger.collect {
                // Reset timer whenever a new inference event fires (ignores value identity)
                timeUntilNextAssessment.value = 60
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

    
    // Lifecycle / Interaction Hooks
    fun connectOrSync() {
        if (connectionState.value == AppBluetoothManager.ConnectionState.DISCONNECTED) {
            if (!bluetoothManager.connectToSavedDevice()) {
                bluetoothManager.startScan(isAutoSync = true)
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        bluetoothManager.disconnect()
    }
}

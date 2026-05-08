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

class ApneaViewModel(
    private val repository: SleepDataRepository,
    private val bluetoothManager: AppBluetoothManager
) : ViewModel() {

    private val _riskScore = MutableStateFlow(0)
    val riskScore: StateFlow<Int> = _riskScore.asStateFlow()
    val timeUntilNextAssessment = MutableStateFlow(60)

    // Tracks whether the user has explicitly started a session.
    // Gates the telemetry sink so it never auto-creates phantom sessions.
    private val _isSessionActive = MutableStateFlow(false)

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

    private var previousMovementScore: Int = 0
    private var lastRestlessEventTime: Long = 0L

    private fun resetSessionUiState() {
        previousMovementScore = liveMovement.value
        lastRestlessEventTime = 0L
        _riskScore.value = 0
        timeUntilNextAssessment.value = 60
    }

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

        viewModelScope.launch {
            connectionState.collectLatest { state ->
                if (state == AppBluetoothManager.ConnectionState.CONNECTED) {
                    previousMovementScore = liveMovement.value
                    lastRestlessEventTime = 0L
                }
            }
        }

        viewModelScope.launch {
            bluetoothManager.latestInferenceScore.collectLatest { score ->
                _riskScore.value = score
            }
        }

        // --- THE ENGINE: Persistent Telemetry Sink ---
        viewModelScope.launch {
            // Observe the 4 core telemetry streams and sync to DB every second
            // This ensures Dashboard, CSV, and PDF all read from the same source.
            combine(liveSpo2, liveBpm, liveMovement, liveAudioLevel) { spo2, bpm, movement, audio ->
                val currentTime = System.currentTimeMillis()
                val currentMovementScore = movement
                val movementJump = currentMovementScore - previousMovementScore
                val isRestlessJerk =
                    movementJump >= 5 && (currentTime - lastRestlessEventTime) > 10_000L

                // Only write to DB when the user has explicitly started a session.
                // Allow write if vitals are valid OR if a restless jerk was detected
                // (movement can spike even if finger briefly lifts off the sensor).
                if (
                    _isSessionActive.value &&
                    connectionState.value == AppBluetoothManager.ConnectionState.CONNECTED &&
                    (spo2 > 0 || bpm > 0 || isRestlessJerk)
                ) {
                    repository.updateActiveSession(
                        spo2 = spo2,
                        bpm = bpm,
                        movement = movement,
                        audio = audio,
                        isApneaEvent = false,
                        isRestlessEvent = isRestlessJerk
                    )

                    if (isRestlessJerk) {
                        lastRestlessEventTime = currentTime
                    }
                }

                previousMovementScore = currentMovementScore
            }.collect { /* Triggered by combine */ }
        }

        viewModelScope.launch {
            bluetoothManager.inferenceTrigger.collect {
                timeUntilNextAssessment.value = 60
                
                val currentRisk = riskScore.value
                
                // Finalize detection for this 60s window
                val session = repository.getActiveSession()
                if (session != null) {
                    repository.updateActiveSession(
                        spo2 = liveSpo2.value,
                        bpm = liveBpm.value,
                        movement = liveMovement.value,
                        audio = liveAudioLevel.value,
                        isApneaEvent = currentRisk > 60,
                        isRestlessEvent = false,
                        appendSample = false
                    )
                }
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
    fun startNewSession() {
        viewModelScope.launch {
            resetSessionUiState()
            _isSessionActive.value = true
            bluetoothManager.setSessionRecording(true)
            repository.startNewSession()
        }
    }

    fun stopSession() {
        viewModelScope.launch {
            _isSessionActive.value = false
            bluetoothManager.setSessionRecording(false)
            repository.finalizeSession()
            resetSessionUiState()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            _isSessionActive.value = false
            bluetoothManager.setSessionRecording(false)
            repository.finalizeSession()
            resetSessionUiState()
            bluetoothManager.disconnect(userInitiated = true)
        }
    }

    
    // Lifecycle / Interaction Hooks
    fun connectOrSync() {
        bluetoothManager.ensureConnected(isAutoSync = true)
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
    }
}

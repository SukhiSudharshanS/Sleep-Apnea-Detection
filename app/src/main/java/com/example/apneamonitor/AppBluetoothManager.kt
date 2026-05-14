package com.example.apneamonitor

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

@SuppressLint("MissingPermission")
class AppBluetoothManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner = bluetoothAdapter?.bluetoothLeScanner
    private var bluetoothGatt: BluetoothGatt? = null
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isConnecting = false
    private var shouldMaintainConnection = false
    private var isSessionRecording = false
    private var reconnectJob: Job? = null

    private val prefs = context.getSharedPreferences("ApneaPrefs", Context.MODE_PRIVATE)
    private var connectedTimestamp = 0L

    // Reassembler Buffers
    private val spo2Buffer = ArrayList<Int>()
    private val bpmBuffer = ArrayList<Int>()
    private val movementBuffer = ArrayList<Int>()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _spo2 = MutableStateFlow(0)
    val spo2: StateFlow<Int> = _spo2.asStateFlow()

    private val _bpm = MutableStateFlow(0)
    val bpm: StateFlow<Int> = _bpm.asStateFlow()

    private val _apneaAlert = MutableStateFlow(0)
    val apneaAlert: StateFlow<Int> = _apneaAlert.asStateFlow()

    private val _movement = MutableStateFlow(0)
    val liveMovement: StateFlow<Int> = _movement.asStateFlow()

    private val _audioLevel = MutableStateFlow(0)
    val audioLevel: StateFlow<Int> = _audioLevel.asStateFlow()

    private val _latestInferenceScore = MutableStateFlow(0)
    val latestInferenceScore: StateFlow<Int> = _latestInferenceScore.asStateFlow()

    private val _inferenceTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val inferenceTrigger: SharedFlow<Unit> = _inferenceTrigger.asSharedFlow()

    // Specific Callback Hook for Bulk Historical Transfer (for the ViewModel repository layer)
    var onHistoricalDataReceived: ((List<Int>, List<Int>, List<Long>, List<Int>) -> Unit)? = null

    companion object {
        const val DEVICE_NAME = "MAX30102_Ring"
        val SERVICE_UUID: UUID = UUID.fromString("19b10000-e8f2-537e-4f6c-d104768a1214")
        val CHAR_LIVE_DATA_UUID: UUID = UUID.fromString("19b10001-e8f2-537e-4f6c-d104768a1214")
        val CHAR_ALERT_UUID: UUID = UUID.fromString("19b10002-e8f2-537e-4f6c-d104768a1214")
        val CHAR_BULK_DATA_UUID: UUID = UUID.fromString("19b10003-e8f2-537e-4f6c-d104768a1214")
        
        val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    enum class ConnectionState {
        DISCONNECTED, SCANNING, CONNECTING, CONNECTED, AUTO_SYNCING
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { sr ->
                if (isConnecting) return
                if (!isTargetDevice(sr.device)) return
                
                Log.d("ApneaBLE", "Device found: ${sr.device.address}. Starting connection...")
                
                isConnecting = true
                stopScan(updateState = false)
                connectToDevice(sr.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("ApneaBLE", "Scan failed to start with error code: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d("ApneaBLE", "Connection state changed: $newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectJob?.cancel()
                    connectedTimestamp = System.currentTimeMillis()
                    prefs.edit().putString("SAVED_MAC", gatt.device.address).apply()
                    bluetoothGatt = gatt
                    isConnecting = false
                    _connectionState.value = ConnectionState.CONNECTED
                    // Android 14 / NimBLE Fix: Wait 2s before service discovery to stabilize stack
                    scope.launch {
                        delay(2000)
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    clearLiveTelemetry()
                    if (bluetoothGatt === gatt) {
                        bluetoothGatt = null
                    }
                    gatt.close()
                    bluetoothGatt = null
                    isConnecting = false
                    if (shouldMaintainConnection) {
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    val liveDataChar = service.getCharacteristic(CHAR_LIVE_DATA_UUID)
                    val alertChar = service.getCharacteristic(CHAR_ALERT_UUID)
                    
                    if (liveDataChar == null) {
                        Log.e("ApneaBLE", "Live data characteristic not found: $CHAR_LIVE_DATA_UUID")
                    } else {
                        val notificationEnabled = gatt.setCharacteristicNotification(liveDataChar, true)
                        if (!notificationEnabled) {
                            Log.e("ApneaBLE", "Failed to enable local notifications for live data")
                        } else {
                            enableNotificationDescriptor(gatt, liveDataChar)
                        }
                    }

                    if (alertChar == null) {
                        Log.e("ApneaBLE", "Alert characteristic not found: $CHAR_ALERT_UUID")
                    } else {
                        val alertEnabled = gatt.setCharacteristicNotification(alertChar, true)
                        if (!alertEnabled) {
                            Log.e("ApneaBLE", "Failed to enable local notifications for alert data")
                        }
                    }
                } else {
                    Log.e("ApneaBLE", "Required BLE service not found: $SERVICE_UUID")
                }
            } else {
                Log.e("ApneaBLE", "Service discovery failed with status: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            parseCharacteristicValue(characteristic.uuid, value)
        }
        
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            parseCharacteristicValue(characteristic.uuid, characteristic.value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.characteristic.uuid == CHAR_LIVE_DATA_UUID) {
                // Once Live Data descriptor is written, enable Alert characteristic descriptor
                val alertChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_ALERT_UUID)
                if (alertChar != null) {
                    enableNotificationDescriptor(gatt, alertChar)
                }
            }
        }
    }

    private fun isTargetDevice(device: BluetoothDevice): Boolean {
        val savedMac = prefs.getString("SAVED_MAC", null)
        return when {
            savedMac != null -> device.address == savedMac || device.name == DEVICE_NAME
            device.name != null -> device.name == DEVICE_NAME
            else -> true
        }
    }

    private fun scheduleReconnect(delayMs: Long = 2000L) {
        if (!shouldMaintainConnection) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (shouldMaintainConnection && _connectionState.value != ConnectionState.CONNECTED) {
                if (!isConnecting) {
                    ensureConnected(isAutoSync = true)
                }
                delay(if (isSessionRecording) 5000L else delayMs)
            }
        }
    }

    private fun clearLiveTelemetry() {
        _spo2.value = 0
        _bpm.value = 0
        _movement.value = 0
        _audioLevel.value = 0
        _apneaAlert.value = 0
        _latestInferenceScore.value = 0
    }

    private fun enableNotificationDescriptor(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID)
        if (descriptor == null) {
            Log.e("ApneaBLE", "CCCD descriptor missing for ${characteristic.uuid}")
            return
        }

        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        }

        if (!success) {
            Log.e("ApneaBLE", "Failed to write CCCD for ${characteristic.uuid}")
        }
    }

    private fun parseCharacteristicValue(uuid: UUID, value: ByteArray) {
        if (value.isEmpty()) return

        when (uuid) {
            CHAR_LIVE_DATA_UUID -> {
                if (value.size < 4) {
                    Log.e("ApneaBLE", "Malformed live payload: expected >= 4 bytes, got ${value.size}")
                    return
                }

                _spo2.value = value[0].toUByte().toInt()
                _bpm.value = value[1].toUByte().toInt()
                _movement.value = value[2].toUByte().toInt()
                _audioLevel.value = value[3].toUByte().toInt()
            }
            CHAR_ALERT_UUID -> {
                if (value.isNotEmpty()) {
                    val score = value[0].toUByte().toInt()
                    _latestInferenceScore.value = score
                    
                    // Fire the hardware event trigger to reset UI timers
                    _inferenceTrigger.tryEmit(Unit)
                } else {
                    Log.w("ApneaBLE", "Dropped empty Alert packet")
                }
            }
            CHAR_BULK_DATA_UUID -> {
                val header = value[0].toUByte().toInt()
                val data = value.sliceArray(1 until value.size)
                
                when (header) {
                    0xAA -> { // SpO2 Header
                        data.forEach { spo2Buffer.add(it.toUByte().toInt()) }
                        if (Log.isLoggable("ApneaBLE", Log.DEBUG)) {
                            Log.d("ApneaBLE", "Sync Progress | SpO2: ${spo2Buffer.size} bytes collected")
                        }
                    }
                    0xBB -> { // BPM Header
                        data.forEach { bpmBuffer.add(it.toUByte().toInt()) }
                        if (Log.isLoggable("ApneaBLE", Log.DEBUG)) {
                            Log.d("ApneaBLE", "Sync Progress | BPM: ${bpmBuffer.size} bytes collected")
                        }
                    }
                    0xCC -> { // Movement Header
                        data.forEach { movementBuffer.add(it.toUByte().toInt()) }
                        if (Log.isLoggable("ApneaBLE", Log.DEBUG)) {
                            Log.d("ApneaBLE", "Sync Progress | Movement: ${movementBuffer.size} bytes collected")
                        }
                    }
                    0xFF -> { // End of Transmission
                        Log.d("ApneaBLE", "Sync Complete. Reassembled SpO2: ${spo2Buffer.size}, BPM: ${bpmBuffer.size}, Movement: ${movementBuffer.size}")
                        // Trigger repository update via ViewModel callback
                        onHistoricalDataReceived?.invoke(
                            ArrayList(spo2Buffer), 
                            ArrayList(bpmBuffer), 
                            emptyList(), 
                            ArrayList(movementBuffer)
                        )
                        // Clear buffers for next sync
                        spo2Buffer.clear()
                        bpmBuffer.clear()
                        movementBuffer.clear()
                    }
                }
            }
        }
    }

    fun connectToSavedDevice(): Boolean {
        val savedMac = prefs.getString("SAVED_MAC", null)
        if (savedMac != null && bluetoothAdapter != null) {
            try {
                shouldMaintainConnection = true
                reconnectJob?.cancel()
                isConnecting = true
                val device = bluetoothAdapter.getRemoteDevice(savedMac)
                _connectionState.value = ConnectionState.CONNECTING
                bluetoothGatt = device.connectGatt(context, true, gattCallback)
                return true
            } catch (e: Exception) {
                isConnecting = false
                Log.e("ApneaBLE", "Error connecting to saved device: ${e.message}")
            }
        }
        return false
    }

    fun hasSavedDevice(): Boolean = prefs.getString("SAVED_MAC", null) != null

    fun ensureConnected(isAutoSync: Boolean = true) {
        shouldMaintainConnection = true
        if (_connectionState.value == ConnectionState.CONNECTED || isConnecting) return
        if (!connectToSavedDevice()) {
            startScan(isAutoSync = isAutoSync)
        }
    }

    fun setSessionRecording(isRecording: Boolean) {
        isSessionRecording = isRecording
        if (isRecording) {
            shouldMaintainConnection = true
            if (_connectionState.value != ConnectionState.CONNECTED) {
                scheduleReconnect(delayMs = 5000L)
            }
        }
    }

    fun startScan(isAutoSync: Boolean = false) {
        if (scanner == null) return
        shouldMaintainConnection = true
        reconnectJob?.cancel()
        if (_connectionState.value == ConnectionState.SCANNING || _connectionState.value == ConnectionState.AUTO_SYNCING) {
            return
        }
        isConnecting = false 
        Log.d("ApneaBLE", "Scan started. Looking for Service UUID: $SERVICE_UUID")
        _connectionState.value = if(isAutoSync) ConnectionState.AUTO_SYNCING else ConnectionState.SCANNING

        // Filter by Service UUID only; name matching is done in the callback
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScan(updateState: Boolean = true) {
        scanner?.stopScan(scanCallback)
        if (
            updateState &&
            (
                _connectionState.value == ConnectionState.SCANNING ||
                _connectionState.value == ConnectionState.AUTO_SYNCING
            )
        ) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        shouldMaintainConnection = true
        reconnectJob?.cancel()
        isConnecting = true
        _connectionState.value = ConnectionState.CONNECTING
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect(userInitiated: Boolean = true) {
        shouldMaintainConnection = !userInitiated
        if (userInitiated) {
            isSessionRecording = false
        }
        reconnectJob?.cancel()
        stopScan(updateState = false)
        val gatt = bluetoothGatt
        if (gatt != null) {
            gatt.disconnect()
            if (userInitiated) {
                gatt.close()
                bluetoothGatt = null
                clearLiveTelemetry()
            }
        } else {
            _connectionState.value = ConnectionState.DISCONNECTED
            clearLiveTelemetry()
        }
        isConnecting = false
    }
}

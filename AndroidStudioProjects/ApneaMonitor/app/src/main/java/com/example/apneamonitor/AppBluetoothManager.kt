package com.example.apneamonitor

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
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
                
                Log.d("ApneaBLE", "Device found: ${sr.device.address}. Starting connection...")
                
                isConnecting = true
                stopScan()
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
                    connectedTimestamp = System.currentTimeMillis()
                    prefs.edit().putString("SAVED_MAC", gatt.device.address).apply()
                    _connectionState.value = ConnectionState.CONNECTED
                    // Android 14 / NimBLE Fix: Wait 2s before service discovery to stabilize stack
                    scope.launch {
                        delay(2000)
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    isConnecting = false
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                simulateChunkedHistoricalSync()

                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    val liveDataChar = service.getCharacteristic(CHAR_LIVE_DATA_UUID)
                    val alertChar = service.getCharacteristic(CHAR_ALERT_UUID)
                    
                    // Enable local notifications for BOTH immediately as requested
                    if (liveDataChar != null) gatt.setCharacteristicNotification(liveDataChar, true)
                    if (alertChar != null) gatt.setCharacteristicNotification(alertChar, true)

                    // Start the sequential descriptor write chain (needed for standard BLE stacks)
                    if (liveDataChar != null) {
                        enableNotificationDescriptor(gatt, liveDataChar)
                    }
                }
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

    // Temporary Mock function for "Chunked Transfer" of past night's data.
    private fun simulateChunkedHistoricalSync() {
        _connectionState.value = ConnectionState.AUTO_SYNCING
        
        // Mocking an 8-hour sleep session's worth of parsed Arrays 
        // We shrink standard frequency to fit in memory
        val mockSpO2 = List(250) { (92..99).random() } 
        val mockBpm = List(250) { (45..75).random() }
        // Mock 4 events over the 8hr span
        val mockApneas = List(4) { System.currentTimeMillis() - (it * 3600000L) }
        // Mock Movement (mostly 0s, with occasional tossing spikes)
        val mockMovement = List(250) { if (Math.random() > 0.9) (4..10).random() else (0..2).random() }
        
        onHistoricalDataReceived?.invoke(mockSpO2, mockBpm, mockApneas, mockMovement)
        
        // Return to normal Connected live view after sync
        _connectionState.value = ConnectionState.CONNECTED
    }

    private fun enableNotificationDescriptor(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val descriptor = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun parseCharacteristicValue(uuid: UUID, value: ByteArray) {
        if (value.isEmpty()) return

        when (uuid) {
            CHAR_LIVE_DATA_UUID -> {
                Log.d("ApneaBLE", "Live Packet Received: Size=${value.size}, Data=${value.joinToString()}")
                if (value.size >= 4) { // Defensive payload check
                    _spo2.value = value[0].toUByte().toInt()
                    _bpm.value = value[1].toUByte().toInt()
                    _movement.value = value[2].toUByte().toInt() // 3rd byte: Movement
                    _audioLevel.value = value[3].toUByte().toInt() // 4th byte: Audio
                } else {
                    Log.w("ApneaBLE", "Dropped undersized Live Data packet: ${value.size} bytes")
                }
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
                        Log.d("ApneaBLE", "Sync Progress | SpO2: ${spo2Buffer.size} bytes collected")
                    }
                    0xBB -> { // BPM Header
                        data.forEach { bpmBuffer.add(it.toUByte().toInt()) }
                        Log.d("ApneaBLE", "Sync Progress | BPM: ${bpmBuffer.size} bytes collected")
                    }
                    0xCC -> { // Movement Header
                        data.forEach { movementBuffer.add(it.toUByte().toInt()) }
                        Log.d("ApneaBLE", "Sync Progress | Movement: ${movementBuffer.size} bytes collected")
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
                val device = bluetoothAdapter.getRemoteDevice(savedMac)
                _connectionState.value = ConnectionState.CONNECTING
                bluetoothGatt = device.connectGatt(context, true, gattCallback)
                return true
            } catch (e: Exception) {
                Log.e("ApneaBLE", "Error connecting to saved device: ${e.message}")
            }
        }
        return false
    }

    fun startScan(isAutoSync: Boolean = false) {
        if (scanner == null) return
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

    fun stopScan() {
        scanner?.stopScan(scanCallback)
        if (
            _connectionState.value == ConnectionState.SCANNING || 
            _connectionState.value == ConnectionState.AUTO_SYNCING
        ) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.CONNECTING
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}

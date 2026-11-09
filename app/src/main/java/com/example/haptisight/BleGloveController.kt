package com.example.haptisight

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

enum class BleConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED
}

/**
 * Hardware-agnostic Bluetooth Low Energy controller supporting ESP32, Seeed Studio XIAO,
 * and Arduino microcontrollers using the standard Nordic UART BLE Service.
 *
 * Streams 5-byte ASCII packets: "<motorPatternCode>\n" (e.g. "100S\n", "000N\n")
 * using WRITE_TYPE_NO_RESPONSE for sub-15ms actuation latency.
 */
class BleGloveController(private val context: Context) {

    companion object {
        private const val TAG = "BleGloveController"
        const val TARGET_DEVICE_NAME = "HaptiSight-Glove"

        // Standard Nordic UART Service & TX Characteristic UUIDs
        val UART_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val UART_TX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

        private const val SCAN_PERIOD_MS = 15000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    var connectionState: BleConnectionState = BleConnectionState.DISCONNECTED
        private set

    val isConnected: Boolean
        get() = connectionState == BleConnectionState.CONNECTED

    var onStateChangedListener: ((BleConnectionState) -> Unit)? = null

    private var isScanning = false
    private var lastSentPattern: String = ""
    private var lastSendTimeMs: Long = 0L

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val deviceName = try {
                if (hasBleConnectPermission()) device.name else null
            } catch (e: SecurityException) {
                null
            }

            val serviceUuids = result.scanRecord?.serviceUuids
            val hasUartService = serviceUuids?.any { it.uuid == UART_SERVICE_UUID } == true

            if ((deviceName != null && deviceName.contains(TARGET_DEVICE_NAME, ignoreCase = true)) || hasUartService) {
                Log.i(TAG, "Found target glove device: ${device.address} ($deviceName)")
                stopScan()
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed with error code: $errorCode")
            isScanning = false
            connectionState = BleConnectionState.DISCONNECTED
            notifyStateChanged()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected to GATT server, discovering services...")
                    connectionState = BleConnectionState.CONNECTING
                    notifyStateChanged()
                    try {
                        if (hasBleConnectPermission()) {
                            gatt?.discoverServices()
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException discovering services", e)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected from GATT server")
                    connectionState = BleConnectionState.DISCONNECTED
                    txCharacteristic = null
                    notifyStateChanged()
                    try {
                        if (hasBleConnectPermission()) {
                            gatt?.close()
                        }
                    } catch (e: SecurityException) {
                        // ignore
                    }
                    bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                val service = gatt.getService(UART_SERVICE_UUID)
                if (service != null) {
                    val characteristic = service.getCharacteristic(UART_TX_CHAR_UUID)
                    if (characteristic != null) {
                        txCharacteristic = characteristic
                        connectionState = BleConnectionState.CONNECTED
                        Log.i(TAG, "Nordic UART TX characteristic discovered and ready")
                        notifyStateChanged()
                        sendPacket("000N")
                    } else {
                        Log.e(TAG, "Nordic UART TX characteristic not found")
                    }
                } else {
                    Log.e(TAG, "Nordic UART Service not found on connected peripheral")
                }
            } else {
                Log.w(TAG, "Service discovery failed with status: $status")
            }
        }
    }

    fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasBleConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun startScan() {
        if (!hasBlePermissions() || bluetoothAdapter == null || !bluetoothAdapter.isEnabled || isScanning) {
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return
        val filterByName = ScanFilter.Builder().setDeviceName(TARGET_DEVICE_NAME).build()
        val filterByUuid = ScanFilter.Builder().setServiceUuid(ParcelUuid(UART_SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            isScanning = true
            connectionState = BleConnectionState.SCANNING
            notifyStateChanged()
            scanner.startScan(listOf(filterByName, filterByUuid), settings, scanCallback)
            mainHandler.postDelayed({
                if (isScanning && connectionState == BleConnectionState.SCANNING) {
                    stopScan()
                }
            }, SCAN_PERIOD_MS)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting BLE scan", e)
            isScanning = false
            connectionState = BleConnectionState.DISCONNECTED
            notifyStateChanged()
        }
    }

    fun stopScan() {
        if (!isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner != null && hasBlePermissions()) {
            try {
                scanner.stopScan(scanCallback)
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException stopping BLE scan", e)
            }
        }
        isScanning = false
        if (connectionState == BleConnectionState.SCANNING) {
            connectionState = BleConnectionState.DISCONNECTED
            notifyStateChanged()
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasBleConnectPermission()) return
        connectionState = BleConnectionState.CONNECTING
        notifyStateChanged()

        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException connecting to device", e)
            connectionState = BleConnectionState.DISCONNECTED
            notifyStateChanged()
        }
    }

    /**
     * Transmits the 4-character ASCII motor pattern code with newline: "<motorPatternCode>\n"
     * Example: "101S\n" (Left + Right motors Short Buzz), "000N\n" (Silent)
     */
    fun sendPacket(motorPatternCode: String): Boolean {
        val gatt = bluetoothGatt ?: return false
        val characteristic = txCharacteristic ?: return false
        if (connectionState != BleConnectionState.CONNECTED) return false

        val now = SystemClock.elapsedRealtime()
        // Deduplicate packets: if state hasn't changed and last send was < 250ms ago, skip writing
        if (motorPatternCode == lastSentPattern && (now - lastSendTimeMs < 250L)) {
            return true
        }

        lastSentPattern = motorPatternCode
        lastSendTimeMs = now
        val payload = "$motorPatternCode\n".toByteArray(Charsets.US_ASCII)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = payload
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException writing characteristic", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Exception writing characteristic", e)
            false
        }
    }

    fun close() {
        stopScan()
        try {
            if (hasBleConnectPermission()) {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            }
        } catch (e: SecurityException) {
            // ignore
        }
        bluetoothGatt = null
        txCharacteristic = null
        connectionState = BleConnectionState.DISCONNECTED
        notifyStateChanged()
    }

    private fun notifyStateChanged() {
        mainHandler.post {
            onStateChangedListener?.invoke(connectionState)
        }
    }
}


package com.phonebridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.UUID

/**
 * BLE link to the board: Nordic UART Service, newline-terminated JSON lines.
 *
 * All callbacks to [listener] are delivered on the main thread.
 * Permissions are checked by the caller before [connect].
 */
@SuppressLint("MissingPermission")
class BleLink(context: Context, private val listener: Listener) {

    interface Listener {
        fun onStateChanged(state: State, deviceName: String)
        fun onLine(line: String)
        fun onLog(text: String)
        /** One ADPCM packet from the board's mic (called on a Bluetooth thread). */
        fun onAudio(packet: ByteArray) {}
    }

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED }

    companion object {
        const val NAME_PREFIX = "MuseGadget"
        val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // phone writes
        val TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // board notifies
        val AUDIO: UUID = UUID.fromString("6E400004-B5A3-F393-E0A9-E50E24DCCA9E") // ADPCM both ways
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 15_000L
    }

    private val appContext = context.applicationContext
    private val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val main = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var audioChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private val rxBuf = StringBuilder()

    // One GATT write in flight at a time; chunks of a line are queued in order.
    private class Op(val audio: Boolean, val data: ByteArray)
    private val writeQueue = ArrayDeque<Op>()
    private var writeBusy = false

    var state = State.IDLE
        private set
    var deviceName = ""
        private set
    private var wantedName: String? = null

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    /** Largest audio packet the board accepts in one write/notification. */
    val audioPayload: Int get() = (mtu - 3).coerceIn(20, 244)

    // ---------------------------------------------------------------- scan

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val match = wantedName?.let { name == it } ?: name.startsWith(NAME_PREFIX)
            if (state == State.SCANNING && match) {
                stopScan()
                connectTo(result.device, name)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Scan failed ($errorCode)")
            setState(State.IDLE)
        }
    }

    private val scanTimeout = Runnable {
        if (state == State.SCANNING) {
            stopScan()
            log("Board not found. Is it powered and advertising?")
            setState(State.IDLE)
        }
    }

    /** Scans for [preferredName] (the board seen before) or, if null, any MuseGadget board. */
    fun connect(preferredName: String? = null) {
        if (state != State.IDLE) return
        wantedName = preferredName
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            log("Bluetooth is off")
            return
        }
        setState(State.SCANNING)
        log("Scanning for ${preferredName ?: "$NAME_PREFIX…"}")
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        main.removeCallbacks(scanTimeout)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    // ---------------------------------------------------------------- connect

    private fun connectTo(device: BluetoothDevice, name: String) {
        deviceName = name
        setState(State.CONNECTING)
        log("Connecting to $name")
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        stopScan()
        gatt?.disconnect()
        cleanup(null)
    }

    private fun cleanup(reason: String?) {
        gatt?.close()
        gatt = null
        rxChar = null
        audioChar = null
        synchronized(writeQueue) {
            writeQueue.clear()
            writeBusy = false
        }
        if (state != State.IDLE) {
            log("Disconnected" + (reason?.let { ": $it" } ?: ""))
            setState(State.IDLE)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                // Short connection interval: voice needs ~10 KB/s each way.
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.requestMtu(247) // continues in onMtuChanged
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post { cleanup(if (status == BluetoothGatt.GATT_SUCCESS) null else "status $status") }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SERVICE)
            val rx = svc?.getCharacteristic(RX)
            val tx = svc?.getCharacteristic(TX)
            if (rx == null || tx == null) {
                main.post { log("Board service not found"); disconnect() }
                return
            }
            rxChar = rx
            audioChar = svc.getCharacteristic(AUDIO)
            rxBuf.setLength(0)
            // Enable notifications on AUDIO first (if the board has it), then TX.
            // The board says hello once TX is enabled.
            enableNotify(g, audioChar ?: tx)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid != CCCD) return
            if (d.characteristic.uuid == AUDIO) {
                g.getService(SERVICE)?.getCharacteristic(TX)?.let { enableNotify(g, it) }
                return
            }
            main.post {
                setState(State.CONNECTED)
                log("Connected (MTU $mtu" + (if (audioChar == null) ", board has no audio)" else ")"))
            }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            when (c.uuid) {
                TX -> onBytes(value)
                AUDIO -> listener.onAudio(value)
            }
        }

        // Android 12 and older
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = c.value ?: return
            when (c.uuid) {
                TX -> onBytes(v)
                AUDIO -> listener.onAudio(v)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) main.post { log("Write failed ($status)") }
            synchronized(writeQueue) { writeBusy = false }
            pumpWrites()
        }
    }

    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD) ?: return
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, enable)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = enable
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    // ---------------------------------------------------------------- framing

    private fun onBytes(data: ByteArray) {
        val lines = mutableListOf<String>()
        synchronized(rxBuf) {
            rxBuf.append(String(data, Charsets.UTF_8))
            var nl = rxBuf.indexOf("\n")
            while (nl >= 0) {
                val line = rxBuf.substring(0, nl).trim()
                rxBuf.delete(0, nl + 1)
                if (line.isNotEmpty()) lines += line
                nl = rxBuf.indexOf("\n")
            }
        }
        if (lines.isNotEmpty()) main.post { lines.forEach(listener::onLine) }
    }

    /** Queues one line (a JSON object) for the board. Safe to call from any thread. */
    fun sendLine(line: String): Boolean {
        if (state != State.CONNECTED || rxChar == null) return false
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val chunk = (mtu - 3).coerceIn(20, 244)
        synchronized(writeQueue) {
            var i = 0
            while (i < bytes.size) {
                writeQueue.add(Op(false, bytes.copyOfRange(i, minOf(i + chunk, bytes.size))))
                i += chunk
            }
        }
        pumpWrites()
        return true
    }

    /** Queues one speaker packet (write without response). False if too much is already queued. */
    fun sendAudio(packet: ByteArray): Boolean {
        if (state != State.CONNECTED || audioChar == null) return false
        synchronized(writeQueue) {
            if (writeQueue.count { it.audio } >= 8) return false
            writeQueue.add(Op(true, packet))
        }
        pumpWrites()
        return true
    }

    private fun pumpWrites() {
        val g = gatt ?: return
        val next: Op
        synchronized(writeQueue) {
            if (writeBusy) return
            next = writeQueue.poll() ?: return
            writeBusy = true
        }
        val c = (if (next.audio) audioChar else rxChar) ?: run {
            synchronized(writeQueue) { writeBusy = false }
            return
        }
        val type = if (next.audio) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, next.data, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = type
            @Suppress("DEPRECATION")
            c.value = next.data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (!started) {
            // Stack busy: retry shortly.
            synchronized(writeQueue) {
                writeQueue.addFirst(next)
                writeBusy = false
            }
            main.postDelayed({ pumpWrites() }, 30)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun setState(s: State) {
        state = s
        listener.onStateChanged(s, deviceName)
    }

    private fun log(text: String) = listener.onLog(text)
}

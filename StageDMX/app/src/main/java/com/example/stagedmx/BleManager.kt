package com.example.stagedmx

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.ArrayDeque

/**
 * BLE 中心设备管理：扫描 -> 连接 -> 协商 MTU -> 发现服务 -> 写入/通知。
 * 所有 GATT 写操作串行化（BLE 一次只允许一个未完成操作）。
 * 权限（BLUETOOTH_SCAN/CONNECT 或旧权限）由 Activity 负责申请，这里假定已授予。
 */
@SuppressLint("MissingPermission")
class BleManager(private val ctx: Context) {

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, DISCONNECTED }

    data class Found(val device: BluetoothDevice, val name: String, val rssi: Int)

    interface Listener {
        fun onScanResult(found: Found) {}
        fun onStateChanged(state: State, info: String?) {}
        fun onNotify(data: ByteArray) {}
    }

    var listener: Listener? = null
    var state: State = State.IDLE
        private set

    private val main = Handler(Looper.getMainLooper())
    private val btManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = btManager.adapter

    private val scanner get() = adapter?.bluetoothLeScanner
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var deviceAddress: String? = null
    private var connectedDevice: BluetoothDevice? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    // 写队列
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private var writeFailCount = 0          // 连续提交失败计数
    private val writeFailMax = 3            // 超过则丢弃，避免死循环

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    /** 当前已连接设备的 MAC 地址（未连接返回 null）。 */
    fun connectedAddress(): String? = if (state == State.CONNECTED) deviceAddress else null

    /** 当前已连接的设备对象（未连接返回 null）。 */
    fun connectedDevice(): BluetoothDevice? = if (state == State.CONNECTED) connectedDevice else null

    private fun setState(s: State, info: String? = null) {
        state = s
        main.post { listener?.onStateChanged(s, info) }
    }

    // ---------------- 扫描 ----------------
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = result.scanRecord?.deviceName ?: dev.name ?: "(未命名)"
            main.post { listener?.onScanResult(Found(dev, name, result.rssi)) }
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            setState(State.IDLE, "扫描失败 code=$errorCode")
        }
    }

    /** 只扫描广播了我们服务 UUID 的设备；filterByService=false 则扫全部。 */
    fun startScan(filterByService: Boolean = true, timeoutMs: Long = 12000) {
        val s = scanner ?: run { setState(State.IDLE, "蓝牙不可用"); return }
        if (scanning) return
        val filters = if (filterByService)
            listOf(ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(DmxProtocol.SERVICE_UUID)).build())
        else emptyList()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanning = true
        setState(State.SCANNING)
        s.startScan(filters, settings, scanCallback)
        main.postDelayed({ stopScan() }, timeoutMs)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        if (state == State.SCANNING) setState(State.IDLE)
    }

    // ---------------- 周期扫描（扫描→暂停→再扫描，列表持续更新） ----------------
    private var periodicEnabled = false
    private var periodicDurationMs = 5000L
    private var periodicPauseMs = 3000L
    private val periodicScanRunnable = object : Runnable {
        override fun run() {
            if (!periodicEnabled) return
            if (!scanning) {
                startScan(filterByService = true, timeoutMs = periodicDurationMs)
            }
            main.postDelayed(this, periodicDurationMs + periodicPauseMs)
        }
    }

    /** 周期扫描：每轮扫 [durationMs]，暂停 [pauseMs] 后自动继续，直到 stopPeriodicScan()。 */
    fun startPeriodicScan(durationMs: Long = 5000, pauseMs: Long = 3000) {
        periodicEnabled = true
        periodicDurationMs = durationMs
        periodicPauseMs = pauseMs
        main.removeCallbacks(periodicScanRunnable)
        main.post(periodicScanRunnable)
    }

    fun stopPeriodicScan() {
        periodicEnabled = false
        main.removeCallbacks(periodicScanRunnable)
        stopScan()
    }

    // ---------------- 连接 ----------------
    fun connect(device: BluetoothDevice) {
        stopPeriodicScan()   // 连接时停止周期扫描，避免干扰 GATT 连接
        stopScan()
        deviceAddress = device.address
        connectedDevice = device
        setState(State.CONNECTING, device.address)
        close()
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(ctx, false, gattCallback)
    }

    /** 直接通过 MAC 地址连接（无需扫描），用于自动重连。 */
    fun connectByAddress(address: String): Boolean {
        val dev = adapter?.getRemoteDevice(address) ?: return false
        connect(dev)
        return true
    }

    fun disconnect() {
        try { gatt?.disconnect() } catch (_: Exception) {}
    }

    fun close() {
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        writeChar = null
        writeQueue.clear()
        writeInFlight = false
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // 先协商更大 MTU，回调里再发现服务
                if (!g.requestMtu(517)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevice = null
                writeChar = null
                writeQueue.clear()
                writeInFlight = false
                setState(State.DISCONNECTED, "status=$status")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setState(State.DISCONNECTED, "服务发现失败 $status"); return
            }
            val svc = g.getService(DmxProtocol.SERVICE_UUID)
            if (svc == null) {
                setState(State.DISCONNECTED, "未找到 DMX 服务")
                disconnect(); return
            }
            writeChar = svc.getCharacteristic(DmxProtocol.CHAR_WRITE_UUID)
            val notifyChar = svc.getCharacteristic(DmxProtocol.CHAR_NOTIFY_UUID)
            if (notifyChar != null) enableNotify(g, notifyChar)
            if (writeChar == null) {
                setState(State.DISCONNECTED, "未找到写特征")
                disconnect(); return
            }
            setState(State.CONNECTED, deviceAddress)
        }

        private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(DmxProtocol.CCCD_UUID) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            writeInFlight = false
            pump()
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { listener?.onNotify(value) }
        }

        // API <33
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = ch.value ?: return
            main.post { listener?.onNotify(v) }
        }
    }

    // ---------------- 写（串行队列） ----------------
    /** 排队发送一帧；drop=true 时若队列积压则丢弃旧帧（实时滑条场景）。 */
    fun send(frame: ByteArray, coalesceRealtime: Boolean = false) {
        val g = gatt ?: return
        if (writeChar == null) return
        synchronized(writeQueue) {
            if (coalesceRealtime && writeQueue.size > 6) {
                // 实时数据积压：清掉旧的，保留最新，避免延迟越滚越大
                writeQueue.clear()
            }
            writeQueue.addLast(frame)
        }
        pump()
    }

    private fun pump() {
        val g = gatt ?: return
        val ch = writeChar ?: return
        synchronized(writeQueue) {
            if (writeInFlight) return
            val frame = writeQueue.pollFirst() ?: return
            writeInFlight = true
            val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, frame, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = type
                    ch.value = frame
                    g.writeCharacteristic(ch)
                }
            }
            if (!ok) {
                // 提交失败：有限重试，超限丢弃避免死循环
                writeInFlight = false
                if (++writeFailCount >= writeFailMax) {
                    writeFailCount = 0
                    writeQueue.clear()
                    return
                }
                writeQueue.addFirst(frame)
                main.postDelayed({ pump() }, 15)
            } else {
                writeFailCount = 0
            }
        }
    }
}

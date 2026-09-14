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
    // ⚠ 这几个字段会被 GATT binder 线程写、主线程读，必须 @Volatile，
    //   否则主线程可能永远读到旧的 null（表现为"连上了但一帧都发不出去"）。
    @Volatile var state: State = State.IDLE
        private set
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var connectedDevice: BluetoothDevice? = null
    @Volatile private var deviceAddress: String? = null

    private val main = Handler(Looper.getMainLooper())
    private val btManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = btManager.adapter

    private val scanner get() = adapter?.bluetoothLeScanner
    @Volatile private var scanning = false

    // ---- 写队列 ----
    // 链路层优先级：Control = 可被更新的同类帧取代（丢弃永远以"整帧"为单位）；
    //               Reliable = 绝不丢弃（文件传输 / 程序上传等有状态序列）。
    enum class FrameKind { Control, Reliable }

    private class Queued(val data: ByteArray, val kind: FrameKind)

    /** ⚠ writeQueue / writeInFlight / writeFailCount 的一切访问都必须在 writeLock 内。 */
    private val writeLock = Any()
    private val writeQueue = ArrayDeque<Queued>()
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
        val old = gatt
        gatt = null
        writeChar = null
        synchronized(writeLock) {
            writeQueue.clear()
            writeInFlight = false
            writeFailCount = 0
        }
        // ⚠ 不能在 synchronized 块里调 gatt.close()：回调可能在别的线程进来。
        try { old?.close() } catch (_: Exception) {}
    }

    /** 断开/关闭时清空共享连接状态。 */
    private fun clearLinkState() {
        connectedDevice = null
        close()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        // ⚠ 所有回调第一件事：确认这个 g 是否仍是"当前连接"。
        //   重连时 connect() 会 close() 旧的 GATT 再 connectGatt()，而旧 GATT 的
        //   滞后回调仍会到达。若不校验，旧连接的 DISCONNECTED 会把刚建立的新连接
        //   清空（writeChar=null / queue.clear / setState(DISCONNECTED)）→
        //   表现为"重连后能用但一帧都不输出"。
        private fun isStale(g: BluetoothGatt): Boolean {
            if (g === gatt) return false
            try { g.close() } catch (_: Exception) {}
            return true
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (isStale(g)) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // 先协商更大 MTU，回调里再发现服务
                if (!g.requestMtu(517)) g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clearLinkState()
                setState(State.DISCONNECTED, "status=$status")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (isStale(g)) return
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (isStale(g)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setState(State.DISCONNECTED, "服务发现失败 $status"); return
            }
            val svc = g.getService(DmxProtocol.SERVICE_UUID)
            if (svc == null) {
                setState(State.DISCONNECTED, "未找到 DMX 服务")
                disconnect(); return
            }
            val wc = svc.getCharacteristic(DmxProtocol.CHAR_WRITE_UUID)
            val notifyChar = svc.getCharacteristic(DmxProtocol.CHAR_NOTIFY_UUID)
            if (notifyChar != null) enableNotify(g, notifyChar)
            if (wc == null) {
                setState(State.DISCONNECTED, "未找到写特征")
                disconnect(); return
            }
            writeChar = wc
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
            if (isStale(g)) return
            synchronized(writeLock) { writeInFlight = false }
            pump()
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (isStale(g)) return
            main.post { listener?.onNotify(value) }
        }

        // API <33
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (isStale(g)) return
            @Suppress("DEPRECATION")
            val v = ch.value ?: return
            main.post { listener?.onNotify(v) }
        }
    }

    // ---------------- 写（串行队列） ----------------
    /**
     * 排队发送一帧。
     *
     * @param kind Control 表示这一帧是"绝对状态覆盖"（如 0x01 通道段设置），当队列积压时
     *             可以被更新的同类帧取代 —— 丢弃**以整帧为单位**。
     *             Reliable 用于文件传输/程序上传这类有状态序列，永不丢弃。
     *
     * ⚠ 历史 bug：旧实现按"帧"做时延合并（队列 >6 就 clear），而 App 一次整帧刷新会拆成
     *   多个 ≤255 字节的分块，于是清队列会把整帧刷新的前半段丢掉、只留最后一块 →
     *   固件收到半帧，舞台灯跳到错值。现在合并整帧：被取代的那些 setRange 块全部丢掉，
     *   但**最新一次刷新的所有块完整保留**。
     */
    fun send(frame: ByteArray, kind: FrameKind = FrameKind.Reliable) {
        if (gatt == null || writeChar == null) return
        synchronized(writeLock) {
            if (kind == FrameKind.Control) {
                // 去掉排队中所有尚未发出的 Control 帧（它们已被本次状态取代）
                val it = writeQueue.iterator()
                while (it.hasNext()) if (it.next().kind == FrameKind.Control) it.remove()
            }
            writeQueue.addLast(Queued(frame, kind))
        }
        pump()
    }

    private fun pump() {
        val ch = writeChar ?: return
        synchronized(writeLock) {
            if (writeInFlight) return
            val g = gatt ?: return
            val item = writeQueue.pollFirst() ?: return
            writeInFlight = true
            val type = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val ok = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(ch, item.data, type) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        ch.writeType = type
                        ch.value = item.data
                        g.writeCharacteristic(ch)
                    }
                }
            } catch (_: Exception) { false }
            if (!ok) {
                // 提交失败：有限重试，超限丢弃避免死循环
                writeInFlight = false
                if (++writeFailCount >= writeFailMax) {
                    writeFailCount = 0
                    writeQueue.clear()
                    return
                }
                writeQueue.addFirst(item)
                main.postDelayed({ pump() }, 15)
            } else {
                writeFailCount = 0
            }
        }
    }
}

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
import android.os.SystemClock
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
        /**
         * 帧**没能发出去**（未连接 / 连续写失败导致传输中止）。
         *
         * ⚠ 加这个回调是因为以前这两种情况都是**静默**的：未连接时 `send()` 直接
         *   return，写失败超限时清空整个队列 —— 用户点了"写入地址""上传"看不出
         *   任何异常，只能靠"怎么没反应"去猜。Reliable 帧（用户主动发起的动作、
         *   文件分块）尤其不能安静地丢。
         */
        fun onSendStalled(reason: String) {}
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

    /**
     * "连接中"看门狗：连上后若 N 秒还没进 CONNECTED，就再推一次服务发现。
     *
     * 为什么需要：BLE 状态机依赖一系列回调（连接 → 服务发现 → 使能通知），
     * 任何一个回调丢失都会让 `state` 永久停在 CONNECTING —— 界面上看起来"已连接"，
     * 但所有操作都报"请先连接设备"。实测某些 ROM 确实会丢回调。
     */
    private var connectWatchdog: Runnable? = null
    @Volatile private var retriedDiscover = false
    /**
     * 看门狗自己的重试计数。
     *
     * ⚠ 必须和 [retriedDiscover] 分开：那个是 `onServicesDiscovered` 失败时
     *   "再发现一次"的额度（:289）。以前看门狗也去写同一个标志，于是
     *   **看门狗先超时就把这份额度吃掉了** —— 服务发现回调随后带着失败状态到达时，
     *   本该发生的重试被跳过，直接断开。
     */
    private var watchdogRetries = 0
    private val connectTimeoutMs = 6000L
    /** 看门狗最多额外重试几次服务发现（超过就明确报错，不再死循环）。 */
    private val maxWatchdogRetries = 2

    private fun armConnectWatchdog(g: BluetoothGatt) {
        cancelConnectWatchdog()
        val r = Runnable {
            // 用 gatt 引用相等代替 isStale：看门狗在外层类里，拿不到内部对象的私有方法
            if (state == State.CONNECTING && gatt === g) {
                // ⚠ 上限。以前是"discoverServices() 成功就再排一个 6 秒"，**没有次数上限**：
                //   只要服务发现回调一直不来、而 discoverServices() 一直返回 true，
                //   就会每 6 秒重做一次，永远不停，而且从不让用户知道出了什么问题 ——
                //   界面永远"连接中"，所有操作都报"请先连接设备"。
                if (watchdogRetries >= maxWatchdogRetries) {
                    android.util.Log.w("BLE", "服务发现重试 $watchdogRetries 次仍无响应，放弃")
                    setState(State.DISCONNECTED, "连接超时：设备无响应，请重连")
                    return@Runnable
                }
                android.util.Log.w("BLE", "连接超时仍在 CONNECTING，第 ${watchdogRetries + 1} 次重试服务发现")
                if (!g.discoverServices()) {
                    setState(State.DISCONNECTED, "连接超时")
                } else {
                    watchdogRetries++         // 只加自己的计数，不碰 retriedDiscover
                    armConnectWatchdog(g)
                }
            }
        }
        connectWatchdog = r
        main.postDelayed(r, connectTimeoutMs)
    }

    private fun cancelConnectWatchdog() {
        connectWatchdog?.let { main.removeCallbacks(it) }
        connectWatchdog = null
    }
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
    /** 进入 CONNECTED 的时刻（elapsedRealtime），用于区分"刚连上的暖机失败"和真故障。 */
    @Volatile private var connectedAtMs = 0L

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    /** 当前已连接设备的 MAC 地址（未连接返回 null）。 */
    fun connectedAddress(): String? = if (state == State.CONNECTED) deviceAddress else null

    /** 当前已连接的设备对象（未连接返回 null）。 */
    fun connectedDevice(): BluetoothDevice? = if (state == State.CONNECTED) connectedDevice else null

    private fun setState(s: State, info: String? = null) {
        // 状态迁移全部打日志：这个状态机一旦卡住，"看起来已连接但什么都干不了"，
        // 只有把每次迁移和来源打出来才查得动。
        android.util.Log.d("BLE", "state ${state} → $s  (${info ?: ""})")
        if (s == State.CONNECTED) connectedAtMs = SystemClock.elapsedRealtime()
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
        // ⚠ 已连上时**拒绝**重复连接请求。
        //   重连定时器（MainActivity 收到 DISCONNECTED 后 2 秒触发）可能在连接
        //   已经成功之后才跑 —— connect() 第一件事是 setState(CONNECTING)，
        //   于是把刚置好的 CONNECTED 打回去。而底层的 GATT 链路一直是好的
        //   （板子日志能看到 connected + mtu + state sync 全部正常），
        //   结果就是"明明连着、所有操作却提示未连接"，极难排查。
        if (state == State.CONNECTED && gatt != null) {
            android.util.Log.w("BLE", "忽略重复连接请求（已 CONNECTED）: ${device.address}")
            return
        }
        stopPeriodicScan()   // 连接时停止周期扫描，避免干扰 GATT 连接
        stopScan()
        deviceAddress = device.address
        connectedDevice = device
        retriedDiscover = false
        watchdogRetries = 0        // 新一次连接，看门狗计数归零
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
                // ⚠ 不要把服务发现挂在 onMtuChanged 上！
                //   旧实现是 requestMtu() → 在 MTU 回调里才 discoverServices()。
                //   但有些 ROM（实测小米）协商 MTU 后**不回调 onMtuChanged**，
                //   于是状态永远停在 CONNECTING：所有功能都提示"请先连接设备"，
                //   而表面上"已经连上了"，极难排查。
                //   现在改成：**立即发现服务**，MTU 协商降级为尽力而为。
                android.util.Log.d("BLE", "已连接，开始发现服务")
                if (!g.discoverServices()) {
                    setState(State.DISCONNECTED, "服务发现启动失败")
                    return
                }
                g.requestMtu(517)      // 拿不到大 MTU 也能用（分块发送会自动适配）
                armConnectWatchdog(g)  // 兜底：卡住就重试一次
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cancelConnectWatchdog()
                clearLinkState()
                setState(State.DISCONNECTED, "status=$status")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (isStale(g)) return
            android.util.Log.d("BLE", "MTU=$mtu status=$status")
            // 只记录，不触发服务发现（服务发现已在连接回调里启动）
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (isStale(g)) return
            cancelConnectWatchdog()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                android.util.Log.w("BLE", "服务发现失败 status=$status，重试一次")
                // 有些 ROM 第一次服务发现会返回失败，重试一次通常就好
                if (!retriedDiscover && g.discoverServices()) {
                    retriedDiscover = true
                    return
                }
                setState(State.DISCONNECTED, "服务发现失败 $status"); return
            }
            android.util.Log.d("BLE", "服务发现成功")
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
        if (gatt == null || writeChar == null) {
            // ⚠ Control 是高频状态流（推子/效果，30Hz），未连接时静默丢弃是合理的，
            //   否则每秒几十条提示。Reliable 是用户主动发起的动作（写入地址/扫描/
            //   上传），必须让用户知道它根本没发出去。
            if (kind == FrameKind.Reliable) listener?.onSendStalled("未连接设备，指令未发出")
            return
        }
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
                    // ⚠ **只丢 Control**。Control 是"绝对状态覆盖"，被更新的同类帧取代
                    //   没有损失；Reliable 是**有状态序列**（文件分块、程序上传），
                    //   丢一块就废掉整个传输 —— 上面 FrameKind 的约定也写着"绝不丢弃"。
                    //   以前这里无条件 writeQueue.clear()，把两者一起清了，而且不报错：
                    //   用户看到"上传失败"却不知道是自己链路的问题，程序上传更是会
                    //   静默写进一个缺步的程序。
                    var reliableAtRisk = (item.kind == FrameKind.Reliable)
                    val it = writeQueue.iterator()
                    while (it.hasNext()) {
                        val q = it.next()
                        if (q.kind == FrameKind.Control) it.remove() else reliableAtRisk = true
                    }
                    if (reliableAtRisk) {
                        // 别装作没事：中止这次传输并明确告诉上层。
                        // ⚠ 但刚连上的头一秒半里报这个会变成假警报 —— 服务发现还没
                        //   跑完时前几次写失败是**已知正常**现象（MainActivity 那边
                        //   专门为此补发了一次 0x05），而且状态同步本身有 2.5s 兜底。
                        //   那种情况安静重试即可，别让用户去查一个不存在的问题。
                        writeQueue.removeAll { it.kind == FrameKind.Reliable }
                        val warmup = SystemClock.elapsedRealtime() - connectedAtMs < 1500
                        if (!warmup) {
                            listener?.onSendStalled(
                                "连续 $writeFailMax 次发送失败，传输已中止（链路不稳，请靠近设备后重试）")
                        }
                    }
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

# StageDMX BLE 协议规范 v1

App（BLE 中心 / Central）⟶ ESP32-S3（BLE 外围 / Peripheral）⟶ SP3485 ⟶ DMX512 灯具

ESP32 端固件必须实现下面完全一致的 UUID 与帧格式，App 才能识别并通信。

---

## 1. GATT 结构

| 角色 | UUID (16-bit) | 完整 128-bit | 属性 |
|------|---------------|--------------|------|
| Service | `0xFF00` | `0000ff00-0000-1000-8000-00805f9b34fb` | — |
| 写特征 Write | `0xFF01` | `0000ff01-0000-1000-8000-00805f9b34fb` | Write / **Write Without Response** |
| 通知特征 Notify | `0xFF02` | `0000ff02-0000-1000-8000-00805f9b34fb` | Notify（可选） |

- **广播（Advertising）中必须带上 Service UUID `0xFF00`**，否则 App 的“按服务过滤扫描”看不到它（用户仍可点“扫描全部”兜底）。
- 建议广播里带一个易识别的设备名，如 `StageDMX-01` / `StageDMX-02`（两套测试设备用不同名字/编号）。
- App 连接后会请求 **MTU 517**，固件应接受更大的 MTU 以便一帧多通道。

---

## 2. 指令帧（App → ESP32，写入 `0xFF01`）

所有多字节整数为**大端**。通道号对外 1..512。

### 2.1 设置连续通道段 `0x01`（最常用）
```
字节:  0     1        2        3       4 ... 4+count-1
内容: 0x01  startHi  startLo  count   v0 v1 ... v(count-1)
```
- `start = (startHi<<8) | startLo`，1-based 起始通道号（1..512）
- `count`：本段通道数，1..255
- `v*`：对应通道值 0..255
- 语义：`DMX[start + i] = v_i`（i=0..count-1）

App 行为：
- 拖动滑条时，以 ~30Hz 把“变动过的最小连续区间”打包成一或多帧发出（每帧 count≤255）。
- 连接成功后会整帧下发一次全部 512 通道（分块，每块 255）以同步初值。

### 2.2 全黑 `0x02`
```
0x02
```
所有 512 通道置 0。

### 2.3 全亮 `0x03`
```
0x03
```
所有 512 通道置 255。

### 2.4 心跳 `0x04`（预留，可忽略）
```
0x04
```

### 2.5 板载程序 — 清空 `0x10`
```
0x10  prog_id
```
清空指定程序（0..3）的所有步骤。

### 2.6 板载程序 — 存一步（稀疏）`0x12`
```
0x12  prog_id  timeHi  timeLo  count  (chHi chLo val) * count
```
- `prog_id`：0..3
- `time = (timeHi<<8)|timeLo`：本步持续时间毫秒（20..65535）
- `count`：本步记录的通道项数，≤64
- 每项：`ch = (chHi<<8)|chLo`（1-based 通道号），`val` 0..255
- **稀疏语义：只记录动过的通道**。播放时只覆盖这些通道，其余保持当前值（推杆/场景），多个程序各管各的通道互不冲突。
- App 录制时对比“当前帧”与“上一步”仅发送变化的通道。

### 2.7 板载程序 — 播放 `0x13`
```
0x13  prog_id  flags
```
- `flags` bit0=1 循环播放；bit0=0 播完停。

### 2.8 板载程序 — 停止 `0x14` / 全部停止 `0x15`
```
0x14  prog_id
0x15
```

### 2.9 效果 — 配置并启动 `0x20`（板载离线运行，v3 支持 fine + 16bit 幅度 + 速度）
```
0x20  slot  fx_id
     panHi panLo  panFHi panFLo    tiltHi tiltLo  tiltFHi tiltFLo
     dimHi dimLo  dimFHi dimFLo    rHi rLo  gHi gLo  bHi bLo
     ampHi ampLo  speedHi speedLo
```
- `slot`：效果槽 0..7（同槽会覆盖，绑定实例）
- `fx_id`：1=圆形摇动 2=水平摇动 3=垂直摇动 4=频闪 5=RGB变色
- 通道字段（1-based，0=未用）每通道 2 字节大端：`pan/panF/tilt/tiltF/dim/dimF/r/g/b`，`*F` 为 fine 通道（0=无 fine）
- `amp`：**16bit 幅度偏移 0..65535**（= 灯的全行程）。App 按灯具实际行程把“角度”换算成该值，不同行程的灯同一角度物理效果一致
- `speed`：**速度 0..65535，越大越快**（8.8 定点：`speed/256` = 每 10ms tick 推进的 1/256 相位步数）。等效周期 = `655360 / speed` ms，如 3277 ≈ 200ms/圈、33 ≈ 19.9s/圈
- **效果是独立层**：读当前通道值作基底做正弦偏移，与程序播放/推杆不冲突；**BLE 断开后板子继续跑**（离线运行）
- fine 通道存在时板端做 16bit 精细运动（coarse<<8|fine），无台阶

### 2.10 效果 — 停止 `0x21` / 全部停止 `0x22`
```
0x21  slot
0x22
```

> 未知首字节的帧，固件应安全忽略。

### 2.11 文件管理（子目录支持：所有命令带 `dirLen dir…`，dir 相对 /fw，空 = 根目录）
```
0x31  dirLen dir… nameLen name… sizeHi sizeLo   上传开始（回 0x91）
0x34  [dirLen dir…]                              列出目录内容（无参数 = 根目录，回 0x92）
0x35  dirLen dir… nameLen name…                  下载文件（回 0x93/0x94）
0x36  dirLen dir… nameLen name…                  删除文件（回 0x95）
0x37  dirLen dir… nameLen name…                  新建文件夹（回 0x96）
0x38  dirLen dir… nameLen name…                  删除空文件夹（回 0x96）
0x39  dirLen dir… oldLen old… newLen new…        重命名（回 0x96）
0x3A  dirLen dir… nameLen name… dstDirLen dstDir…  移动条目到文件夹（回 0x96；dstDir 空=根，目标须已存在）
0x3B  dirLen dir… nameLen name… dstDirLen dstDir…  复制条目到文件夹（回 0x96；文件夹递归复制）
0x3C  （无参数）                                  全量目录树（回 0x97 多帧 + 0x98 结束帧）
```
- `dir`：允许多级（如 `a/b`），不含前导/结尾 `/`、不含 `..` 与 `\`
- `name`：单级条目名，不含 `/` `\` `..`
- 目录深度/路径长度上限 255 字节（协议 `dirLen` 单字节）

---

## 3. 通知帧（ESP32 → App，来自 `0xFF02`，可选）

```
0x81  statusByte              （statusByte bit0 = DMX 正在输出）
0x91  status                   上传结果（0=成功 1=失败）
0x92  count [nameLen name type sizeHi sizeLo]×count   文件列表（type 1=文件夹 0=文件）
0x93  seq totalChunks dataLen data…   下载数据块
0x94  status                   下载结束（0=成功 1=未找到）
0x95  status                   删除文件结果（0=成功 1=失败）
0x96  status                   文件夹操作/移动/复制结果（0=成功 1=失败）
0x97  count [dirLen dir…]×count   全量目录（多帧累加）
0x98  （结束帧）                目录收集完成
```

---

## 4. ESP32 端实现要点（DMX512 输出）

DMX512 电气/时序（SP3485 作为 RS485 驱动，DE/RE 拉高发送）：

- 波特率 **250000**，8 数据位，**2 停止位**，无校验（8N2）
- 每帧：**Break（≥88µs 拉低）→ MAB（≥8µs 拉高）→ Start Code(0x00) → 512 个通道字节**
- 刷新率：约 **44Hz**（不超过），建议固件用一个定时任务持续把当前 512 字节缓冲循环发出，BLE 收到指令只更新缓冲，不直接触发发送——这样输出稳定、不闪。

推荐用 ESP-IDF 的 **RMT** 或 **UART + 手动 Break**：
- UART 方案：把 TX 引脚临时切成 GPIO 拉低产生 Break，再切回 UART 发 Start Code + 512 字节。ESP-IDF 有 `uart_set_line_inverse` / 直接 `gpio` 控制的常见写法。
- SP3485：`DE`/`RE` 接一个 GPIO，常态拉高保持发送使能（纯发送场景可以直接常高）。

BLE 栈：ESP32-S3 用 **NimBLE**（`esp_nimble` / `bluedroid` 均可，NimBLE 更省资源）。

---

## 5. 两套测试设备

- 设备 A 广播名：`StageDMX-01`
- 设备 B 广播名：`StageDMX-02`
- App 扫描列表按名字 + RSSI 区分，点哪个连哪个。（v1 单连接；多设备同时控制是后续迭代。）

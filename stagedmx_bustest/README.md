# StageDMX 灯具模拟器（stagedmx_sniff）

把第二块 ESP32-S3 当成**一台真的 DMX 灯具**，接在控台（`stagedmx_std`）的 UART 线上，
在没有真实灯具的情况下做**端到端联合调试**：

| 能力 | 说明 |
|---|---|
| **收帧** | 像灯具一样按「起始地址 + 占用通道数」解析自己那一段 DMX，把通道值打出来 —— 验证 App 的推子 / 效果 / 程序有没有正确落到总线上 |
| **RDM 应答** | 作为 RDM Responder 被控台发现，支持 GET `DEVICE_INFO` / 各种 `LABEL` / `DMX_START_ADDRESS` / `DMX_PERSONALITY`，支持 SET `DMX_START_ADDRESS`（改址）与 SET `IDENTIFY_DEVICE`（识别） |
| **总线统计** | 真实帧率 / start code / 错帧数 —— 与旧分析仪一致，排查「帧率虚高」「灯具复位乱动」时最该看的三个数 |

> 本工程原来是纯旁听的**分析仪**（`sniff.c`，TX/RTS 都传 `-1`）。
> 现在由 `main/fixture_sim.c` 取代，是它的超集：多出「说」的能力，能应答 RDM。
> 想让回纯旁听：把 `SIM_RDM_ENABLE` 改成 `0` 重新编译。

---

## 接线（TTL 直连，不用收发器）

```
   控台 stagedmx_std              本板 stagedmx_sniff
   U1 TX  GPIO17  ─────────────→  GPIO2   (SIM_RX_PIN)
   U1 RX  GPIO18  ←─────────────  GPIO1   (SIM_TX_PIN)
   GND            ──────────────  GND      ← 必须共地
```

- 两边都是 **TTL 电平**，中间没有收发器 ⇒ **不需要方向脚**，`SIM_RTS_PIN = -1`。
- ⚠ 控台 U1 的 17/18 同时也接着它自己的 SP3485（DI / RO）。发送时 RO 是高阻（无影响）；
  但控台切到 RDM 接收态时 RO 会驱动 GPIO18，与本板 GPIO1 形成**推挽争用**。
  ESP32 GPIO 驱动（~20Ω）明显强于 SP3485 的 RO（~100Ω+），实测能压过去，但请知悉：
  - 若 RDM 应答不稳定，优先怀疑这里；
  - 最干净的做法是给本板也配一片 SP3485，走真正的 A/B 差分总线（那时把 `SIM_RTS_PIN` 指到 EN 脚，驱动会自动换向）。
- ⚠ 不要用 GPIO0 / GPIO3 / GPIO45 / GPIO46 / GPIO19 / GPIO20（strapping 或原生 USB）。

---

## 编译

```powershell
# 1) 进 ESP-IDF 环境
. D:\Espressif\frameworks\esp-idf-v5.5.2\export.ps1

# 2) 直接编译即可（不需要额外设环境变量）
cd stagedmx_sniff
idf.py build
```

### 曾经踩过的坑：中文 Windows 下 `idf.py build` 报 GBK 解码错误（已修复）

```
UnicodeDecodeError: 'gbk' codec can't decode byte 0xa8 in position 64
CMake Error at tools/cmake/kconfig.cmake:230: Failed to run kconfgen
```

原因：`sdkconfig.defaults` 里有 **UTF-8 中文注释**，而 ESP-IDF 的
`kconfgen/core.py` 用 `open(path, "r")` **不指定编码**，中文 Windows 下按 GBK 解码就崩。
（在 WSL / Linux 下 Python 默认 UTF-8，所以一直没暴露。）

**已修复**：`stagedmx_sniff/sdkconfig.defaults` 与 `stagedmx_std/sdkconfig.defaults`
的注释都改成了纯 ASCII，现在 `idf.py build` 直接可用，**不需要** `PYTHONUTF8=1`。

> 说明：只有 `sdkconfig.defaults` 会被 kconfgen 用 Python 读，所以只有它必须纯 ASCII。
> 源码里的中文注释（`.c` / `.h` / `.kt`）不受影响，可以继续用中文。
> `sdkconfig`（idf.py 生成的派生文件）本身一直是纯 ASCII，没这个问题。

---

## 烧录

本板控制台在**原生 USB 口**（USB-Serial/JTAG），不是 UART0。
所以看日志要插原生 USB 口 —— 两块板同时插着时会有**两个「USB 串行设备」**，
分不清就拔掉一块看哪个消失，或者用设备管理器看端口号。

```powershell
cd stagedmx_sniff
idf.py -p COM<x> flash monitor
```

---

## 联调步骤

### 0. 控台的「RDM 模拟模式」已移除

控台固件里曾经有一个默认打开的"模拟模式"：`rdm_scan()` 不碰总线、直接返回
固件内置的虚拟灯具。它的害处是**真灯在场也扫不到**，排查时极易误判成
"总线/接线有问题"，而且让"扫描成功"这件事变得不可信。

现在**已整体移除**（固件 `rdm.c` / `ble_dmx.c` + App 的开关与解析）。
所以：控台的每一次 RDM 扫描都是**真实总线扫描**，扫不到就是真扫不到。
用本工程（真实 RDM 协议、真实应答）做联调即可，**不再需要任何开关**。

### 1. 确认模拟灯起来了

本板串口应出现：

```
I (xxx) fixture: === StageDMX 灯具模拟器 ===
I (xxx) fixture: 接线: 控台 U1 TX(GPIO17) → 本板 GPIO2 | 控台 U1 RX(GPIO18) ← 本板 GPIO1 | GND↔GND
I (xxx) fixture: TTL 直连模式：无方向脚（不用收发器）
I (xxx) fixture: 灯就绪：UID=05E0:xxxxxxxx  地址=1  占用=16  模式=StageDMX Sim 16ch
I (xxx) fixture: 等控台发 DMX…（App 里点『RDM 扫描』应当能发现本灯）
```

`UID` 是芯片 MAC 派生的，**每块板不同**，这就是这盏「灯」的身份。

### 2. 验证 DMX 收帧

控台开机后应持续输出 DMX。本板每秒打一行：

```
I (12345) fixture: [灯] 41 fps | sc=00 size=513 | 地址=1..16 | 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0（全黑） | 非零=0 错帧=0 sc错=0 | RDM req=0 set=0 | TTL直连
```

- `41 fps` = 真实帧率。**这个数是旁路实测的**，控台自己上报的 fps 不可信
  （它统计的是「循环了几次」，发送失败时反而更高）。
- 在 App 推子页把 1..16 通道拉起来 → 这一行的通道值应实时跟着变。
- 全零时会打「（全黑）」而不是一长串 0，便于一眼看出有没有信号。

### 3. 验证 RDM 发现 + 读参数

App 里点「扫描设备」。本板串口应连续出现：

```
I (xxx) fixture: [RDM] DEVICE_INFO  cc=0x20 pid=0x0060  <- 05E0:xxxxxxxx
I (xxx) fixture: [RDM] DEVICE_LABEL  cc=0x20 pid=0x0082  <- 05E0:xxxxxxxx
...
```

App 里应出现 **1 台**设备，制造商 `StageDMX`，型号 `StageDMX Sim 16ch`，
占用 **16** 通道，地址 **1**。

> `cc=0x20` 是 GET，`0x30` 是 SET，`0x10` 是发现（`DISC_UNIQUE_BRANCH`）。
> 第一行通常是发现阶段的 `pid=0x0000`。

### 4. 验证改址

App 里把这台设备地址改成比如 17。本板应打：

```
W (xxx) fixture: [RDM] ★ 控台改址：起始地址 17（占用 16 通道 → 17..32）
```

并且状态行立刻变成 `地址=17..32` —— 说明模拟灯**真的按新地址重新解析了 DMX**，
不是只回了个 RDM 应答糊弄过去。

地址存在 NVS 里，**重启不丢**（真实灯具的行为）。想复位成出厂地址 1：
`idf.py erase-flash` 擦掉 NVS 再烧。

### 5. 验证识别（IDENTIFY）

App 里点「识别」。本板应打：

```
W (xxx) fixture: [RDM] ★ IDENTIFY ON（我该闪了）
```

状态行会多出 `IDENTIFY!` 前缀。若给 `SIM_LED_PIN` 填了引脚号，接的 LED 会快闪。

---

## 可调参数（`main/fixture_sim.c` 顶部）

| 宏 | 默认 | 说明 |
|---|---|---|
| `SIM_TX_PIN` / `SIM_RX_PIN` | 1 / 2 | 接控台的 TX / RX |
| `SIM_RTS_PIN` | -1 | TTL 直连不用；改差分时指到 EN 脚 |
| `SIM_RDM_ENABLE` | 1 | 0 = 退回纯旁听（旧分析仪行为） |
| `SIM_FOOTPRINT` | 16 | 模拟灯的占用通道数（决定 DEVICE_INFO 的 footprint） |
| `SIM_START_ADDR` | 1 | 出厂默认地址（仅 NVS 为空时生效） |
| `SIM_LED_PIN` | -1 | 识别时闪烁的 LED；-1 = 只打日志 |
| `SIM_REPORT_MS` | 1000 | 状态行间隔 |

---

## 故障排查

| 现象 | 先查这里 |
|---|---|
| 本板一直打「无 DMX 信号」 | 接线 / **共地**；控台是否真的在发（控台串口看 `U1 ... fps`） |
| App 扫描不到设备 | RDM 模式下 RO 与本板 TX 争用（见接线警告）；或控台侧的地址/UID 过滤 |
| App 能发现但读不到参数 | 看本板日志有没有对应 `[RDM] <PID>` 行 —— 有行说明请求到了、应答没回去，即争用问题 |
| `sc错` 一直涨 | 帧 start code 不是 0x00，控台帧结构有问题 |
| `错帧` 一直涨 | 接收错误（framing / 溢出），多半是波特率或线上干扰 |
| RDM 应答时好时坏 | 典型的总线争用 —— 改用差分收发器，或断开控台 RO↔GPIO18 那根线 |

### 串口一个字节都没有 / ROM 停在 `waiting for download`

```
rst:0x15 (USB_UART_CHIP_RESET),boot:0x0 (DOWNLOAD(USB/UART0))
waiting for download
```

芯片**每一次 USB-JTAG 复位都进了下载模式**，根本没跑你的固件。
（`boot:0x0` 才是关键，正常启动应为 `boot:0x8 (SPI_FAST_FLASH_BOOT)`。）

**解法：用看门狗复位把芯片捞出来**（实测一次就好，之后普通复位也正常了）：

```python
from esptool.targets.esp32s3 import ESP32S3ROM
esp = ESP32S3ROM("COM9", 115200)
esp.connect("default_reset")
esp.write_reg(esp.RTC_CNTL_OPTION1_REG, 0, esp.RTC_CNTL_FORCE_DOWNLOAD_BOOT_MASK)
esp.watchdog_reset()      # 芯片内部 RTC 复位，不经过 USB-JTAG，因此不会被要求进下载
```

原理见 `esptool/targets/esp32s3.py:365-387`：ESP32S3ROM 的 `hard_reset()`
会先清 `RTC_CNTL_FORCE_DOWNLOAD_BOOT` 位，再走看门狗复位 —— 这是
[arduino-esp32#6762](https://github.com/espressif/arduino-esp32/issues/6762) 的 workaround。

> ⚠ **别用基类 `esptool.ESPLoader`**：它没有这个覆盖，`hard_reset()` 只有
> `HardReset`，在这块板上一定落回下载模式。必须用 `ESP32S3ROM`。

> ⚠ 看门狗复位会让 **USB 重新枚举**，旧串口句柄立刻失效
> （`ClearCommError failed (PermissionError(13))`）。要关掉端口、等 2~3 秒再重开。

**已走过的死路（别再试了）**：

| 尝试 | 结果 |
|---|---|
| `HardReset(uses_usb=True)`（基类） | 仍进下载 |
| `USBJTAGSerialReset` 原样 | 仍进下载 |
| 各种 DTR/RTS 组合（4 种） | 仍进下载 |
| `--after soft_reset` | esptool 报 `only supported on ESP8266` |
| 读 `GPIO_STRAP_REG` 判断 GPIO0 | **没用** —— 好板和坏板都读出 `0x00000000`，USB-JTAG 复位后该寄存器不反映真实 strapping |
| 直接读 `GPIO_IN_REG`(0x6000403C) | 两块板 GPIO0 **都是 HIGH**，不是 strapping 问题 |

> ⚠ Windows 上 `serial.Serial(port)` **先 open 再设 DTR/RTS** 会触发一次复位。
> esptool 的做法是先 `rts=False, dtr=False` 再 `open()`（`loader.py:334-340`）。
> 自己写串口读取脚本时要照做，否则每次打开端口都会把板子踢回下载模式。

### 固件在跑，但一直报「无 DMX 信号」

先看括号里的计数器，这一行已经把病因分好了：

| 读数 | 含义 |
|---|---|
| `收帧=0 错帧=0`，`GPIO2(RX): 跳变=0 高=0%` | **线恒定低** → 接错到 GND / 接了个被拉低的脚 |
| `收帧=0 错帧=0`，`跳变=0 高=100%` | **线恒定高** → 接对了但控台没在发（或接到了恒高的脚） |
| `收帧=0 错帧=0`，`跳变` 很大 | 有信号在动 → 问题在**波特率/帧格式**，不在接线 |
| `错帧` 里 `帧格式` 在涨 | 有信号但解不出帧：波特率不对、电平不像样、线上干扰 |
| `错帧` 里 `溢出` 在涨 | 信号太密/太脏，UART 收不过来（多为引脚悬空拾噪） |
| `错帧` 里 `槽不足` 在涨 | 真收到 DMX 帧了，但比 513 短 |

> ⚠ **这条日志同时采样 TX 引脚作参照，务必一起看**：
> `GPIO1(TX参照): 高=100%` 说明采样方法可信（UART 空闲就是 mark=高）；
> 若参照也读成 0%，那是采样方法失效（引脚被外设占用导致 `gpio_get_level()`
> 恒返回 0），此时 RX 的读数无意义，**别误判成"线接地了"**。

**判据的逻辑**：UART 线空闲时是高电平，发数据时在高电平上叠加跳变。
所以一条正常的 TX 线**无论空闲还是忙，都不可能是"恒 0% 高"**。
一旦 RX 读到 `跳变=0 高=0%`，就能确定它没接在对方的 TX 上，而是被拉到了地。

对照控台自己的遥测确认它确实在发：

```
I (5688) dmx: U1 frm=208 fps=41 ok=1 fail=0     ← 控台在发，41fps 无失败
```

排查顺序：

1. **接线点对不对**：要接控台 **UART 的 TTL 侧**，即 ESP32 的 **GPIO17 那个焊盘/排针**，
   **不是** SP3485 的 A/B 差分线，也不是模块的 RO（接收输出）。本板接 GPIO2。
2. **是不是接成了 GND 或 TX↔TX**：这是实测踩到的 —— `高=0%` 就是接地的典型特征。
   控台 **GPIO17** 才是要接的那根；GPIO18 是接收脚。
3. **共地**：两块板 GND 必须相连（但别把信号线也接到 GND 上）。
4. 线是否松动 / 杜邦线内部断线（换一根试）。

> **接线自检的土办法**：把本板 GPIO2 的线从控台拔下来悬空，
> 再看这一行 —— 悬空时读数会变成乱跳（`跳变` 很大、高电平百分比不固定）；
> 若拔下来后仍是 `跳变=0 高=0%`，那说明读的不是这根线。


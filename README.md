# ESPDMX · StageDMX — 手机控台 + ESP32-S3，DMX512 舞台灯控制

![build](https://github.com/zww546/ESPDMX/actions/workflows/build-release.yml/badge.svg)

手机 App 通过 **BLE** 把通道值发给 **ESP32-S3 控台**，控台转成标准 **DMX512**（250k / 8N2 + Break/MAB，**双宇宙 1024 通道**）驱动舞台灯；
另有一块 ESP32-S3 可以**扮成一台真灯**（含 RDM 应答），在没有真灯的情况下做端到端联调。

```
  ┌──────────────┐    BLE (0xFF00)    ┌───────────────────┐    DMX512 (250k 8N2)    ┌──────────┐
  │ StageDMX App │ ─────────────────▶ │ ESP32-S3 + SP3485 │ ─────────────────────▶ │ DMX 灯具 │
  └──────────────┘                    └───────────────────┘                        └──────────┘
                                             ▲ UART TTL 直连（不用收发器）
                                   ┌───────────────────┐
                                   │ stagedmx_sniff    │  灯库模拟器：收帧 / RDM 应答 / 统计
                                   └───────────────────┘
```

## 三个部件

| 目录 | 是什么 | 技术栈 |
|---|---|---|
| `StageDMX/` | 手机控台 App：推子 / 效果 / 程序 / 场景 / 灯库 / **切割** / **RDM** | Kotlin · AGP 8.11.1 · Kotlin 2.1.0 · Gradle 8.13 · compileSdk 36 / minSdk 26 · v1.26 (code 27) |
| `stagedmx_std/` | 控台固件：双宇宙 DMX512 输出 + BLE 协议 + 效果引擎 + USB 盘 + RDM 主站 | ESP-IDF **v5.5.2** · C · 内置 `components/esp_dmx` |
| `stagedmx_sniff/` | 灯库模拟器：把自己当一台真灯接在控台后面，**收帧 + RDM 应答 + 统计** | ESP-IDF · C |
| `release_assets/` | 预编译产物：`stagedmx.bin` / `bootloader.bin` / `partition-table.bin` / `StageDMX-debug.apk` + 一键发布脚本 | — |

> **为什么要模拟器**：真灯数量、型号、地址都不可控。有了它，"App 的推子 / 效果 / 程序到底有没有正确落到总线上"这件事变成可回归的 ——
> 它是从早期纯旁听分析仪演进过来的超集（想要回纯旁听：把 `SIM_RDM_ENABLE` 改成 `0` 重编）。

---

## 快速开始

```powershell
# 1) 控台固件（ESP-IDF 环境里）
cd stagedmx_std
idf.py set-target esp32s3 && idf.py build
python tools\flash_esp32.py -p COM13            # 自动找串口/固件；--check 可顺带做验收核对

# 2) App（Android Studio 打开 StageDMX/，或命令行）
cd ..\StageDMX
.\gradlew.bat assembleDebug                      # 产物：app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat testDebugUnitTest                  # 233 个纯逻辑单测

# 3) 手机连 BLE 设备 "StageDMX-01"（Android 8.0+；BLE 需真机，模拟器没蓝牙）
```

不接真灯也能跑通全链路：把 `stagedmx_sniff` 烧到第二块板，按它自己的 README 接线（TTL 直连、不用收发器），
控台就会"看到"一台会应答的灯。

---

## 功能

### App（Android）

**通道与推子**
- BLE 扫描 / 连接：按 DMX 服务（`0xFF00`）过滤，扫描全部兜底，断线自动重连、已连接设备置顶
- 512 / 1024 通道滑条，实时 0–255；**推子页排列方式**（按通道顺序 / 自定义顺序 + ↑↓ 调整，可存命名预设）
- **主控亮度**（总控推子）：只缩放各实例的**调光(DIM)** 通道，带百分比与断电记忆
- 全黑 / 定位 / Flash / 记录

**状态同步（`0x05`）**：连接后先拉整机状态（1024 通道 + 运行中的效果 + 正在播放的程序），
用单片机 uptime 区分"App 重连"与"单片机重启"，两种方向各自恢复（老固件不支持时自动退回整帧下发）。

**场景 / 程序 / 效果**
- 场景：保存 / 调用 / 删除（本地持久化 1024 通道快照）、编组、多实例分组、跟随延时
- 程序走灯（Chase）：多步 + 节拍
- **效果引擎 FX 1–11 + 13**：圆摆 / Pan 摆 / Tilt 摆 / 频闪 / RGB 循环 / 放大摆 / 调焦摆 / 色盘摆 / 图案盘摆 / 图案自转 / 摇动 / **切割循环**
- **阵列效果**：一个槽同时驱动整排灯（最多 64 台，`first + i×stride`，不需要地址表）
  - **相位扩散 0–360°** → 波浪 / 跑马 / 对称张开；波形 6 种、方向 3 种、包络 5 种
  - 同一实例可**叠加多个效果**（共 8 槽），通道冲突会被拦截；效果预设一键存/取
  - ⚠ 阵列要求组内**同灯型 + 起始地址等间距**，不满足会拒绝并提示

**灯库**
- 支持 3 种格式：MA2 XML（`.xml`）、Avolites Titan `.d4`、Avolites Pearl `.R20`
- 内置灯库编辑器（导出 ZIP）；3 个标签页：App 灯库 / 设备灯库（U 盘）/ 文件管理
- 上传 / 下载 / 删除 / 重命名 / 新建文件夹
- 解析细节见 `StageDMX/FIXTURE_CHANNELS.md`（含 16bit fine 段补通道、D4 多单元通道号还原）

**切割（Framing Shutter）可视化** —— 详见下一节
**RDM 设备页** —— 扫描 / 读参数 / 改地址 / 识别，见"RDM"一节

**设置**：中 / English 全局切换、跟随延时、推子页排列、切割调整方式（三选一）、切割最大角度、切割映射与逐片自检、灯库编辑入口

### 固件（ESP32-S3）

- **双宇宙 DMX512**：`ch 1..512 = 宇宙1（UART1/GPIO17）`、`ch 513..1024 = 宇宙2（UART2/GPIO18）`，
  每口一个输出任务**并行**发送（各自 41–43fps，互不影响），两片 SP3485 共用 DE（GPIO2）
- **渲染管线**：core 1 上一个 10ms 任务 `snapshot → 程序层(HTP) → 效果层(阵列) → commit`，
  临界区从"每通道一次"降到"每 tick 两次"，用版本号做乐观并发，避免 commit 覆盖刚到的推子值
- **BLE GATT**：服务 `0xFF00` / 写 `0xFF01`（WRITE + WRITE_NO_RSP）/ 通知 `0xFF02`；设备名 `StageDMX-01`
- **状态同步**：`0x05` 请求 → `0x82`（uptime / 运行效果数 / 播放程序位图）+ `0x83` 通道分块（5 帧）+ `0x84` 效果参数 + `0x85` 结束
- **效果引擎**：256 点 SIN 表 + 8.8 定点相位累加，与 App 的 FX 1–11、13 一一对应（效果帧 57 字节 + 8 字节阵列 = 65）
- **内置程序**：chase 等（`0x10–0x15`）
- **USB MSC（U 盘模式）**：2MB SPI Flash 挂 FAT，直插电脑管灯库文件；枚举为 PID `4002`
- **文件传输**：上传 / 列表 / 下载 / 删除 / 建目录 / 删目录 / 重命名 / 移动 / 复制 / 目录树（`0x31–0x3C`，响应 `0x91–0x98`）
- **RDM 主站**：见下

### RDM（ANSI E1.20）

| 能力 | 说明 |
|---|---|
| 扫描 | `rdm_scan(universe)`：发现 + 逐台读参数；设备表放 **PSRAM**，每宇宙最多 32 台 |
| 读参数 | DEVICE_INFO / MANUFACTURER_LABEL / DEVICE_MODEL_DESCRIPTION / SOFTWARE_VERSION_LABEL / DEVICE_LABEL / DMX_PERSONALITY_DESCRIPTION |
| 改地址 | SET `DMX_START_ADDRESS`：单台，以及**批量**（App 里可按分组算好再一次性下发） |
| 识别 | SET `IDENTIFY_DEVICE`（让灯闪，现场对号） |

⚠ **RDM 和 DMX 共用同一条 A/B 线，且是双向的**：扫描期间该宇宙的 DMX 输出会**暂停**（灯保持最后一帧），跑完自动恢复。
批量改址只暂停**一次**（单台循环会 N 次 churn，现场会看到闪断）。

---

## 切割（Framing Shutter）：三视图 + 实灯标定

**同一份通道值的三个视图**，全局开关切换，切换不改任何值：

| 模式 | 长什么样 | 适合 |
|---|---|---|
| 0 · 8 条推杆 | 切割片通道照旧当普通推子 | 精确看数值、批量推 |
| 1 · 内嵌面板 | 推子页里就地展开：圆形光斑预览 + 8 条滑块 + 旋转 | 边看光斑边调 |
| 2 · 画布 | 预览图**可直接拖线段 / 拖角**，只保留旋转滑块 | 手感调节 |

- 每台灯可配**映射**（每片对应窗口哪条边、要不要反向），以及**最大角度**（灯库写了 `SHAPER ROT` 的物理量程就以灯库为准，如 Ares `−45..+45`）
- **逐片自检**：一片一片压下去（档位 `SELF_TEST_AMOUNT = 0.1`，约遮 9%，**不会把灯打黑**），看灯上哪条边在动就知道映射对不对

### A/B 是同一片刀片的两个端点 —— 实灯标定

灯库里的 `BLADE1A` / `BLADE1B` 不是"偏移 + 角度"，而是**同一片刀片的两端**（两端各自进出，合成平移 + 倾斜）。
下面这张表是**在真灯上量的**（不是推的）：

| A 端 | B 端 | 遮住光源 |
|---|---|---|
| 0.00 | 0.00 | 0%（全开） |
| **1.00** | 0.00 | **≈ 80%** |
| 1.00 | 0.50 | 92.6% |
| 1.00 | 0.75 | 97.4% |
| 1.00 | 0.90 | 99.3%（**还没满**） |
| **1.00** | **1.00** | **100%（刚好切满，不超出）** |

据此标定（`ShaperGeometry`）：

```
深度 u = K1·(a+b) − K2·a·b      K1 = 0.745（由"单端满 = 80%"反解）
                                 K2 = 2·K1 − 1 = 0.49（由"另一端拉满才刚好切满"逼出）
倾斜    = (b − a) × 45°         B 端更深 = 正
```

这套形式有三个必要性质，且都由单测锁住：

1. `u(1,1) = 1` **恰好**（线正好压在对面边沿）—— **不超出**；
2. `u = 1` 只有 (1,1) 一个解 —— **不提前闭死**，另一端不拉满就切不满；
3. `u ∈ [0,1]` 恒成立 —— 任何合法通道组合都不会把刀片推出框外。

> **教训**：曾经用"两端各沿自己那条导轨滑到对面**角**"来推几何 ——
> 那条路在行程 = 口径时**单端拉满恒为 50%**（线正好过圆心），永远到不了 80%；
> 把行程放大能凑出 80%，但 (1, 0.5) 就已经闭死。**两条实测量在几何族里无法同时满足**，
> 所以深度改成按实测标定，而不再假装是几何推出来的。

---

## 硬件

- **控台**：ESP32-S3（如 DevKitC-1 **N16R8**）+ SP3485（RS-485 半双工）×2
- **模拟器**：第二块 ESP32-S3（不需要收发器，TTL 直连）

控台接线（`stagedmx_std/main/pins.h`，**v1.26 起两片 SP3485EN 均接回程**）：

| SP3485EN | 接 ESP32-S3 | 说明 |
|---|---|---|
| VCC / GND | 3.3V / GND | 供电共地 |
| DI | GPIO17（宇宙1）/ GPIO16（宇宙2） | ESP 输出 → 芯片 DI |
| RO | GPIO18（宇宙1）/ GPIO15（宇宙2） | 芯片 RO → ESP 输入（RDM 依赖它） |
| DE | GPIO4（宇宙1）/ GPIO5（宇宙2） | 方向脚；**建议把 `/RE` 与 `DE` 短接** |
| A / B / G | DMX 总线 | XLR 母头 pin3 / pin2 / 地 |

- 末端设备 A/B 之间并联 **120Ω** 终端电阻
- GPIO 避让（N16R8）：26–32 = SPI flash、33–37 = Octal PSRAM、19/20 = 原生 USB、43/44 = UART0（留控制台）
- 模拟器接线（TTL 直连，`SIM_RTS_PIN = -1`）：控台 U1 TX `GPIO17` → 模拟器 `GPIO2`，控台 U1 RX ← 模拟器 `GPIO1`，**必须共地**

---

## 目录结构

```
ESPDMX/
├── StageDMX/                    # Android App
│   ├── app/src/main/java/com/example/stagedmx/
│   │   ├── MainActivity.kt      # UI 编排 / 权限 / 连接 / 页面切换（切割与 RDM 的粘合层）
│   │   ├── BleManager.kt        # 扫描 / 连接 / MTU / 写队列
│   │   ├── DmxEngine.kt         # 通道状态 + 节流批量下发
│   │   ├── DmxProtocol.kt       # GATT UUID + 帧编码（与固件共同约定）
│   │   ├── FxEngine.kt          # 效果引擎 / 阵列 / 相位
│   │   ├── FixtureParser.kt     # 灯库解析（MA2 XML / D4 / R20）
│   │   ├── FixtureStore.kt      # 灯库与实例存储 / 导入导出
│   │   ├── FixtureEditor.kt     # 灯库编辑器
│   │   ├── ChannelGroups.kt     # 通道 → 功能组（纯逻辑，可单测）
│   │   ├── ChannelRows.kt       # 推子页行模型（纯逻辑）
│   │   ├── ChannelAdapter.kt    # 行模型 → RecyclerView
│   │   ├── ShaperGeometry.kt    # 切割几何（纯逻辑：四片四边形 / 拖拽 / 覆盖率）
│   │   ├── ShaperStore.kt       # 切割映射与设置持久化
│   │   ├── ShaperWindowView.kt  # 自绘光斑（拖线段 / 拖角）
│   │   ├── RdmStore.kt / RdmRows.kt / RdmDrag.kt   # RDM 设备表 / 行模型 / 拖拽改址（纯逻辑）
│   │   ├── StepStore.kt         # 场景 / 程序持久化
│   │   └── ...                  # BleManager · DmxProtocol · DeviceMessages · FxPresetStore
│   │                            # · InstanceForm · Lang(i18n) · ZipReader · ZipScan
│   ├── app/src/test/            # 233 个纯逻辑单测（14 个测试类）
│   └── *.md                     # PROTOCOL / UI_REVIEW / FIXTURE_CHANNELS / EFFECT_MODEL_V2_DESIGN
├── stagedmx_std/                # 控台固件（ESP-IDF）
│   ├── main/
│   │   ├── app_main.c           # 入口 / 任务编排
│   │   ├── ble_dmx.c            # BLE GATT + 协议解析
│   │   ├── dmx.c / dmx_state.c  # DMX512 帧输出 / 通道状态
│   │   ├── render.c             # 10ms 渲染管线
│   │   ├── fx.c / fx_proto.c    # 效果引擎 / 效果帧编解码
│   │   ├── program.c            # 内置程序（chase 等）
│   │   ├── rdm.c / rdm.h        # RDM 主站（扫描 / 改址 / 识别）
│   │   ├── file_xfer.c          # 文件上传下载 / 目录管理
│   │   ├── usb_msc.c            # USB 盘模式（SPI Flash + FAT）
│   │   └── pins.h               # GPIO 定义
│   ├── components/esp_dmx/      # 随仓库内置的 DMX/RDM 组件
│   ├── test_host/               # 主机端测试（gcc 直接编译 fx/program/proto，不需要硬件）
│   ├── tools/                   # flash_esp32.py / i18n 工具 / ui_tap / ui_node / read_log …（Python）
│   └── partitions.csv · sdkconfig.defaults
├── stagedmx_sniff/              # 灯库模拟器（第二块 ESP32-S3）
└── release_assets/              # 预编译 bin / APK + publish_release.ps1
```

---

## 编译与烧录

### 固件（控台）

依赖 **ESP-IDF v5.5.x**（本仓库用 v5.5.2 验证）。

```powershell
cd stagedmx_std
idf.py set-target esp32s3
idf.py build
python tools\flash_esp32.py                 # 自动探测串口并烧录
python tools\flash_esp32.py -p COM13        # 指定串口
python tools\flash_esp32.py --check 20      # 烧录 + 自动核对验收项
python tools\flash_esp32.py --list          # 只看串口
```

手动 esptool（COM13 / 16MB Flash 为例）：

```powershell
esptool.py --chip esp32s3 -p COM13 -b 460800 write_flash `
  --flash_mode dio --flash_size 16MB --flash_freq 80m `
  0x0 bootloader.bin 0x8000 partition-table.bin 0x10000 stagedmx.bin
```

> **`storage` 分区**（`0x310000`，2MB）是灯库文件系统。FAT / WL 配置变更时需要擦：
> `esptool.py --chip esp32s3 -p COM13 erase_region 0x310000 0x200000`

### App

```powershell
cd StageDMX
.\gradlew.bat assembleDebug          # 需要 JDK 17（JAVA_HOME 指过去）
.\gradlew.bat installDebug           # 连真机直接装
```
或 Android Studio 打开 `StageDMX/`，Gradle Sync 后运行（**BLE 必须真机**）。

### 模拟器

见 `stagedmx_sniff/README.md`（接线 + `idf.py build` + `SIM_RDM_ENABLE`）。

---

## 测试

| 层 | 命令 | 规模 |
|---|---|---|
| App 纯逻辑单测 | `cd StageDMX && .\gradlew.bat testDebugUnitTest` | **233 例 / 14 个测试类**（JUnit，无 Robolectric） |
| 固件主机端测试 | `cd stagedmx_std && bash test_host/run.sh` | 4 个（fx / render / proto / patch，gcc 直接编真实源码） |
| 真机联调 | 控台 + `stagedmx_sniff`（或真灯） | 见各 README 的验收清单 |

App 侧刻意把**容易错的纯逻辑**从 Android 类型里拆出来（`ChannelGroups` / `ChannelRows` / `RdmRows` / `RdmDrag` / `RdmStore` /
`ShaperGeometry` / `ShaperStore` / `FxEngine` 通道解析 / ZIP 与 GBK 文件名），所以不用 Robolectric 也能覆盖；
改这些模块时请**先写失败测试再改**（本仓库的规矩：每条修过的 bug 都留一条能复现它的测试）。

---

## 踩过的坑（改之前先看）

- **DMX 中断必须在 core 1 安装**：`esp_intr_alloc()` 把中断路由到"调用它的核"。曾经中断被射频排队 → 驱动卡在
  `DMX_STATUS_SENDING`、`dmx_send()` 永远返回 0、DMX 停而 BLE 还活着，只能重启才恢复。
  `CONFIG_DMX_ISR_IN_IRAM=y` + 钉核是**互补**的两件事（前者保证 cache 停顿时能执行，后者保证不被射频排队），别只留一个。
- **绝不在 DMX 输出任务里 printf**：控制台在 **USB-Serial/JTAG**（原生 USB 口），主机不读会阻塞。遥测一律走独立低优先级任务。
- **看日志要插原生 USB 口**：CH343 那个口只用于烧录。
- **U 盘模式会占串口**：插 USB 后设备枚举为 MSC（PID `4002`），退出（BLE 发 `0xA0 0x30 0`）或断电后串口才回来。
- **通道号全局 1..1024**：跨宇宙统一寻址，`512` 与 `513` 是两个口，不要按 0..511 写。
- **两片 SP3485 的 DI / RO 不能接反**：两个都是输出，接反会互相对打（芯片发烫、无输出）；DE 每片一根（4 / 5），
  裸片的 `/RE` 建议与 `DE` 短接，否则接收常开 → 自己的回波 + 伪 break。引脚只改 `pins.h`，固件里没有硬编码 GPIO。
- **切割 A/B 的那两个常数是实测来的**：`ONE_END_INSET` / `BOTH_END_CROSS` 有专门的回归测试锁着，
  改之前先跑 `ShaperGeometryTest`（"双端拉满才刚好切满"）。
- **改固件协议时同步改 App**：帧格式两边的注释都指向 `StageDMX/PROTOCOL.md`，改一边等于制造幽灵 bug。

### 已知限制

- 灯库里切割片有两套命名：`BLADE1A/1B`（A/B 双端，面板按它解释）与 `blade_1/blade_1_rot`（"偏移+角度"语义）。
  遇到后者时面板只能认出一部分通道，需要先在灯库里补/改通道名。
- 阵列效果要求组内同灯型 + 起始地址等距（否则拒绝执行）。
- RDM 扫描期间该宇宙 DMX 暂停（灯保持最后一帧），现场需要避免在演出中扫描。

---

## 自动构建 / 发布

`.github/workflows/build-release.yml`：推 `v*` tag（或在 Actions 页手动触发）后

1. 编译 App（JDK 17 + Android SDK + Gradle 8.13 → `app-debug.apk`）
2. 编译固件（ESP-IDF v5.5.2 → `bootloader.bin` / `partition-table.bin` / `stagedmx.bin`）
3. 创建 / 更新 Release，把 APK 与固件 bin 作为附件上传（也可只下 Artifacts）

不想用 Actions：`release_assets/publish_release.ps1`（设 `GH_TOKEN` 后跑，走 REST API 上传本地已构建产物）。

---

## 通信协议摘要

BLE GATT：服务 `0xFF00` · 写 `0xFF01`（WRITE / WRITE_NO_RSP）· 通知 `0xFF02`。完整帧格式见 `StageDMX/PROTOCOL.md`。

| 命令 | 含义 |
|---|---|
| `0x01–0x03` | 通道区间设置 / 全黑 / 全亮（通道号 1..1024，跨宇宙统一寻址） |
| `0x04` | Ping |
| `0x05` | 请求整机状态 → 响应 `0x82–0x85`（uptime / 1024 通道 / 运行效果 / 播放程序） |
| `0x10–0x15` | 内置程序（chase 等） |
| `0x20–0x22` | 效果设置（**65 字节**：57 基础 + 8 阵列参数；FX 1–11 + 13）/ 停止 |
| `0x30` / `0xA0` | USB MSC U 盘模式切换 |
| `0x31–0x3C` | 文件上传 / 列表 / 下载 / 删除 / 建目录 / 删目录 / 重命名 / 移动 / 复制 / 目录树 |
| `0x91–0x98` | 对应响应帧 |
| — | RDM 走固件本地总线（UART RTS 自动换向），不占用 BLE 协议 |

---

## 文档索引

| 文档 | 内容 |
|---|---|
| `RELEASE_NOTES.md` | **当前版本的更新说明**（新增 / 修复 / 破坏性变更 / 升级注意 / 验收清单 —— 发布时作为 Release 正文） |
| `StageDMX/PROTOCOL.md` | BLE 帧格式 / 命令 / 响应（App 与固件共同约定） |
| `StageDMX/FIXTURE_CHANNELS.md` | 灯库解析清单（各格式实际读出来的通道，排查灯库问题的第一站） |
| `StageDMX/UI_REVIEW.md` | App UI 评审与改进状态 |
| `StageDMX/EFFECT_MODEL_V2_DESIGN.md` | 效果模型 v2 设计稿（阵列扩散 + 双宇宙） |
| `stagedmx_sniff/README.md` | 模拟器：接线、编译、RDM 应答能力 |
| `stagedmx_std/tools/` | 烧录脚本、i18n 工具链、`ui_tap.py` / `ui_node.py` / `read_log.py`（真机自动化与抓日志） |

---

## 许可

个人开源项目，仅供学习交流。文中出现的灯具品牌名（Avolites / MA2 / OMARTE 等）与灯库格式均为其各自所有者的商标或格式规范。

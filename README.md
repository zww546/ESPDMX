# ESPDMX — StageDMX 便携舞台灯 DMX 控制器

手机 App（Android）通过 **BLE** 连接 **ESP32-S3 + SP3485（RS-485）**，将 512 路通道数据实时输出为标准 **DMX512** 信号，驱动各类舞台灯具（帕灯 / 摇头灯 / 染色灯 / 图案灯等）。

```
┌─────────────┐   BLE    ┌──────────────────┐   DMX512 (250k, 8N2)   ┌──────────┐
│ Android App │ ───────▶ │ ESP32-S3 + SP3485 │ ─────────────────────▶ │ DMX 灯具 │
│  StageDMX   │ GATT     │  固件 stagedmx_std │   Break/MAB + 512ch    │  (总线)   │
└─────────────┘ 0xFF00   └──────────────────┘                        └──────────┘
```

- **App 侧**：`StageDMX/`（Kotlin）
- **固件侧**：`stagedmx_std/`（ESP-IDF C）
- **通信协议**：`PROTOCOL.md`（BLE 帧格式，App 与固件共同约定）

---

## 功能特性

### App（Android）
- **BLE 扫描 / 连接**：按 DMX 服务（0xFF00）过滤，支持扫描全部兜底；自动周期重扫、已连接设备置顶
- **512 通道控制**：滑条实时调光（0–255），可自定义显示通道数；全黑 / 全亮一键
- **状态同步（新增）**：连接后先用 `0x05` 拉取单片机整机状态（512 通道 + 运行中的效果 + 正在播放的程序），
  用单片机 uptime 区分两种重启：
  - **App 重启 / 重连** → 采纳单片机状态，推子数值、效果开关、程序播放按钮与单片机完全一致；
  - **单片机重启** → 本地状态回推给单片机，恢复断联前的输出。
  （老固件不支持 `0x05` 时自动退回“整帧下发”的旧行为）
- **主控亮度（总控推子）**：推子页顶部一条推子，0–100% 全局缩放**各实例的调光(DIM)通道**输出
  （修复：以前会把水平/垂直/图案/棱镜等所有通道一起缩放），带百分比显示与断电记忆
- **场景系统**：保存 / 调用 / 删除（本地持久化 512 通道快照），支持编组、多实例分组与跟随延迟
- **程序走灯（Chase）**：多步程序 + 节拍控制
- **效果引擎 FX（1–11 + 13）**：
  1. 圆形摆动（pan+tilt 圆）
  2. Pan 摆动 · 3. Tilt 摆动
  4. 频闪（方波）
  5. RGB 三色循环（120° 相位）
  6. 放大摆动（zoom）· 7. 调焦摆动（focus）
  8. 色盘摆动（color）· 9. 图案盘摆动（gobo）
  10. 图案盘自转（gobo_rot）· 11. 固定图案摇动（shake）
  13. **切割循环**（切割片 BLADE1A–4B 依次拉满→关闭、切割旋转、循环间隔；参数为每步时长 / 循环间隔）
  - **阵列效果（v6）**：一个效果槽可同时驱动**整排灯**（最多 64 台）—— 同型灯具按等距 patch，
    固件用 `first + i×stride` 推每台的通道，不需要地址表
  - **相位扩散**：`扩散 0–360°` 让每台相位依次错开 → **波浪 / 跑马 / 对称张开**（0° = 全排同步）
  - **波形**：正弦 / 三角 / 方波 / 脉冲 / 随机 / 锯齿；**方向**：正序 / 反序 / 往返；
    **阵列包络**：无 / 渐入 / 渐出 / 对称 / 两端强（奇偶交替用"扩散 180°"即得）
  - 幅度 0–255、速度 33–3277（可调），通道可任意指定
  - **多效果叠加**：同一实例可同时叠加多个效果（各自占用一个板载槽，共 8 槽），同实例通道冲突会被拦截
  - **效果预设**：一键保存当前实例上**所有**内置效果的数值与开关状态，
    之后一键应用（含一键全关）；预设默认名字 = 当前实例名
  - ⚠ **规则阵列前提**：效果作用于"整组"时，组内必须**同灯型**且起始地址**等间距**；
    不满足会拒绝并提示（例如 1/19/100 这种非等距 patch，或 18ch/20ch 混选）
- **灯库系统**：
  - 支持 **3 种灯库格式**：MA2 XML（`.xml`）、Avolites Titan `.d4`、AVOLITES Pearl `.R20`
  - **通道读取修复**：Titan `.d4` 按 `<Mode><Include ChannelOffset>` + `<Cells>`（多单元/子模式）还原真实通道号
    （旧版按 `<Control>` 顺序编号 → 通道号错位、多模式/多单元灯具漏读通道）；
    MA2 XML 会把 16bit 通道的 fine 段补成独立通道，推子页不再出现 “CH 15” 这种无名空洞
  - 内置灯库编辑器，可创建 / 编辑自定义灯型（通道语义：RGB / Pan / Tilt / Zoom / Focus / Color / Gobo…），
    入口在**设置页 → 灯库编辑**，编辑页可导出 ZIP
  - 灯库 3 个标签页：**App 灯库**（本地）/ **设备灯库**（ESP32 U 盘）/ **文件管理**
  - 上传 / 下载 / 删除 / 重命名 / 新建文件夹（3 种格式均可导入导出）
- **设置页（新增）**：
  - 中 / English 全局切换（不再每个灯库单独设置）
  - 跟随延时开关与延时时间（从实例管理页移入）
  - **推子页排列方式**：按通道顺序 / 自定义顺序；可自行用 ↑↓ 调整通道位置，
    并保存为命名预设（默认名为灯型名），之后一键切换
  - **灯库编辑**：入口从“灯具”页移入（灯具页原按钮改为“批量”选择）
- **界面**：多实例选择器、底部导航（推子 / 程序 / 效果 / 灯具 / 文件 / 设置）


### 固件（ESP32-S3）
- **DMX512 发送**：UART 250k 8N2 + Break/MAB，由 SP3485 转为 RS-485 差分信号；**双宇宙（2×512 通道）**
  - 全局通道寻址：`ch 1..512 = 宇宙1（UART1/GPIO17）`，`ch 513..1024 = 宇宙2（UART2/GPIO18）`
  - 每口一个输出任务**并行**发送，各自 41~43fps（互不影响）；两片 SP3485 共用 DE（GPIO2）
- **渲染管线（v6）**：一个 10ms 任务在 core 1 上 `snapshot → 程序层(HTP) → 效果层(阵列) → commit`，
  临界区从"每通道一次"降到"每 tick 两次"；用版本号做乐观并发，避免 commit 覆盖刚到的推子值
- **BLE GATT**：服务 `0xFF00`，写特征 `0xFF01`（WRITE / WRITE_NO_RSP），通知特征 `0xFF02`
- **状态同步（新增）**：`0x05` 请求 → 回 `0x82` 头（uptime / 运行效果数 / 播放程序位图）、
  `0x83` 1024 通道分块（5 帧）、`0x84` 每个运行中效果的参数、`0x85` 结束；由独立任务发送，不阻塞 NimBLE host
- **双核分工（关键）**：
  - **core 0**：BLE 控制器 / NimBLE / Wi-Fi 协议栈、文件传输、状态同步（射频主场）
  - **core 1**：渲染管线 + DMX 输出任务 + **DMX 中断**（UART/GPTimer）
  - ⚠ DMX 驱动**必须在 core 1 上安装**：`esp_intr_alloc()` 会把中断路由到"调用它的那个核"
    （`intr_alloc.c: cpu = esp_cpu_get_core_id()`）。启动日志会打印中断表供核对（`esp_intr_dump`）。
  - ISR 放 IRAM（`CONFIG_DMX_ISR_IN_IRAM=y`）与钉核是互补的两件事：前者保证 cache 停顿时仍能执行，
    后者保证不被射频排队。
  - 所有遥测/打印都放在独立的低优先级任务（core 0），**绝不在 DMX 输出任务里 printf**
    （控制台在 USB-Serial/JTAG 上，主机不读时会阻塞）
- **FX 效果引擎**：256 点 SIN 表 + 8.8 定点相位累加，与 App 端效果 1–11、13 一一对应；效果帧 57 字节（含 8 片切割片 + 切割旋转）
  - 切割循环(FX13)参数与 App 统一为 10ms tick：`speed = 每步时长/10ms`、`amp16 = 循环间隔/10ms`
- **内置程序**：chase 走灯等内置程序（协议 0x10–0x15）
- **USB MSC（U 盘模式）**：2MB SPI Flash 挂载为 FAT 文件系统，手机 / 电脑可直插当作 U 盘管理灯库文件（协议 0xA0 0x30 切换）
- **文件传输协议**：上传 / 下载 / 列表 / 删除 / 建目录 / 重命名（0x31–0x3C，响应 0x91–0x98）

---

## 硬件

- **主控**：ESP32-S3（标准开发板即可，如 ESP32-S3-DevKitC-1 N16R8）
- **收发器**：SP3485（RS-485 半双工）×2（每宇宙一片）
- **接线**（`stagedmx_std/main/pins.h`）：

| SP3485 | 接 ESP32-S3 | 说明 |
|---|---|---|
| VCC | 3.3V | 供电 |
| GND | GND | 共地 |
| TXD (DI) | GPIO17（宇宙1）/ GPIO18（宇宙2） | DMX 数据 |
| EN | GPIO2（两片共用） | 高电平 = 发送 |
| RXD (RO) | 悬空 | 纯发送端不接 |
| A | DMX 总线 A | XLR 母头 pin3 |
| B | DMX 总线 B | XLR 母头 pin2 |
| G | GND | 共地 |

> 末端设备 A/B 之间并联 120Ω 终端电阻。
> GPIO 避让（N16R8 模块）：26~32 = SPI flash，33~37 = Octal PSRAM，19/20 = 原生 USB，43/44 = UART0（留给控制台）。

---

## 目录结构

```
ESPDMX/
├── README.md               # 本文档
├── PROTOCOL.md             # BLE 通信协议（帧格式 / 命令 / 响应）
├── StageDMX/               # Android App（Kotlin）
│   └── app/src/main/java/com/example/stagedmx/
│       ├── MainActivity.kt     # UI 编排 / 权限 / 扫描连接 / 页面切换
│       ├── BleManager.kt       # BLE 扫描 / 连接 / MTU / 写队列
│       ├── DmxEngine.kt        # 512 通道状态 + 节流批量下发
│       ├── DmxProtocol.kt      # GATT UUID + 帧编码（与固件约定）
│       ├── FxEngine.kt         # 效果引擎（FX 1–11 预设 / 参数下发）
│       ├── FixtureParser.kt    # 灯库解析（MA2 XML / D4 / R20）
│       ├── FixtureStore.kt     # 灯库存储 / 导入导出 / 上传下载
│       ├── FixtureEditor.kt    # 灯库编辑器
│       ├── StepStore.kt        # 场景 / 程序持久化
│       ├── ChannelAdapter.kt   # 通道滑条列表 / 编组
│       └── ...
└── stagedmx_std/           # ESP32-S3 固件（ESP-IDF）
    ├── main/
    │   ├── app_main.c         # 入口 / 任务编排
    │   ├── ble_dmx.c          # BLE GATT + 协议解析（0x01–0x39）
    │   ├── dmx.c              # DMX512 帧输出（Break/MAB + 512ch）
    │   ├── dmx_state.c        # 512 通道状态缓冲
    │   ├── fx.c               # 效果引擎（SIN 表 / 定点相位）
    │   ├── file_xfer.c        # 文件上传下载 / 目录管理
    │   ├── usb_msc.c          # USB MSC U 盘模式（SPI Flash + FAT）
    │   ├── program.c          # 内置程序（chase 等）
    │   ├── pins.h             # GPIO 定义
    │   └── ...
    ├── partitions.csv         # 分区表（factory 3MB + storage 2MB FAT）
    └── sdkconfig.defaults     # 默认配置（FATFS LFN / MSC / WL…）
```

---

## 固件编译与烧录

依赖：**ESP-IDF v5.5.x**（本仓库使用 v5.5.2 验证）。

```powershell
# 1. 进入 ESP-IDF 环境（示例：Windows 下的 IDF 工具链）
#    设置 IDF_PATH / IDF_TOOLS_PATH 并激活 Python 虚拟环境后：

cd E:\Desktop\ESPDMX\stagedmx_std
idf.py set-target esp32s3
idf.py build
```

烧录（推荐用仓库自带的一键脚本，它会自动找串口/固件，并可选做验收核对）：

```powershell
python stagedmx_std\tools\flash_esp32.py                 # 自动探测串口并烧录
python stagedmx_std\tools\flash_esp32.py -p COM13        # 指定串口
python stagedmx_std\tools\flash_esp32.py --check 20      # 烧录 + 按验收项自动核对
python stagedmx_std\tools\flash_esp32.py --list          # 只看串口
```

或手动用 esptool（以 COM13 为例，16MB Flash 开发板）：

```powershell
esptool.py --chip esp32s3 -p COM13 -b 460800 write_flash `
  --flash_mode dio --flash_size 16MB --flash_freq 80m `
  0x0 build/bootloader/bootloader.bin `
  0x8000 build/partition_table/partition-table.bin `
  0x10000 build/stagedmx.bin
```

> **注意**：`storage` 分区（0x310000，2MB）存放灯库文件系统。当 FAT / WL 扇区配置变更时需擦除该分区：
>
> ```powershell
> esptool.py --chip esp32s3 -p COM13 erase_region 0x310000 0x200000
> ```

> **控制台位置**：本固件把日志配在 **USB-Serial/JTAG**（原生 USB 口，枚举为 `USB 串行设备`），
> 不在 UART0/烧录口上。所以"看日志"要插原生 USB 口；CH343 那个口只用于烧录。

> **U 盘模式提示**：插入 USB 后设备以 MSC U 盘（PID 4002）枚举，串口会被占用；退出 U 盘模式（BLE 发送 `0xA0 0x30 0`）或断电重启后串口恢复。

---

## App 编译运行

1. 用 Android Studio 打开 `StageDMX/`（AGP 8.11.1 / Kotlin 2.1.0 / Gradle 8.13 / compileSdk 36 / minSdk 26）
2. Gradle Sync 后编译安装到真机（BLE 需真机，模拟器无蓝牙）
3. 首次使用会申请蓝牙权限（Android 12+：`BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`）

---

## 自动构建 / 发布（GitHub Actions）

`.github/workflows/build-release.yml`：推 `v*` tag（或在 Actions 页手动 Run workflow）后自动

1. 编译 Android APK（JDK 17 + Android SDK + Gradle 8.13 → `assembleDebug`）
2. 编译 ESP32-S3 固件（ESP-IDF v5.5.2 → `bootloader.bin` / `partition-table.bin` / `stagedmx.bin`）
3. 创建 / 更新 GitHub Release，把 APK 与固件 bin 作为附件上传

产物也可在 Actions 运行的 Artifacts 里单独下载。**不想用 Actions 时**：
`release_assets/publish_release.ps1`（设 `GH_TOKEN` 后运行，走 REST API 上传本地已构建的产物）。

---

## 通信协议

BLE GATT：
- 服务：`0xFF00`
- 写特征：`0xFF01`（WRITE / WRITE_NO_RSP）
- 通知特征：`0xFF02`

命令一览（详见 `PROTOCOL.md`）：

| 命令 | 含义 |
|---|---|
| 0x01–0x03 | 通道区间设置 / 全黑 / 全亮（通道号 1..1024，跨宇宙统一寻址） |
| 0x04 | Ping |
| 0x05 | 请求整机状态（响应 0x82–0x85：uptime / 1024 通道 / 运行效果 / 播放程序） |
| 0x10–0x15 | 内置程序（chase 等） |
| 0x20–0x22 | 效果设置（**65 字节帧**：57 字节基础 + 8 字节阵列参数，FX 1–11 + 13 切割循环）/ 停止 |
| 0x30 / 0xA0 | USB MSC U 盘模式切换 |
| 0x31–0x3C | 文件上传 / 列表 / 下载 / 删除 / 建目录 / 删目录 / 重命名 / 移动 / 复制 / 目录树 |
| 0x91–0x98 | 对应响应帧 |

---

## 许可

本项目为个人开源项目，仅供学习交流使用。灯具品牌名（Avolites / MA2 等）与灯库格式均为其各自所有者的商标 / 格式规范。

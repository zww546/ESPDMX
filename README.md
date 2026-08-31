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
- **场景系统**：保存 / 调用 / 删除（本地持久化 512 通道快照），支持编组、多实例分组与跟随延迟
- **程序走灯（Chase）**：多步程序 + 节拍控制
- **效果引擎 FX（1–11）**：
  1. 圆形摆动（pan+tilt 圆）
  2. Pan 摆动 · 3. Tilt 摆动
  4. 频闪（方波）
  5. RGB 三色循环（120° 相位）
  6. 放大摆动（zoom）· 7. 调焦摆动（focus）
  8. 色盘摆动（color）· 9. 图案盘摆动（gobo）
  10. 图案盘自转（gobo_rot）· 11. 固定图案摇动（shake）
  - 幅度 0–255、速度 33–3277（可调），通道可任意指定
- **灯库系统**：
  - 支持 **3 种灯库格式**：MA2 XML（`.xml`）、Avolites Titan `.d4`、AVOLITES Pearl `.R20`
  - 内置灯库编辑器，可创建 / 编辑自定义灯型（通道语义：RGB / Pan / Tilt / Zoom / Focus / Color / Gobo…）
  - 灯库 3 个标签页：**App 灯库**（本地）/ **设备灯库**（ESP32 U 盘）/ **文件管理**
  - 上传 / 下载 / 删除 / 重命名 / 新建文件夹（3 种格式均可导入导出）
- **界面**：中 / English 一键切换；多实例选择器

### 固件（ESP32-S3）
- **DMX512 发送**：UART 250k 8N2 + Break/MAB，由 SP3485 转为 RS-485 差分信号；512 字节缓冲循环输出
- **BLE GATT**：服务 `0xFF00`，写特征 `0xFF01`（WRITE / WRITE_NO_RSP），通知特征 `0xFF02`
- **FX 效果引擎**：256 点 SIN 表 + 8.8 定点相位累加，与 App 端效果 1–11 一一对应
- **内置程序**：chase 走灯等内置程序（协议 0x10–0x15）
- **USB MSC（U 盘模式）**：2MB SPI Flash 挂载为 FAT 文件系统，手机 / 电脑可直插当作 U 盘管理灯库文件（协议 0xA0 0x30 切换）
- **文件传输协议**：上传 / 下载 / 列表 / 删除 / 建目录 / 重命名（0x31–0x39，响应 0x91–0x96）

---

## 硬件

- **主控**：ESP32-S3（标准开发板即可，如 ESP32-S3-DevKitC-1 N16R8）
- **收发器**：SP3485（RS-485 半双工）
- **接线**（`stagedmx_std/main/pins.h`）：

| SP3485 | 接 ESP32-S3 | 说明 |
|---|---|---|
| VCC | 3.3V | 供电 |
| GND | GND | 共地 |
| TXD (DI) | GPIO17 | DMX 数据 |
| EN | GPIO2 | 高电平 = 发送 |
| RXD (RO) | 悬空 | 纯发送端不接 |
| A | DMX 总线 A | XLR 母头 pin3 |
| B | DMX 总线 B | XLR 母头 pin2 |
| G | GND | 共地 |

> 末端设备 A/B 之间并联 120Ω 终端电阻。

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

烧录（以 COM13 为例，16MB Flash 开发板）：

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

> **U 盘模式提示**：插入 USB 后设备以 MSC U 盘（PID 4002）枚举，串口会被占用；退出 U 盘模式（BLE 发送 `0xA0 0x30 0`）或断电重启后串口恢复。

---

## App 编译运行

1. 用 Android Studio 打开 `StageDMX/`（AGP 8.11.1 / Kotlin 2.1.0 / Gradle 8.13 / compileSdk 36 / minSdk 26）
2. Gradle Sync 后编译安装到真机（BLE 需真机，模拟器无蓝牙）
3. 首次使用会申请蓝牙权限（Android 12+：`BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`）

---

## 通信协议

BLE GATT：
- 服务：`0xFF00`
- 写特征：`0xFF01`（WRITE / WRITE_NO_RSP）
- 通知特征：`0xFF02`

命令一览（详见 `PROTOCOL.md`）：

| 命令 | 含义 |
|---|---|
| 0x01–0x03 | 通道区间设置 / 全黑 / 全亮 |
| 0x04 | Ping |
| 0x10–0x15 | 内置程序（chase 等） |
| 0x20–0x22 | 效果设置（39 字节帧，FX 1–11）/ 停止 |
| 0x30 / 0xA0 | USB MSC U 盘模式切换 |
| 0x31–0x39 | 文件上传 / 列表 / 下载 / 删除 / 建目录 / 删目录 / 重命名 |
| 0x91–0x96 | 对应响应帧 |

---

## 许可

本项目为个人开源项目，仅供学习交流使用。灯具品牌名（Avolites / MA2 等）与灯库格式均为其各自所有者的商标 / 格式规范。

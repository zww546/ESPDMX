#pragma once
// ============ StageDMX 标准版 GPIO ============
// 适用: ESP32-S3-DevKitC-1 (N16R8) 等标准开发板
// 无屏幕、无按键、无电池检测 —— 纯 DMX 发送端
// v6: 双宇宙（2 个 UART 口）

// ---- 宇宙 1（UART1）----
#define DMX_TX_PIN     17     // ESP TXD → SP3485 DI (TXD)
#define DMX_DE_PIN     2      // ESP → SP3485 EN (高=发送)；两个口共用

// ---- 宇宙 2（UART2）----
#define DMX_TX2_PIN    18     // 第二片 SP3485 的 DI
// 引脚避让（N16R8 模块）：
//   GPIO26~32  → SPI flash
//   GPIO33~37  → Octal PSRAM
//   GPIO19/20  → 原生 USB (D-/D+)
//   GPIO43/44  → UART0（留给控制台兜底）

// SP3485 模块接线（每口一片）:
//   VCC → 3.3V
//   GND → GND
//   TXD (DI) → ESP GPIO17(宇宙1) / GPIO18(宇宙2)
//   EN       → ESP GPIO2（两片共用）
//   RXD (RO) → 悬空 (发送端不接)
//   A → DMX 总线 A (XLR 母头 pin3)
//   B → DMX 总线 B (XLR 母头 pin2)
//   G → GND (共地)
//   末端设备 A/B 间并 120Ω 终端电阻

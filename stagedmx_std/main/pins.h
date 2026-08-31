#pragma once
// ============ StageDMX 标准版 GPIO ============
// 适用: ESP32-S3-DevKitC-1 (N16R8) 等标准开发板
// 无屏幕、无按键、无电池检测 —— 纯 DMX 发送端

// DMX512 / SP3485
#define DMX_UART_PORT  UART_NUM_1
#define DMX_TX_PIN     17     // ESP TXD → SP3485 DI (TXD)
#define DMX_DE_PIN     2      // ESP → SP3485 EN (高=发送)

// SP3485 模块接线:
//   VCC → 3.3V
//   GND → GND
//   TXD (DI) → ESP GPIO17
//   EN       → ESP GPIO2
//   RXD (RO) → 悬空 (发送端不接)
//   A → DMX 总线 A (XLR 母头 pin3)
//   B → DMX 总线 B (XLR 母头 pin2)
//   G → GND (共地)
//   末端设备 A/B 间并 120Ω 终端电阻

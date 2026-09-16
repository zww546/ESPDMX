#pragma once
// ============ StageDMX 标准版 GPIO ============
// 适用: ESP32-S3-DevKitC-1 (N16R8) 等标准开发板
// 无屏幕、无按键、无电池检测
//
// v7: **双宇宙 + 双向(RS-485 半双工)**
//   每个宇宙一套完整的 4 线收发接口: DI / RO / DE / RE。
//   v6 及以前是"纯发送端": 只有 TX 和一根共用 EN，RX 悬空、EN 常驻拉高，
//   收发器永远处于发送态 —— 收不到任何东西。
//
// ⚠ RS-485 是**半双工**: 同一时刻只能有一端驱动总线。DE/RE 必须在发送与
//   接收之间切换，且切换要跟得住 break/MAB 的微秒级时序。
//
// ⚠ DE 与 RE 电平**相反**（SP3485: DE 高=驱动总线，RE 低=使能接收），
//   千万不要把两个脚接到同一个 GPIO。两者同时有效 = 一边驱动一边接收，
//   轻则收到自己的回波、重则总线上两个驱动源打架。
//   接法二选一，见 pins.h 末尾「DE/RE 接法」。

// ---- 宇宙 1（UART1）----
#define DMX_TX_PIN      17    // ESP TXD → 模块 TXD (DI)
#define DMX_RX_PIN      15    // ESP RXD ← 模块 RXD (RO)   (UART1 默认 RX)
#define DMX_EN_PIN      2     // ESP → 模块 EN（单方向脚）

// ---- 宇宙 2（UART2）----
// ⚠ ESP32-S3 的 UART2 **没有原生引脚**（soc/uart_pins.h: U2RXD/U2TXD = -1），
//   必须经 GPIO Matrix 路由 —— 所以下面三个脚可以任选空闲 GPIO。
#define DMX_TX2_PIN     18    // 第二片模块的 TXD
#define DMX_RX2_PIN     16    // 第二片模块的 RXD
#define DMX_EN2_PIN     4     // 第二片模块的 EN

// 引脚避让（N16R8 模块）：
//   GPIO26~32  → SPI flash
//   GPIO33~37  → Octal PSRAM
//   GPIO19/20  → 原生 USB (D-/D+)
//   GPIO43/44  → UART0（留给控制台兜底）
//   GPIO0/3/45/46 → strapping，不要用作普通输出
//
// 未被占用的脚（可留给按键 / 状态灯 / 扩展）：
//   5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 21, 38, 39, 40, 41, 42, 47, 48
//   （48 = 多数 S3 开发板的板载 RGB LED）

// ==================== EN 是单个方向脚（不是分开的 DE + /RE）====================
// 用户模块的引脚是: EN, A, B, GND, TXD, RXD, VCC —— 只有一个 EN。
// 也就是说 DE 与 /RE 在模块内部已经处理好了，我们只需要驱动这一个脚。
//
//   EN = 低  → 不驱动总线 + 使能接收   （dmx_transceiver_rx()）
//   EN = 高  → 驱动总线                （dmx_transceiver_tx()）
//
// 代码上仍然是"一对"逻辑（dmx_transceiver_tx/rx 同时写两个电平），
// 只是把这**两个逻辑脚映射到同一个 GPIO** —— 见 dmx.c 的 s_out 初始化：
//   .en_pin = DMX_EN_PIN, .en_rx_level = 0, .en_tx_level = 1
// 这样"先发后收"的成对顺序依然保持，且天然不可能出现两个脚互相矛盾的情况
// （那正是需要 74HC04 反相器的场景，此模块不需要）。
//
// ⚠ 仍需确认的一件事：EN 低电平时模块是否**真的常驻接收**。
//   两种可能的模块内部接法：
//     (a) EN = DE 单独控制驱动；/RE 内部常接地 → EN 低时一直能收（最理想）
//     (b) EN 同时并到 DE 和 /RE      → EN 低时"不驱动 + 接收开"（也满足半双工）
//   两者对本固件的行为都正确。用 BLE 命令 `0xA0 0x40 0` 跑**环回自检**即可验证：
//   把 A/B 短接（可经 120Ω）后发该命令，8 字节全部回读即通过。
//   详见 dmx.c 的 dmx_loopback_selftest()。

// ==================== 模块接线（每口一片）====================
//   VCC  → 3.3V
//   GND  → GND
//   TXD  → ESP GPIO17 (宇宙1) / GPIO18 (宇宙2)   [ESP 输出 → 模块 DI]
//   RXD  → ESP GPIO15 (宇宙1) / GPIO16 (宇宙2)   [ESP 输入 ← 模块 RO]
//   EN   → ESP GPIO2  (宇宙1) / GPIO4  (宇宙2)   [方向控制]
//   A    → DMX 总线 A (XLR 母头 pin3)
//   B    → DMX 总线 B (XLR 母头 pin2)
//   末端设备 A/B 间并 120Ω 终端电阻
//
// ⚠ A/B 不要接反（DMX 标准: pin2 = B / Data-, pin3 = A / Data+）。
//   接反的表现是"完全收不到"，用环回自检时则表现为 0 字节回读。

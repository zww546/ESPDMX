# 效果模型 v2 设计稿（阵列扩散 + 双宇宙）

> **状态：已实施并完成真机验收（2026-09-14）**
>
> 真机验收（ESP32-S3 @COM13 + 安卓 App）：`flash_esp32.py -p COM13 --check 20` **12/12 通过** ——
> 两口驱动与中断（UART1/UART2 + 两个 GPTimer）全在 CPU1、渲染管线 1024 通道、
> **U1/U2 各自 41fps、fail=0**、无 panic。
>
> 跨宇宙落位另用固件自检在真机验证：写 `ch513..520` 与横跨边界的 `ch509..516`，
> 遥测显示 `U1 ch1..8 = 1..8`、`U2 ch1..8 = 54 55 56 57 105 106 107 108`，与预期逐字节一致。
>
> App 侧：整状态推送的 5 帧被设备收到（`start=1/256/511/766/1021`，后三帧已越过 512），
> 1024 通道状态同步的 7 个通知（头 + 5 分块 + 结束）收发正常。
>
> **端到端**：App 里给"宇宙 2 / 地址 1"的实例应用灯库后点「定位」，设备遥测显示
> `U1 ch1..8 = 0 0 0 0 0 0 0 0`、`U2 ch1..8 = 255 0 255 0 0 0 0 0`
> —— 调光与频闪落在**宇宙 2**，宇宙 1 完全不受影响 ✓
>
> 目标：把"一个效果槽只能驱动一组固定通道"改成"驱动一个**灯具阵列**，并按灯具序号扩散相位"，
> 同时把通道寻址从 512 扩到 **1024（2 宇宙）**。
> 这一步做完，从"玩具级效果"进入"控台级观感"：波浪、对称张开、跑马灯、往返扫。

---

## 0. 一句话总结

| 改什么 | 为什么 |
|---|---|
| 效果槽 → **阵列目标**（`first_ch + i×stride`，i=0..count-1） | 同型灯具本来就是等距排布，用 3 个数就能描述"一整排灯" |
| 每个目标独立相位（`phase + i×spread`） | 这是"波浪/跑马"的唯一来源，也是专业控台效果的核心 |
| 新增 形状 / 方向 / 相位 参数 | 现在只有正弦+幅度+速度，做不出锯齿、方波、往返 |
| 通道上限 512 → **1024（2 宇宙）** | 协议本来就是 16bit 寻址，改动集中在常量与缓冲区 |
| 逐通道写 → **整帧提交**（1c，可选） | 临界区次数从数百降到 2 次，给阵列效果留出性能余量 |

---

## 1. 现状（代码事实，改动前基线）

**固件**
- `fx_cfg_t`（`main/fx.h:30-49`）：一组固定通道 `pan_ch / tilt_ch / dim_ch / r,g,b / zoom / focus / color / gobo / gobo_rot / blade_ch[8] / shaper_rot_ch` + `amp16` + `speed`
- 相位推进（`main/fx.c:172-175`）：`f->phase += speed; idx = (phase>>8)&0xFF;` 查 `SIN[256]`
- 写通道（`main/fx.c:82-90` `set16`）：每个通道一次 `dmx_state_set()`，**每次进出临界区**
- 性能：`fx_task` core1 prio5、`dmx_task` core1 prio6、tick = 10ms
- 槽位：`FX_MAX_COUNT = 8`（全局共享）
- 通道上限：`dmx_state.h: DMX_CHANNELS 512`；`esp_dmx: DMX_PACKET_SIZE = 513`（**每口一宇宙，组件不需要改**）

**App**
- `DmxProtocol.MAX_CHANNELS = 512`；`0x20` 效果帧固定 **57 字节**
- `FxEngine.applyFixture(def, startAddr)` 只把一台灯映射成通道号
- 已有：多选实例（`selectedInstanceIds`）、组控制（`applyFixtureGroup`）、同灯型校验

**协议帧 0x20（现状 57B）**
```
[0]0x20 [1]slot [2]fx_id
[3..34]  pan,panF,tilt,tiltF,dim,dimF,r,g,b,zoom,zoomF,focus,focusF,color,gobo,goboRot  (16×u16)
[35..38] amp16, speed                                                                    (2×u16)
[39..54] blade[0..7]                                                                     (8×u16)
[55..56] shaper_rot
```

---

## 2. 设计 A：阵列目标 + 扩散（单宇宙即可见效）

### A.1 数据模型

`fx_cfg_t` 追加 6 个字段（语义上"第 i 台的通道 = `xxx_ch + i×stride`"）：

```c
uint16_t stride;     // 相邻实例的起始地址差（**等间距**即可，允许 > 灯型通道数）；0 = 单台（= 旧行为）
uint8_t  count;      // 台数 1..FX_MAX_TARGETS
uint8_t  spread;     // 相位扩散 0..255（映射 0..360°），每台递增
uint8_t  shape;      // 0=正弦 1=三角 2=方波 3=脉冲 4=随机 5=锯齿
uint8_t  direction;  // 0=正序 1=反序 2=往返
uint8_t  phase;      // 整体相位偏移 0..255（0..360°）
```

**成立条件（"规则阵列"）**：组内所有实例**同灯型**，且起始地址按**从小到大构成等差数列**。
间距就是 `stride`；因为实例创建时已有"地址不可重叠"的校验，所以天然 `stride >= 灯型通道数`。
（允许留空隙：1, 21, 41 三台 18ch 灯 → 等间距 20，`stride=20` 依然成立。）

fine 通道同样按 `+ i×stride` 平移 —— 同灯型布局一致，天然成立。

**基底要变成数组**（每台灯启动时的基底值不同）：
```c
// fx_inst_t 内
uint16_t base_pan[FX_MAX_TARGETS], base_tilt[FX_MAX_TARGETS], base_dim[FX_MAX_TARGETS];
uint16_t base_zoom[FX_MAX_TARGETS], base_focus[FX_MAX_TARGETS];
uint8_t  base_r[..], base_g[..], base_b[..], base_color[..], base_gobo[..], base_gobo_rot[..];
```
内存：约 16B/台 × `FX_MAX_TARGETS` × 8 槽。取 `FX_MAX_TARGETS = 48` → ≈ 6KB。可接受（S3 有 512KB SRAM + 8MB PSRAM）。

> `FX_MAX_TARGETS = 48` 的依据：2 宇宙共 1024 通道，若灯型 21ch 以上 → 满编 ≤ 48 台。
> 若你们的灯都是 30ch+，可以降到 32，省一半内存。

### A.2 tick 算法

```c
case FX_SWING: {   // 以 pan 摆动为例（其余属性同理）
    uint8_t base = (uint8_t)(f->phase >> 8) + c->phase;
    for (int i = 0; i < c->count; i++) {
        int step = (c->direction == 1) ? -i : i;          // 反序
        if (c->direction == 2) step = bounce(i);          // 往返: 0,1,..,n-1,n-2,..,1
        uint8_t idx = (uint8_t)(base + step * c->spread);
        int o = curve(c->shape, idx) * amp / 127;
        set16(c->pan_ch + i * c->stride,
              c->pan_fine_ch ? c->pan_fine_ch + i * c->stride : 0,
              clamp16((int)f->base_pan[i] + o));
    }
    break;
}
```

`bounce(i)`：`i < count ? i : 2*count - 2 - i` 之类的三角折叠，用于"往返扫"。

### A.3 形状表

```c
static inline int curve(uint8_t shape, uint8_t idx) {
    switch (shape) {
    case 0: return SIN[idx];                                   // 正弦
    case 1: return (idx < 128) ? (idx*2 - 127) : (127 - (idx-128)*2);  // 三角
    case 2: return (idx < 128) ? 127 : -127;                   // 方波
    case 3: return (idx < 64) ? 127 : -127;                    // 脉冲
    case 4: return SIN[(idx + s_rand_seed) & 0xFF];            // 随机（每台固定偏移，不逐 tick 抖动）
    case 5: return (int)idx - 127;                             // 锯齿（跑马灯）
    }
    return 0;
}
```
> 随机必须是**每台一个固定种子**（启动时生成），否则每 tick 乱跳就不成"效果"了。

### A.4 性能：整帧提交（1c，建议同期做）

现状每通道一次 `dmx_state_set()`（进出临界区 ≈0.5µs）。阵列效果每 tick 的调用次数：

```
48 台 × 3 属性 × 2(coarse+fine) ≈ 288 次 ≈ 144µs / tick(10ms) ≈ 单核 1.4%
```
不致命，但把临界区改成整帧提交后能降一个数量级，而且代码更干净（专业控台就是"先算出这一帧，再输出"）：

```
每 tick：
  1. dmx_state_snapshot(buf)     // 1 次加锁，1024B memcpy
  2. buf 上依次应用：程序层(HTP) → 效果层(按 slot 顺序)
  3. dmx_state_commit(buf)       // 1 次加锁
```
风险：会动 `fx.c` / `program.c` / `dmx.c` 的并发关系（现在效果直接写 `dmx_state`）。建议独立一步、可灰度（先只让效果层走新模型）。

---

## 3. 设计 B：双宇宙（1024 通道）

### 3.1 寻址：全局通道 1..1024

**关键发现：协议本来就是 16bit 寻址**，所以这一步主要是常量与缓冲区，不是字段宽度：

```
ch  1..512    → 宇宙 1
ch 513..1024  → 宇宙 2
universe = (ch - 1) / 512
```

好处：
- `0x01` 区间设置、`0x20` 效果帧、程序、场景、状态同步**全都不用改字段**，只是上限 512 → 1024
- **跨宇宙边界的灯具**（例如起始 500 的 24ch 灯，占 U1 500-512 + U2 1-11）自然连续，无需特殊处理
- 阵列 stride 跨宇宙也自然成立（`512 + i*stride` 会自动落到 U2）

需要改的常量：
| 位置 | 现在 | 改为 |
|---|---|---|
| `stagedmx_std/main/dmx_state.h` | `DMX_CHANNELS 512` | `1024` |
| `stagedmx_std/main/dmx_state.c` | 缓冲 `[1+512]` | 数据区 1024（建议去掉首字节起始码，改为纯数据 + 每口拷贝函数） |
| `StageDMX/.../DmxProtocol.kt` | `MAX_CHANNELS = 512` | `1024` |
| `StageDMX/.../DmxEngine.kt` | `ByteArray(512)` | `1024` |
| `StepStore` / 场景快照 / `sanitizeSnapshot` | 512 | 1024 |

### 3.2 固件：两个 UART 口

```c
typedef struct { dmx_port_t port; uint16_t ch_lo, ch_hi; int tx_pin; } dmx_out_t;
static const dmx_out_t OUTS[] = {
    { DMX_NUM_1, 1,   512,  DMX_TX_PIN  },   // 现状 GPIO17
    { DMX_NUM_2, 513, 1024, DMX_TX2_PIN },   // 新增（建议 GPIO18）
};
```

- `esp_dmx` 组件**不用改**：每个口仍是 1 宇宙、513B、`personality {512,"DMX512"}`
- **每口一个 `dmx_task`**（都钉 core1、prio 6）。两口**并行**，所以每口仍是 41~43fps（不是串行各半）
- **中断**：沿用已做的做法 —— 在 **core1** 的初始化任务里安装两个口的驱动，中断都落 core1，远离射频
- **DE 脚**：两个 SP3485 可共用 GPIO2（都是常驻发送使能），也可各自独立（更稳，省一个脚的建议是共用）
- CPU：≈ 0.06% × 2 = **0.12%**（1 口已实测）

### 3.3 引脚（需按你的板子核对）

```
TX1 = GPIO17   （现状）
TX2 = GPIO18   （建议；也可 16）
DE  = GPIO2    （共用）
```
⚠ 避免：GPIO26~32（SPI flash）、GPIO33~37（N16R8 Octal PSRAM）、GPIO19/20（原生 USB）、GPIO43/44（UART0，留给控制台兜底）。

### 3.4 App：patch 与 UI

- `FixtureInstance` 建议**增加 `universe` 字段**（1/2），`startAddr` 仍存 1..512，内部换算全局 `global = (universe-1)*512 + addr`
  - 理由：演出场景里"第几个宇宙"是人脑里的概念，直接显示全局 1024 号会让人算错
  - 兼容旧数据：缺 `universe` 字段时按 1 处理
- 校验：`addr + channelCount - 1 <= 512`（每个宇宙内）
- 重叠检测：换算成全局区间后照旧
- 显示：`EOS-1  U2@128` 这种格式（比 `@640` 直观）

### 3.5 状态同步 / 文件传输的影响

- 状态同步：512 → 1024 通道，`0x83` 分块从 3 个变 5 个（每块 ≤255）→ 多约 16ms，一次性，无感
- 文件传输：不受影响

---

## 4. 协议变更（0x20 扩展，向后兼容）

新帧 = 旧 57 字节 + 尾部 7 字节 = **64 字节**：

```
[57] strideHi   [58] strideLo
[59] count
[60] spread
[61] shape
[62] direction
[63] phase
```

**兼容性矩阵（这次设计最舒服的地方：两个方向都能跑）**

| 组合 | 行为 |
|---|---|
| **新 App → 旧固件** | 旧固件 `if (len < 57) return;` 之后只读前 57 字节，**尾部被自动忽略** → 退化为单台效果（无扩散），不报错 ✅ |
| **旧 App → 新固件** | `len == 57` → 固件用默认 `count=1, stride=0, shape=正弦, spread=0, direction=正向, phase=0` → 与今天**完全一致** ✅ |
| 新 App ↔ 新固件 | 完整功能 ✅ |

固件分派：
```c
if (len < 57) return;
/* …读前 57 字节（不变）… */
if (len >= 64) {
    cfg.stride    = RD16();
    cfg.count     = d[59];
    cfg.spread    = d[60];
    cfg.shape     = d[61];
    cfg.direction = d[62];
    cfg.phase     = d[63];
} else {
    cfg.stride = 0; cfg.count = 1; cfg.spread = 0;
    cfg.shape = 0; cfg.direction = 0; cfg.phase = 0;
}
if (cfg.count < 1) cfg.count = 1;
if (cfg.count > FX_MAX_TARGETS) cfg.count = FX_MAX_TARGETS;
```

---

## 5. App 侧变更

### FxEngine
- `FxState` 增 `stride / count / spread / shape / direction / phase`
- `applyFixture(def, startAddr)` → 新增 `applyTargets(def, instances: List<FixtureInstance>)`：
  ```
  first_ch = real(ch of instances[0])
  stride   = def.channelCount
  count    = instances.size
  ```
- **校验"是否规则阵列"**（判据就两条）：
  1. 组内所有实例 **同一个 fixtureId**（同灯型）；
  2. 按全局地址排序后，**相邻差全部相等**（`stride`），且 `stride >= def.channelCount`。
  - 成立 → 用一个槽驱动整排（`first_ch + i×stride`）
  - 不成立 → 见 §8 待定问题 1
- `sendFxSetFor()` 在 57 字节后追加 7 字节

### 效果页 UI
- 顶部作用范围提示：`作用于 5 台灯  U1@1 … U1@97`
- 参数区新增 4 个控件：
  - **扩散** 0–360°（0 = 所有灯同步，越大波越"斜"）
  - **形状** 下拉：正弦 / 三角 / 方波 / 脉冲 / 随机 / 锯齿
  - **方向** 正序 / 反序 / 往返
  - **相位** 0–360°
- 幅度 / 速度保留；切割循环仍是时间参数（`shape=6` 走原有时序分支）

### 分组
- 复用推子页实例栏的**多选** → 效果作用于该组
- 组内必须同灯型（已有校验，会提示"组内灯具类型不一致"）

---

## 6. 性能预算（2 宇宙 + 48 台阵列）

| 项目 | 数字 | 依据 |
|---|---|---|
| 每口帧长 | ≈23.6ms | 513B @250k 8N2 = 22.6ms + 1 tick |
| 每口帧率 | **41~42 fps**（两口并行，互不影响） | 已实测 41 |
| DMX 输出 CPU | 0.06% × 2 = **0.12%** | 已实测单口 |
| 阵列效果遍历（1c 之前） | 288 次 set ≈ 144µs/tick ≈ **1.4%** | `dmx_state_set` ≈0.5µs |
| 阵列效果遍历（1c 之后） | 2 次加锁 + 2×1024B memcpy ≈ **0.1%** | |
| BLE 下行 | 滑条 delta ≤255B/帧 × 30Hz ≈ 7.6KB/s | 远低于 BLE 上限 |
| 状态同步 | 1024 通道 → 5 块 ≈ 40ms | 仅连接时一次 |
| **合计** | **< 2% 单核** | 完全无压力 |

---

## 7. 分阶段落地 + 验收标准

### 1a 阵列效果 + 扩散（单宇宙，不动 512 上限）
- 固件：`fx_cfg_t` 扩展 + tick 阵列遍历 + 形状表 + 基底数组
- App：`FxState` 扩展 + 发 64 字节帧 + 4 个新控件 + 作用范围提示
- **验收**
  1. `count=1, stride=0` 时输出与今天**逐字节一致**（回归测试）
  2. 12 台同型灯、扩散 0→255：肉眼可见"齐动 → 波浪"
  3. 遥测 `TX frm=... ok=1 fail=0` 不掉帧

### 1b 双宇宙（1024 通道）
- 固件：`dmx_state` 1024 + 双 UART 口 + 双任务 + 引脚
- App：`MAX_CHANNELS=1024` + 实例加 `universe` 字段 + patch 校验 + 显示格式
- **验收**
  1. 两个口各自 41fps、`fail=0`
  2. U2 上挂灯能正常控制
  3. **跨边界灯具**（U1 末尾 → U2 开头）输出正确
  4. 场景 / 程序 / 状态同步在 1024 下正常

### 1c 整帧提交（性能与并发）
- `snapshot → 计算 → commit`
- **验收**：临界区次数从数百降到 2；效果叠加 + 程序 HTP 结果与 1a/1b 一致

---

## 8. 已定参数（本次实施）

| # | 项目 | 决定 |
|---|---|---|
| 1 | 组内非规则阵列 | **方案 A**：App 校验（同灯型 + 等差地址），不满足则拒绝并提示，不做自动拆段 |
| 2 | `FX_MAX_TARGETS` | **64**（1024 通道 ÷ 最小 16ch 灯型的满编台数；基底内存 8 槽 × 64 × 16B ≈ 8KB） |
| 3 | TX2 引脚 | **GPIO18** |
| 4 | DE 脚 | **两口共用 GPIO2**（常驻发送使能） |
| 5 | 宇宙的表达 | **"宇宙 + 地址"两个输入框**（实例增加 `universe` 字段，内部换算全局通道） |
| 6 | 形状集合 | **全都要**：波形 `shape` = 正弦/三角/方波/脉冲/随机/锯齿；另加**阵列包络** `envelope` = 无/渐入/渐出/对称/两端强（奇数偶数交替由 `spread=128` 自然得到） |
| 7 | 1c 整帧提交 | **同期做**（与 1a/1b 一并落地） |

### 8.1 形状与包络的语义（定稿）

**`shape`（波形，作用于相位 `idx`）**

| 值 | 名称 | 输出 |
|---|---|---|
| 0 | 正弦 | `SIN[idx]`（峰值在 idx=64，谷值 192） |
| 1 | 三角 | 峰值对齐正弦：`0→64` 升到 +127，`64→192` 降到 -127，`192→255` 回到 0 |
| 2 | 方波 | `idx<128 ? +127 : -127`（正半周与正弦一致） |
| 3 | 脉冲 | 占空 1/4 且**峰值对齐正弦**：`32 ≤ idx < 96 → +127` |
| 4 | 随机 | `SIN[idx + 每台固定随机偏移]`（**不是**每 tick 乱跳） |
| 5 | 锯齿 | `idx - 127`（跑马灯；峰值在周期末尾是锯齿固有形状） |

> ⚠ **所有波形的相位对齐点必须一致**（零点 0/128、峰值 64、谷值 192），
> 否则现场切换波形时视觉相位会突然跳一段。这一点已由宿主端测试
> （`stagedmx_std/test_host/`）覆盖。

**`direction`（相位沿阵列的走向）**

| 值 | 名称 | `step(i)` |
|---|---|---|
| 0 | 正序 | `i` |
| 1 | 反序 | `-i` |
| 2 | 往返 | `i < count/2 ? i : count-1-i`（对称张开/收拢） |

**`envelope`（幅度沿阵列的包络，乘在幅度上）**

| 值 | 名称 | 系数（×256） |
|---|---|---|
| 0 | 无 | 256 |
| 1 | 渐入 | `i*256/(count-1)` |
| 2 | 渐出 | `256 - i*256/(count-1)` |
| 3 | 对称（中间强） | 三角：`256 - |i - mid|*512/(count-1)` |
| 4 | 两端强（中间弱） | 上式的反相 |

**奇数/偶数交替**：不需要独立参数 —— `spread = 128` 就是相邻灯反相（正=奇偶交替）。

### 8.2 帧长定稿

`0x20` 新帧 = 旧 57 字节 + `stride(2) count(1) spread(1) shape(1) direction(1) phase(1) envelope(1)` = **65 字节**
（旧固件只读前 57 字节，尾部自动忽略 → 双向兼容）

---

## 9. 工作量粗估

| 阶段 | 固件 | App | 主要工作量在哪 |
|---|---|---|---|
| 1a | ~200 行 | ~250 行 + 4 控件 | `fx.c` tick 重写 + 帧扩展 + 基底数组 |
| 1b | ~150 行 | ~200 行 | 机械但面广（1024 常量 + patch UI + 双口） |
| 1c | ~150 行 | 无 | 并发模型（`fx.c`/`program.c`/`dmx_state` 配合） |

---

## 10. 离线验证（宿主端测试）

无需硬件即可把**真实的 `fx.c` / `program.c` / `dmx_state.c`** 编译到 PC 上跑
（用 `test_host/stubs/` 里的最小 FreeRTOS 桩）：

```bash
cd stagedmx_std && bash test_host/run.sh
```

| 套件 | 覆盖 |
|---|---|
| `test_fx.c`（8 组） | 回归(count=1 与 v5 逐值一致)、扩散相位、方向(正序/反序/往返)、包络、5 种波形、跨宇宙寻址、占用判定 O(1)、RGB 空通道不误写 |
| `test_render.c`（7 组） | 乐观并发提交、程序层 HTP + **off-by-one 回归**、多程序 HTP、步进推进、效果优先不覆盖、**双宇宙口帧切分**、跨宇宙区间写入 |
| `test_proto.c`（5 组） | **0x20 帧协议契约**：v5(57B) 字段逐项落位 + 阵列参数取默认、v6(65B) 阵列参数落位、长度分派矩阵(56 拒/57 收/64 收/65 收)、非法帧拒绝、越界参数夹紧 |

这三套测试累计抓出并修复了 4 个真 BUG：
1. `apply_rgb` 未逐个通道判 0 → `count>1` 时会写到 `i×stride` 的无关通道
2. 三角/脉冲波形相位与正弦差 90° → 切换波形时视觉相位跳变
3. `fx.c` 缺 `<string.h>`
4. **`fx_cfg_parse` 的切割片循环漏了花括号** —— `RD16()` 宏展开是两条语句，
   `for (...) x = RD16();` 会让 `i += 2` 落在循环外，导致 8 个切割片全读成同一通道、
   后续字段整体错位 → **切割循环彻底失效**（不崩不报错，最难查的一类）

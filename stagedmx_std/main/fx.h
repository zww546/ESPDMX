#pragma once
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// 板载效果引擎 — 独立层，可离线运行（BLE 断开后继续）。
// 效果作为独立层叠加在程序/推杆之上：每个效果控制一组通道（如 PAN/TILT/DIM/RGB），
// 按波形在“基底值”上做偏移；效果启停与程序播放互不冲突。
// 基底 = 效果启动时捕获的通道值（推子/程序当前值），效果运行期间不实时回读，
// 避免效果自身输出污染基底造成漂移；调整幅度/速度重发配置不会重置基底。
//
// 效果类型与 App FxEngine.presets 对齐：
//   1=圆形摇动  2=水平摇动  3=垂直摇动  4=频闪  5=RGB变色
//   6=放大摆动  7=调焦摆动  8=色盘摆动  9=图案盘摆动
//   10=图案盘自转  11=固定图案摇动
//   13=切割循环（时序分步）
//
// v6：**阵列目标 + 相位扩散**。
//   一个效果槽不再只驱动"一组通道"，而是驱动一整排灯：
//     第 i 台的某属性通道 = 该属性通道 + i × stride      (i = 0..count-1)
//     第 i 台的相位       = 基准相位 + step(i) × spread
//   因为同型灯具是等距 patch 的，用 (通道, stride, count) 三个数就能描述一整排，
//   固件不需要地址表。spread=0 → 全排同步（= 旧行为）；spread 拉大 → 波浪。
//   v6 同时把渲染方式改成"整帧提交"：渲染任务 snapshot → 各层计算 → commit，
//   效果不再直接写 dmx_state（见 render.c）。

#define FX_MAX_COUNT    8       // 最多同时运行的效果数（槽位，全局共享）
#define FX_BLADE_COUNT  8       // 切割片最大数（blade1a..blade4b）
#define FX_MAX_TARGETS  64      // 阵列最多 64 台（1024 通道 ÷ 16ch 灯型的满编台数）

// ---- 波形 shape ----
#define FX_SHAPE_SIN     0      // 正弦
#define FX_SHAPE_TRI     1      // 三角
#define FX_SHAPE_SQUARE  2      // 方波
#define FX_SHAPE_PULSE   3      // 脉冲（占空 1/4）
#define FX_SHAPE_RANDOM  4      // 随机（每台一个固定相位，不逐 tick 抖动）
#define FX_SHAPE_RAMP    5      // 锯齿（跑马灯）

// ---- 方向 direction（相位沿阵列的走向）----
#define FX_DIR_FWD     0        // 正序
#define FX_DIR_REV     1        // 反序
#define FX_DIR_BOUNCE  2        // 往返（对称张开/收拢）

// ---- 阵列包络 envelope（幅度沿阵列的系数）----
#define FX_ENV_NONE  0          // 无
#define FX_ENV_IN    1          // 渐入
#define FX_ENV_OUT   2          // 渐出
#define FX_ENV_MID   3          // 对称（中间强）
#define FX_ENV_EDGE  4          // 两端强（中间弱）

typedef struct {
    uint8_t  fx_id;          // 1..13，0=空槽
    // 控制通道（1-based 全局通道 1..1024，0=未用）及对应 fine 通道（0=无 fine）
    // v6：这些是**第 0 台灯**的通道，第 i 台的通道 = 该值 + i × stride
    uint16_t pan_ch,     pan_fine_ch;
    uint16_t tilt_ch,    tilt_fine_ch;
    uint16_t dim_ch,     dim_fine_ch;
    uint16_t r_ch, g_ch, b_ch;
    uint16_t zoom_ch,    zoom_fine_ch;
    uint16_t focus_ch,   focus_fine_ch;
    uint16_t color_ch;               // 色盘
    uint16_t gobo_ch;                // 图案盘
    uint16_t gobo_rot_ch;            // 图案盘旋转
    uint16_t blade_ch[FX_BLADE_COUNT];  // 切割片 1a..4b
    uint16_t shaper_rot_ch;             // 切割旋转
    uint16_t amp16;          // 幅度 0..65535（0=不动，65535=全行程，峰值偏移）
    uint16_t speed;          // 速度 0..65535，越大越快。
                             // 8.8 定点相位累加器每 tick 加 speed（fx.c: f->phase += c->speed），
                             // 高 8 位作为 256 项波形表索引 → 实际每 tick 相位推进 speed/256 个表步。
                             // 注意：不是"speed×256"。
    // ---- v6 阵列参数 ----
    uint16_t stride;         // 相邻实例的起始地址差（等间距即可，允许 > 灯型通道数）；0 = 单台
    uint8_t  count;          // 台数 1..FX_MAX_TARGETS
    uint8_t  spread;         // 相位扩散 0..255（0=全排同步；128=奇偶交替）
    uint8_t  shape;          // FX_SHAPE_*
    uint8_t  direction;      // FX_DIR_*
    uint8_t  phase;          // 整体相位偏移 0..255
    uint8_t  envelope;       // FX_ENV_*
    volatile bool running;
} fx_cfg_t;

// ---- 效果控制 ----
// 初始化（创建互斥量、清空槽位）。由 render_start_task() 调用。
void fx_init(void);
// 配置并启动一个效果槽（slot 0..7），覆盖同槽已有效果
void fx_set(uint8_t slot, const fx_cfg_t *cfg);
// 停止指定槽效果
void fx_stop(uint8_t slot);
void fx_stop_all(void);
// 查询
bool fx_is_running(uint8_t slot);
int  fx_running_count(void);
uint8_t fx_active_id(uint8_t slot);
// 查询某槽运行参数（状态同步用）：返回是否运行，输出 fx_id / amp16 / speed
bool fx_get_info(uint8_t slot, uint8_t *fx_id, uint16_t *amp16, uint16_t *speed);

// 某通道是否被运行中的效果占用（效果优先：程序合并时跳过这些通道）
bool fx_owns_channel(uint16_t ch);

// ---- 渲染（由渲染管线 render.c 每 10ms 调用一次）----
// 在整帧缓冲 buf（1024 字节，buf[0] = 全局通道 1）上叠加所有运行中的效果
void fx_render(uint8_t *buf);

#ifdef __cplusplus
}
#endif

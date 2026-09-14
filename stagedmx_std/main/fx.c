/**
 * 板载效果引擎 — 独立层叠加，离线运行。
 *
 * v6（本次重构）：
 *   1. **阵列目标**：一个槽驱动一整排灯。第 i 台的某属性通道 = 属性通道 + i×stride。
 *   2. **相位扩散**：第 i 台的相位 = 基准 + step(i)×spread → 波浪/跑马/对称张开。
 *   3. **波形 + 阵列包络**：shape（正弦/三角/方波/脉冲/随机/锯齿）+ envelope（渐入/渐出/对称/两端强）。
 *   4. **整帧提交**：不再直接写 dmx_state，而是渲染到 buf（由 render.c 统一 snapshot/commit）。
 *      临界区从"每通道一次"降到"每 tick 两次"。
 *
 * 相位推进仍是 8.8 定点（`phase += speed`，高 8 位查 256 项正弦表）。
 */
#include "fx.h"
#include "dmx_state.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "esp_log.h"
#include <string.h>

static const char *TAG = "fx";

#define PHASE_COUNT   256

typedef struct {
    fx_cfg_t cfg;
    uint32_t  phase;      // 8.8 定点相位累加器：高 8 位 = 波形索引
    // ---- 阵列基底（每台灯一份，16bit：coarse<<8|fine）----
    uint16_t base_pan[FX_MAX_TARGETS],  base_tilt[FX_MAX_TARGETS],  base_dim[FX_MAX_TARGETS];
    uint16_t base_zoom[FX_MAX_TARGETS], base_focus[FX_MAX_TARGETS];
    uint8_t  base_r[FX_MAX_TARGETS], base_g[FX_MAX_TARGETS], base_b[FX_MAX_TARGETS];
    uint8_t  base_color[FX_MAX_TARGETS], base_gobo[FX_MAX_TARGETS], base_gobo_rot[FX_MAX_TARGETS];
    uint8_t  rnd[FX_MAX_TARGETS];    // shape=RANDOM 用的每台固定相位（不逐 tick 抖动）
    // ---- 切割循环（fx_id=13）时序状态 ----
    uint32_t step_tick;
    uint8_t  step;
    bool     blade_on;
    bool     in_loop_gap;
} fx_inst_t;

static fx_inst_t s_fx[FX_MAX_COUNT];
static SemaphoreHandle_t s_fx_mux = NULL;

static void fx_lock(void)   { if (s_fx_mux) xSemaphoreTake(s_fx_mux, portMAX_DELAY); }
static void fx_unlock(void) { if (s_fx_mux) xSemaphoreGive(s_fx_mux); }

// 256 项正弦表 sin(0..2π)*127（四舍五入，峰值 ±127）
static const int SIN[256] = {
    0,3,6,9,12,16,19,22,25,28,31,34,37,40,43,46,
    49,51,54,57,60,63,65,68,71,73,76,78,81,83,85,88,
    90,92,94,96,98,100,102,104,106,107,109,111,112,113,115,116,
    117,118,120,121,122,122,123,124,125,125,126,126,126,127,127,127,
    127,127,127,127,126,126,126,125,125,124,123,122,122,121,120,118,
    117,116,115,113,112,111,109,107,106,104,102,100,98,96,94,92,
    90,88,85,83,81,78,76,73,71,68,65,63,60,57,54,51,
    49,46,43,40,37,34,31,28,25,22,19,16,12,9,6,3,
    0,-3,-6,-9,-12,-16,-19,-22,-25,-28,-31,-34,-37,-40,-43,-46,
    -49,-51,-54,-57,-60,-63,-65,-68,-71,-73,-76,-78,-81,-83,-85,-88,
    -90,-92,-94,-96,-98,-100,-102,-104,-106,-107,-109,-111,-112,-113,-115,-116,
    -117,-118,-120,-121,-122,-122,-123,-124,-125,-125,-126,-126,-126,-127,-127,-127,
    -127,-127,-127,-127,-126,-126,-126,-125,-125,-124,-123,-122,-122,-121,-120,-118,
    -117,-116,-115,-113,-112,-111,-109,-107,-106,-104,-102,-100,-98,-96,-94,-92,
    -90,-88,-85,-83,-81,-78,-76,-73,-71,-68,-65,-63,-60,-57,-54,-51,
    -49,-46,-43,-40,-37,-34,-31,-28,-25,-22,-19,-16,-12,-9,-6,-3
};

static inline int clamp8(int v)  { return v < 0 ? 0 : (v > 255 ? 255 : v); }
static inline int clamp16(int v) { return v < 0 ? 0 : (v > 65535 ? 65535 : v); }

// ===================== 波形 / 包络 / 相位 =====================

/** 波形：把相位索引（0..255）映射到 -127..+127
 *
 *  ⚠ 所有波形的**相位对齐点必须一致**（零点在 0/128、峰值在 64、谷值在 192），
 *    否则现场切换波形时视觉相位会突然跳一段。三角波尤其要注意：
 *    如果写成 `idx<128 ? idx*2-127 : ...`，峰值会落在 128 而正弦峰值在 64，
 *    两者差 90°。这里统一按"峰值在 64、谷值在 192"实现。
 */
static inline int fx_curve(uint8_t shape, uint8_t idx)
{
    switch (shape) {
    case FX_SHAPE_TRI: {
        // 分段三角：0→64 升到 +127，64→192 降到 -127，192→255 回到 0
        if (idx < 64)       return (int)idx * 127 / 64;
        if (idx < 192)      return 127 - (int)(idx - 64) * 254 / 128;
        return -127 + (int)(idx - 192) * 127 / 64;
    }
    case FX_SHAPE_SQUARE:
        return (idx < 128) ? 127 : -127;    // 正半周 0..127（与正弦同相）
    case FX_SHAPE_PULSE:
        // 占空 1/4，且**峰值对齐正弦**（idx=64 在脉冲内部，而不是边界）
        return (idx >= 32 && idx < 96) ? 127 : -127;
    case FX_SHAPE_RAMP:
        return (int)idx - 127;              // 锯齿（跑马灯）：峰值落在周期末尾，这是锯齿的固有形状
    case FX_SHAPE_RANDOM:
    case FX_SHAPE_SIN:
    default:
        return SIN[idx];
    }
}

/** 第 i 台的相位索引：base + extra + step(i)×spread */
static inline uint8_t fx_target_idx(const fx_cfg_t *c, uint8_t base_phase, uint8_t extra, int i)
{
    int step;
    switch (c->direction) {
    case FX_DIR_REV:
        step = -i;
        break;
    case FX_DIR_BOUNCE:
        step = (i * 2 < (int)c->count) ? i : ((int)c->count - 1 - i);
        break;
    default:
        step = i;
        break;
    }
    return (uint8_t)(base_phase + extra + (uint8_t)(step * (int)c->spread));
}

/** 阵列包络系数 ×256（0=无、1=渐入、2=渐出、3=中间强、4=两端强） */
static inline int fx_env(const fx_cfg_t *c, int i)
{
    int n = (int)c->count;
    if (n <= 1 || c->envelope == FX_ENV_NONE) return 256;
    int span = n - 1;
    int num = i * 256 / span;
    switch (c->envelope) {
    case FX_ENV_IN:   return num;
    case FX_ENV_OUT:  return 256 - num;
    case FX_ENV_MID: {
        int d = (2 * i > span) ? (2 * i - span) : (span - 2 * i);
        return 256 - d * 256 / span;
    }
    case FX_ENV_EDGE: {
        int d = (2 * i > span) ? (2 * i - span) : (span - 2 * i);
        return d * 256 / span;
    }
    default:          return 256;
    }
}

// ===================== 帧缓冲读写（整帧提交） =====================

/** 写 coarse(+fine)，v 为 16bit（coarse<<8|fine）。越界自动忽略。 */
static inline void put16(uint8_t *buf, uint16_t ch, uint16_t fine, uint16_t v)
{
    if (ch >= 1 && ch <= DMX_CHANNELS) buf[ch - 1] = (uint8_t)(v >> 8);
    if (fine >= 1 && fine <= DMX_CHANNELS) buf[fine - 1] = (uint8_t)(v & 0xFF);
}

static inline void put8(uint8_t *buf, uint16_t ch, uint8_t v)
{
    if (ch >= 1 && ch <= DMX_CHANNELS) buf[ch - 1] = v;
}

/** 捕获基底时的 16bit 读（有 fine 则 coarse<<8|fine） */
static uint16_t get16_dmx(uint16_t ch, uint16_t fine)
{
    if (ch == 0) return 0;
    uint16_t coarse = dmx_state_get(ch);
    if (fine) return (uint16_t)((coarse << 8) | dmx_state_get(fine));
    return (uint16_t)(coarse << 8);
}

// ===================== 阵列施加 =====================

/** 16bit 属性：按阵列施加波形偏移 */
static void apply16(uint8_t *buf, const fx_inst_t *f,
                    uint16_t ch, uint16_t fine, const uint16_t *base,
                    uint8_t base_phase, uint8_t extra, int amp)
{
    const fx_cfg_t *c = &f->cfg;
    if (!ch) return;
    for (int i = 0; i < c->count; i++) {
        uint32_t off = (uint32_t)i * c->stride;
        if (ch + off > DMX_CHANNELS) break;
        uint8_t idx = fx_target_idx(c, base_phase, extra, i);
        if (c->shape == FX_SHAPE_RANDOM) idx = (uint8_t)(idx + f->rnd[i]);
        int a = amp * fx_env(c, i) / 256;
        int o = fx_curve(c->shape, idx) * a / 127;
        put16(buf, (uint16_t)(ch + off), fine ? (uint16_t)(fine + off) : 0,
              (uint16_t)clamp16((int)base[i] + o));
    }
}

/** 8bit 属性：波形偏移按 255/65535 比例缩放（与旧版一致） */
static void apply8(uint8_t *buf, const fx_inst_t *f,
                   uint16_t ch, const uint8_t *base,
                   uint8_t base_phase, uint8_t extra, int amp)
{
    const fx_cfg_t *c = &f->cfg;
    if (!ch) return;
    for (int i = 0; i < c->count; i++) {
        uint32_t off = (uint32_t)i * c->stride;
        if (ch + off > DMX_CHANNELS) break;
        uint8_t idx = fx_target_idx(c, base_phase, extra, i);
        if (c->shape == FX_SHAPE_RANDOM) idx = (uint8_t)(idx + f->rnd[i]);
        int a = amp * fx_env(c, i) / 256;
        int o = fx_curve(c->shape, idx) * a / 127;
        put8(buf, (uint16_t)(ch + off), (uint8_t)clamp8((int)base[i] + o * 255 / 65535));
    }
}

/** 频闪：波形 >0 时给基底，否则 0（spread 让每台错相 → 追光式频闪） */
static void apply_strobe16(uint8_t *buf, const fx_inst_t *f,
                           uint16_t ch, uint16_t fine, const uint16_t *base, uint8_t base_phase)
{
    const fx_cfg_t *c = &f->cfg;
    if (!ch) return;
    for (int i = 0; i < c->count; i++) {
        uint32_t off = (uint32_t)i * c->stride;
        if (ch + off > DMX_CHANNELS) break;
        uint8_t idx = fx_target_idx(c, base_phase, 0, i);
        if (c->shape == FX_SHAPE_RANDOM) idx = (uint8_t)(idx + f->rnd[i]);
        bool on = fx_curve(c->shape, idx) > 0;
        put16(buf, (uint16_t)(ch + off), fine ? (uint16_t)(fine + off) : 0,
              on ? base[i] : 0);
    }
}

/** RGB 变色：R/G/B 三通道 120° 相差，全行程扫（沿用旧版语义） */
static void apply_rgb(uint8_t *buf, const fx_inst_t *f, uint8_t base_phase)
{
    const fx_cfg_t *c = &f->cfg;
    for (int i = 0; i < c->count; i++) {
        uint32_t off = (uint32_t)i * c->stride;
        if ((c->r_ch && c->r_ch + off > DMX_CHANNELS) ||
            (c->g_ch && c->g_ch + off > DMX_CHANNELS) ||
            (c->b_ch && c->b_ch + off > DMX_CHANNELS)) break;
        uint8_t i0 = fx_target_idx(c, base_phase, 0, i);
        uint8_t i1 = fx_target_idx(c, base_phase, 85, i);
        uint8_t i2 = fx_target_idx(c, base_phase, 170, i);
        if (c->shape == FX_SHAPE_RANDOM) {
            i0 = (uint8_t)(i0 + f->rnd[i]);
            i1 = (uint8_t)(i1 + f->rnd[i]);
            i2 = (uint8_t)(i2 + f->rnd[i]);
        }
        // ⚠ 必须逐个通道判 0：通道为 0 表示"该灯型没有这个属性"，
        //   若不判，count>1 时 `0 + i×stride` 会写到完全无关的通道上。
        if (c->r_ch) put8(buf, (uint16_t)(c->r_ch + off), (uint8_t)clamp8((fx_curve(c->shape, i0) + 127) * 255 / 254));
        if (c->g_ch) put8(buf, (uint16_t)(c->g_ch + off), (uint8_t)clamp8((fx_curve(c->shape, i1) + 127) * 255 / 254));
        if (c->b_ch) put8(buf, (uint16_t)(c->b_ch + off), (uint8_t)clamp8((fx_curve(c->shape, i2) + 127) * 255 / 254));
    }
}

// ===================== 单槽渲染 =====================

static void fx_render_slot(fx_inst_t *f, uint8_t *buf)
{
    const fx_cfg_t *c = &f->cfg;
    if (!c->running || c->fx_id == 0) return;
    if (c->fx_id != 13) {
        // 速度：8.8 定点累加，高 8 位即波形索引（值越大越快）
        f->phase += (uint32_t)c->speed;
    }
    uint8_t base = (uint8_t)((f->phase >> 8) + c->phase);
    int amp = c->amp16;

    switch (c->fx_id) {
    case 1:   // 圆形摇动: pan/tilt 相差 90°
        apply16(buf, f, c->pan_ch,  c->pan_fine_ch,  f->base_pan,  base, 0,  amp);
        apply16(buf, f, c->tilt_ch, c->tilt_fine_ch, f->base_tilt, base, 64, amp);
        break;
    case 2:   // 水平摇动
        apply16(buf, f, c->pan_ch, c->pan_fine_ch, f->base_pan, base, 0, amp);
        break;
    case 3:   // 垂直摇动
        apply16(buf, f, c->tilt_ch, c->tilt_fine_ch, f->base_tilt, base, 0, amp);
        break;
    case 4:   // 频闪（波形控制亮灭；spread 让每台错相）
        apply_strobe16(buf, f, c->dim_ch, c->dim_fine_ch, f->base_dim, base);
        break;
    case 5:   // RGB 变色
        apply_rgb(buf, f, base);
        break;
    case 6:   // 放大摆动
        apply16(buf, f, c->zoom_ch, c->zoom_fine_ch, f->base_zoom, base, 0, amp);
        break;
    case 7:   // 调焦摆动
        apply16(buf, f, c->focus_ch, c->focus_fine_ch, f->base_focus, base, 0, amp);
        break;
    case 8:   // 色盘摆动
        apply8(buf, f, c->color_ch, f->base_color, base, 0, amp);
        break;
    case 9:   // 图案盘摆动
        apply8(buf, f, c->gobo_ch, f->base_gobo, base, 0, amp);
        break;
    case 10:  // 图案盘自转
        apply8(buf, f, c->gobo_rot_ch, f->base_gobo_rot, base, 0, amp);
        break;
    case 11:  // 固定图案摇动：gobo 固定基底，gobo_rot 抖动
        for (int i = 0; i < c->count; i++) {
            uint32_t off = (uint32_t)i * c->stride;
            if (c->gobo_ch && c->gobo_ch + off <= DMX_CHANNELS)
                put8(buf, (uint16_t)(c->gobo_ch + off), f->base_gobo[i]);
        }
        apply8(buf, f, c->gobo_rot_ch, f->base_gobo_rot, base, 0, amp);
        break;
    case 13: { // 切割循环（时序分步）
        // v6 参数语义（与 App FxEngine 换算一致，tick = 10ms）：
        //   speed = 每步时长 / 10ms      amp16 = 循环间隔 / 10ms
        uint32_t step_ticks = c->speed ? c->speed : 1;
        uint32_t gap_ticks = c->amp16;

        // 收集存在的切割片通道（0=未用，跳过）
        uint16_t blades[FX_BLADE_COUNT];
        int nblade = 0;
        for (int i = 0; i < FX_BLADE_COUNT; i++) {
            if (c->blade_ch[i]) blades[nblade++] = c->blade_ch[i];
        }
        if (f->in_loop_gap) {
            for (int b = 0; b < nblade; b++)
                for (int i = 0; i < c->count; i++)
                    put8(buf, (uint16_t)(blades[b] + (uint32_t)i * c->stride), 0);
            if (c->shaper_rot_ch)
                for (int i = 0; i < c->count; i++)
                    put8(buf, (uint16_t)(c->shaper_rot_ch + (uint32_t)i * c->stride), 0);
            f->step_tick++;
            if (gap_ticks == 0 || f->step_tick >= gap_ticks) {
                f->in_loop_gap = false;
                f->step_tick = 0;
                f->step = 0;
                f->blade_on = false;
            }
            break;
        }

        f->step_tick++;
        if (f->step_tick >= step_ticks) {
            f->step_tick = 0;
            if (f->step < nblade) {
                uint8_t v = f->blade_on ? 0 : 255;
                for (int i = 0; i < c->count; i++)
                    put8(buf, (uint16_t)(blades[f->step] + (uint32_t)i * c->stride), v);
                f->blade_on = !f->blade_on;
                if (!f->blade_on) f->step++;
            } else if (f->step == nblade) {
                if (c->shaper_rot_ch)
                    for (int i = 0; i < c->count; i++)
                        put8(buf, (uint16_t)(c->shaper_rot_ch + (uint32_t)i * c->stride), 255);
                f->blade_on = true;
                f->step = nblade + 1;
            } else if (f->step == nblade + 1) {
                if (c->shaper_rot_ch)
                    for (int i = 0; i < c->count; i++)
                        put8(buf, (uint16_t)(c->shaper_rot_ch + (uint32_t)i * c->stride), 0);
                f->blade_on = false;
                f->in_loop_gap = true;
                f->step = 0;
            }
        }
        break;
    }
    default:
        break;
    }
}

void fx_render(uint8_t *buf)
{
    fx_lock();
    for (int i = 0; i < FX_MAX_COUNT; i++) {
        fx_render_slot(&s_fx[i], buf);
    }
    fx_unlock();
}

// ===================== 基底捕获 =====================

static void fx_capture_bases(fx_inst_t *f, const fx_cfg_t *c)
{
    int n = c->count;
    if (n > FX_MAX_TARGETS) n = FX_MAX_TARGETS;
    for (int i = 0; i < n; i++) {
        uint32_t off = (uint32_t)i * c->stride;
        f->base_pan[i]  = get16_dmx(c->pan_ch  ? (uint16_t)(c->pan_ch  + off) : 0,
                                    c->pan_fine_ch ? (uint16_t)(c->pan_fine_ch + off) : 0);
        f->base_tilt[i] = get16_dmx(c->tilt_ch ? (uint16_t)(c->tilt_ch + off) : 0,
                                    c->tilt_fine_ch ? (uint16_t)(c->tilt_fine_ch + off) : 0);
        f->base_dim[i]  = get16_dmx(c->dim_ch  ? (uint16_t)(c->dim_ch  + off) : 0,
                                    c->dim_fine_ch ? (uint16_t)(c->dim_fine_ch + off) : 0);
        f->base_zoom[i] = get16_dmx(c->zoom_ch ? (uint16_t)(c->zoom_ch + off) : 0,
                                    c->zoom_fine_ch ? (uint16_t)(c->zoom_fine_ch + off) : 0);
        f->base_focus[i]= get16_dmx(c->focus_ch ? (uint16_t)(c->focus_ch + off) : 0,
                                    c->focus_fine_ch ? (uint16_t)(c->focus_fine_ch + off) : 0);
        f->base_r[i] = c->r_ch ? dmx_state_get((uint16_t)(c->r_ch + off)) : 0;
        f->base_g[i] = c->g_ch ? dmx_state_get((uint16_t)(c->g_ch + off)) : 0;
        f->base_b[i] = c->b_ch ? dmx_state_get((uint16_t)(c->b_ch + off)) : 0;
        f->base_color[i]    = c->color_ch    ? dmx_state_get((uint16_t)(c->color_ch + off)) : 0;
        f->base_gobo[i]     = c->gobo_ch     ? dmx_state_get((uint16_t)(c->gobo_ch + off)) : 0;
        f->base_gobo_rot[i] = c->gobo_rot_ch ? dmx_state_get((uint16_t)(c->gobo_rot_ch + off)) : 0;
        // RANDOM 波形的每台固定相位：用与 256 互质的步长打散，肉眼即为随机
        f->rnd[i] = (uint8_t)(i * 137 + 43);
    }
}

// ===================== 公共 API =====================

void fx_init(void)
{
    if (!s_fx_mux) s_fx_mux = xSemaphoreCreateMutex();
    memset(s_fx, 0, sizeof(s_fx));
    ESP_LOGI(TAG, "fx engine v6: array targets (max %d), phase spread, shapes, envelope",
             FX_MAX_TARGETS);
}

void fx_set(uint8_t slot, const fx_cfg_t *cfg)
{
    if (slot >= FX_MAX_COUNT) return;
    fx_inst_t tmp;
    // 先在本任务里捕获基底（读 dmx_state，不持 s_fx 锁，避免长临界）
    fx_cfg_t c = *cfg;
    if (c.count < 1) c.count = 1;
    if (c.count > FX_MAX_TARGETS) c.count = FX_MAX_TARGETS;
    if (c.shape >= 6) c.shape = FX_SHAPE_SIN;
    if (c.direction > FX_DIR_BOUNCE) c.direction = FX_DIR_FWD;
    if (c.envelope > FX_ENV_EDGE) c.envelope = FX_ENV_NONE;

    fx_lock();
    fx_inst_t *f = &s_fx[slot];
    // 同效果重发（调整参数）时保留基底与相位；阵列布局变了则重新捕获
    bool sameFx = f->cfg.running && f->cfg.fx_id == c.fx_id &&
                  f->cfg.count == c.count && f->cfg.stride == c.stride;
    if (sameFx) {
        f->cfg = c;
        f->cfg.running = true;
        fx_unlock();
        return;
    }
    fx_unlock();

    memset(&tmp, 0, sizeof(tmp));
    fx_capture_bases(&tmp, &c);
    tmp.cfg = c;
    tmp.cfg.running = true;
    tmp.phase = 0;
    tmp.step_tick = 0;
    tmp.step = 0;
    tmp.blade_on = false;
    tmp.in_loop_gap = false;

    fx_lock();
    s_fx[slot] = tmp;
    fx_unlock();
}

void fx_stop(uint8_t slot)
{
    if (slot >= FX_MAX_COUNT) return;
    fx_lock();
    s_fx[slot].cfg.running = false;
    s_fx[slot].cfg.fx_id = 0;
    fx_unlock();
}

void fx_stop_all(void)
{
    fx_lock();
    for (int i = 0; i < FX_MAX_COUNT; i++) {
        s_fx[i].cfg.running = false;
        s_fx[i].cfg.fx_id = 0;
    }
    fx_unlock();
}

bool fx_is_running(uint8_t slot)
{
    if (slot >= FX_MAX_COUNT) return false;
    fx_lock();
    bool r = s_fx[slot].cfg.running;
    fx_unlock();
    return r;
}

int fx_running_count(void)
{
    fx_lock();
    int n = 0;
    for (int i = 0; i < FX_MAX_COUNT; i++)
        if (s_fx[i].cfg.running) n++;
    fx_unlock();
    return n;
}

uint8_t fx_active_id(uint8_t slot)
{
    if (slot >= FX_MAX_COUNT) return 0;
    fx_lock();
    uint8_t r = s_fx[slot].cfg.running ? s_fx[slot].cfg.fx_id : 0;
    fx_unlock();
    return r;
}

bool fx_get_info(uint8_t slot, uint8_t *fx_id, uint16_t *amp16, uint16_t *speed)
{
    if (slot >= FX_MAX_COUNT) return false;
    fx_lock();
    bool run = s_fx[slot].cfg.running && s_fx[slot].cfg.fx_id != 0;
    if (run) {
        if (fx_id) *fx_id = s_fx[slot].cfg.fx_id;
        if (amp16) *amp16 = s_fx[slot].cfg.amp16;
        if (speed) *speed = s_fx[slot].cfg.speed;
    }
    fx_unlock();
    return run;
}

/** 某属性阵列是否覆盖通道 ch（O(1)：等差判定，不做逐台遍历） */
static inline bool attr_owns(uint16_t base, uint16_t fine, uint16_t ch,
                             uint16_t count, uint16_t stride)
{
    if (base == 0) return false;
    if (ch >= base) {
        uint16_t d = ch - base;
        if (stride == 0) { if (d == 0) return true; }
        else if (d / stride < count && d % stride == 0) return true;
    }
    if (fine) {
        if (ch >= fine) {
            uint16_t d = ch - fine;
            if (stride == 0) { if (d == 0) return true; }
            else if (d / stride < count && d % stride == 0) return true;
        }
    }
    return false;
}

bool fx_owns_channel(uint16_t ch)
{
    bool owned = false;
    fx_lock();
    for (int i = 0; i < FX_MAX_COUNT && !owned; i++) {
        fx_inst_t *f = &s_fx[i];
        const fx_cfg_t *c = &f->cfg;
        if (!c->running || c->fx_id == 0) continue;
        uint16_t n = c->count ? c->count : 1;
        uint16_t st = c->stride;
        switch (c->fx_id) {
        case 1:
            owned = attr_owns(c->pan_ch, c->pan_fine_ch, ch, n, st) ||
                    attr_owns(c->tilt_ch, c->tilt_fine_ch, ch, n, st);
            break;
        case 2:
            owned = attr_owns(c->pan_ch, c->pan_fine_ch, ch, n, st);
            break;
        case 3:
            owned = attr_owns(c->tilt_ch, c->tilt_fine_ch, ch, n, st);
            break;
        case 4:
            owned = attr_owns(c->dim_ch, c->dim_fine_ch, ch, n, st);
            break;
        case 5:
            owned = attr_owns(c->r_ch, 0, ch, n, st) ||
                    attr_owns(c->g_ch, 0, ch, n, st) ||
                    attr_owns(c->b_ch, 0, ch, n, st);
            break;
        case 6:
            owned = attr_owns(c->zoom_ch, c->zoom_fine_ch, ch, n, st);
            break;
        case 7:
            owned = attr_owns(c->focus_ch, c->focus_fine_ch, ch, n, st);
            break;
        case 8:
            owned = attr_owns(c->color_ch, 0, ch, n, st);
            break;
        case 9:
            owned = attr_owns(c->gobo_ch, 0, ch, n, st);
            break;
        case 10:
            owned = attr_owns(c->gobo_rot_ch, 0, ch, n, st);
            break;
        case 11:
            owned = attr_owns(c->gobo_ch, 0, ch, n, st) ||
                    attr_owns(c->gobo_rot_ch, 0, ch, n, st);
            break;
        case 13:
            for (int b = 0; b < FX_BLADE_COUNT && !owned; b++)
                owned = attr_owns(c->blade_ch[b], 0, ch, n, st);
            if (!owned) owned = attr_owns(c->shaper_rot_ch, 0, ch, n, st);
            break;
        default:
            break;
        }
    }
    fx_unlock();
    return owned;
}

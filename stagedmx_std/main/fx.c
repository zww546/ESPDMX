/**
 * 板载效果引擎 — 独立层叠加，离线运行。
 * 每个效果在自己的 tick 里：
 *   1. 用效果启动时捕获的通道值作为“基底”（程序/推杆的值）
 *   2. 计算正弦偏移，写回 dmx_state（覆盖该通道）
 * 效果停止时不动基底值，因此与程序播放不冲突。
 * 效果写在 core1，10ms tick；正弦表 256 项（sin(0..2π)*127）。
 *
 * v2：支持 fine 通道（16bit 精细运动），幅度为 16bit 偏移。
 * v3：速度改用 8.8 定点相位累加器（值越大越快），消除慢速下整数步进被截断的问题。
 * v4：基底只在启动时捕获一次（不再每 tick 回读 dmx_state），
 *     修复效果输出污染基底导致的累积漂移、幅度失效、低速卡在边界的问题。
 */
#include "fx.h"
#include "dmx_state.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "esp_log.h"

static const char *TAG = "fx";

#define FX_TICK_MS    10
#define PHASE_COUNT   256

typedef struct {
    fx_cfg_t cfg;
    uint32_t  phase;      // 8.8 定点相位累加器：高 8 位 = 正弦表索引
    // 16bit 基底值（coarse<<8|fine）
    uint16_t base_pan, base_tilt, base_dim;
    uint8_t  base_r, base_g, base_b;
    // v4：新增属性通道基底
    uint16_t base_zoom, base_focus;
    uint8_t  base_color, base_gobo, base_gobo_rot;
    // v5：切割片效果（fx_id=13）时序状态
    uint32_t step_tick;    // 当前步已走 tick 数
    uint8_t  step;         // 当前步索引（0..N-1 切割片，N=循环间隔等待，等）
    bool     blade_on;     // 当前切割片是否处于拉满状态
    bool     in_loop_gap;  // 是否处于循环间隔等待
} fx_inst_t;

static fx_inst_t s_fx[FX_MAX_COUNT];
static SemaphoreHandle_t s_fx_mux = NULL;   // 保护 s_fx（BLE 写 vs fx_task 读）

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

static inline int clamp8(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
static inline int clamp16(int v) { return v < 0 ? 0 : (v > 65535 ? 65535 : v); }

/** 读 16bit 通道值（有 fine 则 coarse<<8|fine，否则 coarse<<8）。 */
static uint16_t get16(uint16_t ch, uint16_t fine_ch) {
    if (ch == 0) return 0;
    uint16_t coarse = dmx_state_get(ch);
    if (fine_ch) {
        return (uint16_t)((coarse << 8) | dmx_state_get(fine_ch));
    }
    return (uint16_t)(coarse << 8);
}

/** 写 16bit 通道值（有 fine 拆两字节，否则只写 coarse）。 */
static void set16(uint16_t ch, uint16_t fine_ch, uint16_t v) {
    if (ch == 0) return;
    if (fine_ch) {
        dmx_state_set(ch, (uint8_t)(v >> 8));
        dmx_state_set(fine_ch, (uint8_t)(v & 0xFF));
    } else {
        dmx_state_set(ch, (uint8_t)(v >> 8));
    }
}

void fx_set(uint8_t slot, const fx_cfg_t *cfg) {
    if (slot >= FX_MAX_COUNT) return;
    fx_lock();
    fx_inst_t *f = &s_fx[slot];
    // 同效果重发（调整幅度/速度）时保留原基底与相位，
    // 避免把效果自身的输出误当作基底（否则会产生累积漂移、幅度失效、低速时卡在边界）
    bool sameFx = f->cfg.running && f->cfg.fx_id == cfg->fx_id;
    if (!sameFx) {
        f->phase = 0;
        // 记录当前通道值作为基底（16bit）——仅启动/更换效果类型时捕获一次
        f->base_pan  = get16(cfg->pan_ch,  cfg->pan_fine_ch);
        f->base_tilt = get16(cfg->tilt_ch, cfg->tilt_fine_ch);
        f->base_dim  = get16(cfg->dim_ch,  cfg->dim_fine_ch);
        f->base_r = cfg->r_ch ? dmx_state_get(cfg->r_ch) : 0;
        f->base_g = cfg->g_ch ? dmx_state_get(cfg->g_ch) : 0;
        f->base_b = cfg->b_ch ? dmx_state_get(cfg->b_ch) : 0;
        f->base_zoom  = get16(cfg->zoom_ch,  cfg->zoom_fine_ch);
        f->base_focus = get16(cfg->focus_ch, cfg->focus_fine_ch);
        f->base_color     = cfg->color_ch     ? dmx_state_get(cfg->color_ch)     : 0;
        f->base_gobo      = cfg->gobo_ch      ? dmx_state_get(cfg->gobo_ch)      : 0;
        f->base_gobo_rot  = cfg->gobo_rot_ch  ? dmx_state_get(cfg->gobo_rot_ch)  : 0;
        // v5：切割效果启动时重置时序状态
        f->step_tick = 0;
        f->step = 0;
        f->blade_on = false;
        f->in_loop_gap = false;
    }
    f->cfg = *cfg;
    f->cfg.running = true;
    fx_unlock();
}

void fx_stop(uint8_t slot) {
    if (slot >= FX_MAX_COUNT) return;
    fx_lock();
    s_fx[slot].cfg.running = false;
    s_fx[slot].cfg.fx_id = 0;
    fx_unlock();
}

void fx_stop_all(void) {
    fx_lock();
    for (int i = 0; i < FX_MAX_COUNT; i++) {
        s_fx[i].cfg.running = false;
        s_fx[i].cfg.fx_id = 0;
    }
    fx_unlock();
}

bool fx_is_running(uint8_t slot) {
    if (slot >= FX_MAX_COUNT) return false;
    fx_lock();
    bool r = s_fx[slot].cfg.running;
    fx_unlock();
    return r;
}

int fx_running_count(void) {
    fx_lock();
    int n = 0;
    for (int i = 0; i < FX_MAX_COUNT; i++)
        if (s_fx[i].cfg.running) n++;
    fx_unlock();
    return n;
}

uint8_t fx_active_id(uint8_t slot) {
    if (slot >= FX_MAX_COUNT) return 0;
    fx_lock();
    uint8_t r = s_fx[slot].cfg.running ? s_fx[slot].cfg.fx_id : 0;
    fx_unlock();
    return r;
}

static void fx_tick(fx_inst_t *f) {
    const fx_cfg_t *c = &f->cfg;
    if (!c->running || c->fx_id == 0) return;
    // 基底 = 效果启动时捕获的值（fx_set），不做每 tick 刷新：
    // dmx_state 中该通道值已被效果自身写入，若回读会形成反馈漂移。
    // 速度：8.8 定点累加，高 8 位即正弦表索引（值越大越快）
    f->phase += (uint32_t)c->speed;
    uint8_t idx = (uint8_t)((f->phase >> 8) & 0xFF);
    int amp = c->amp16;

    switch (c->fx_id) {
    case 1: { // 圆形摇动: pan/tilt 正弦+余弦（16bit）
        if (c->pan_ch) {
            int o = SIN[idx] * amp / 127;
            set16(c->pan_ch, c->pan_fine_ch, (uint16_t)clamp16((int)f->base_pan + o));
        }
        if (c->tilt_ch) {
            int o = SIN[(idx + 64) & 0xFF] * amp / 127;
            set16(c->tilt_ch, c->tilt_fine_ch, (uint16_t)clamp16((int)f->base_tilt + o));
        }
        break;
    }
    case 2: { // 水平摇动
        if (c->pan_ch) {
            int o = SIN[idx] * amp / 127;
            set16(c->pan_ch, c->pan_fine_ch, (uint16_t)clamp16((int)f->base_pan + o));
        }
        break;
    }
    case 3: { // 垂直摇动
        if (c->tilt_ch) {
            int o = SIN[idx] * amp / 127;
            set16(c->tilt_ch, c->tilt_fine_ch, (uint16_t)clamp16((int)f->base_tilt + o));
        }
        break;
    }
    case 4: { // 频闪: dim 开/关（16bit 基底）
        if (c->dim_ch) {
            bool on = idx < (PHASE_COUNT / 2);
            set16(c->dim_ch, c->dim_fine_ch, on ? f->base_dim : 0);
        }
        break;
    }
    case 5: { // RGB 变色: 三通道 120° 相差正弦
        if (c->r_ch) dmx_state_set(c->r_ch, (uint8_t)clamp8((SIN[idx] + 127) * 255 / 254));
        if (c->g_ch) dmx_state_set(c->g_ch, (uint8_t)clamp8((SIN[(idx + 85) & 0xFF] + 127) * 255 / 254));
        if (c->b_ch) dmx_state_set(c->b_ch, (uint8_t)clamp8((SIN[(idx + 170) & 0xFF] + 127) * 255 / 254));
        break;
    }
    case 6: { // 放大摆动: zoom 正弦
        if (c->zoom_ch) {
            int o = SIN[idx] * amp / 127;
            set16(c->zoom_ch, c->zoom_fine_ch, (uint16_t)clamp16((int)f->base_zoom + o));
        }
        break;
    }
    case 7: { // 调焦摆动: focus 正弦
        if (c->focus_ch) {
            int o = SIN[idx] * amp / 127;
            set16(c->focus_ch, c->focus_fine_ch, (uint16_t)clamp16((int)f->base_focus + o));
        }
        break;
    }
    case 8: { // 色盘摆动: color 正弦（色盘来回走）
        if (c->color_ch) {
            int o = SIN[idx] * amp / 127;
            dmx_state_set(c->color_ch, (uint8_t)clamp8((int)f->base_color + o * 255 / 65535));
        }
        break;
    }
    case 9: { // 图案盘摆动: gobo 正弦（图案盘来回走）
        if (c->gobo_ch) {
            int o = SIN[idx] * amp / 127;
            dmx_state_set(c->gobo_ch, (uint8_t)clamp8((int)f->base_gobo + o * 255 / 65535));
        }
        break;
    }
    case 10: { // 图案盘自转: gobo_rot 正弦（旋转速度摆动）
        if (c->gobo_rot_ch) {
            int o = SIN[idx] * amp / 127;
            dmx_state_set(c->gobo_rot_ch, (uint8_t)clamp8((int)f->base_gobo_rot + o * 255 / 65535));
        }
        break;
    }
    case 11: { // 固定图案摇动: 图案盘固定在基底，gobo_rot 快速正弦抖动
        if (c->gobo_ch) dmx_state_set(c->gobo_ch, f->base_gobo);
        if (c->gobo_rot_ch) {
            int o = SIN[idx] * amp / 127;
            dmx_state_set(c->gobo_rot_ch, (uint8_t)clamp8((int)f->base_gobo_rot + o * 255 / 65535));
        }
        break;
    }
    case 13: { // 切割循环（时序分步）: 依次拉满→关闭每个切割片，最后切割旋转，循环
        // 每步时长(ms) = 655360 / speed（speed 越大越快）；循环间隔(ms) = amp16
        // 每 tick = 10ms
        uint32_t step_ticks = c->speed ? (655360u / c->speed) : 655360u;
        if (step_ticks < 1) step_ticks = 1;
        uint32_t gap_ticks = c->amp16 ? ((uint32_t)c->amp16 / FX_TICK_MS) : 0;

        // 收集存在的切割片通道（0=未用，跳过）
        uint16_t blades[FX_BLADE_COUNT];
        int nblade = 0;
        for (int i = 0; i < FX_BLADE_COUNT; i++) {
            if (c->blade_ch[i]) blades[nblade++] = c->blade_ch[i];
        }

        if (f->in_loop_gap) {
            // 阶段 C：循环间隔等待，所有通道归零
            for (int i = 0; i < nblade; i++) dmx_state_set(blades[i], 0);
            if (c->shaper_rot_ch) dmx_state_set(c->shaper_rot_ch, 0);
            f->step_tick++;
            if (gap_ticks == 0 || f->step_tick >= gap_ticks) {
                f->in_loop_gap = false;
                f->step_tick = 0;
                f->step = 0;      // 回到阶段 A 第 0 片
                f->blade_on = false;
            }
            break;
        }

        f->step_tick++;
        if (f->step_tick >= step_ticks) {
            f->step_tick = 0;
            // 阶段 A：依次处理每片切割片（step=0..nblade-1）
            if (f->step < nblade) {
                if (!f->blade_on) {
                    // 拉满当前片
                    dmx_state_set(blades[f->step], 255);
                    f->blade_on = true;
                } else {
                    // 关闭当前片，进入下一片
                    dmx_state_set(blades[f->step], 0);
                    f->blade_on = false;
                    f->step++;
                }
            } else if (f->step == nblade) {
                // 阶段 B：切割旋转 —— 拉满
                if (c->shaper_rot_ch) dmx_state_set(c->shaper_rot_ch, 255);
                f->blade_on = true;
                f->step = nblade + 1;
            } else if (f->step == nblade + 1) {
                // 阶段 B 完：归零，进入循环间隔
                if (c->shaper_rot_ch) dmx_state_set(c->shaper_rot_ch, 0);
                f->blade_on = false;
                f->in_loop_gap = true;
                f->step = 0;
            }
            break;
        }
        break;
    }
    }
}

// 判断某通道是否被任意运行中的效果占用（效果优先：程序不覆盖效果通道）
bool fx_owns_channel(uint16_t ch) {
    bool owned = false;
    fx_lock();
    for (int i = 0; i < FX_MAX_COUNT && !owned; i++) {
        fx_inst_t *f = &s_fx[i];
        const fx_cfg_t *c = &f->cfg;
        if (!c->running || c->fx_id == 0) continue;
        switch (c->fx_id) {
        case 5:  // RGB：只占 r/g/b
            owned = (c->r_ch && c->r_ch == ch) || (c->g_ch && c->g_ch == ch) || (c->b_ch && c->b_ch == ch);
            break;
        case 4:  // 频闪：只占 dim
            owned = (c->dim_ch && c->dim_ch == ch);
            break;
        case 11: // 固定图案摇动：占 gobo + gobo_rot
            owned = (c->gobo_ch && c->gobo_ch == ch) || (c->gobo_rot_ch && c->gobo_rot_ch == ch);
            break;
        case 13: // 切割循环：占所有切割片 + 切割旋转
            for (int b = 0; b < FX_BLADE_COUNT && !owned; b++) {
                if (c->blade_ch[b] && c->blade_ch[b] == ch) { owned = true; break; }
            }
            if (!owned && c->shaper_rot_ch && c->shaper_rot_ch == ch) owned = true;
            break;
        case 10: // 图案盘自转：占 gobo_rot
            owned = (c->gobo_rot_ch && c->gobo_rot_ch == ch);
            break;
        case 9:  // 图案盘摆动：占 gobo
            owned = (c->gobo_ch && c->gobo_ch == ch);
            break;
        case 8:  // 色盘摆动：占 color
            owned = (c->color_ch && c->color_ch == ch);
            break;
        case 7:  // 调焦摆动：占 focus
            owned = (c->focus_ch && c->focus_ch == ch);
            break;
        case 6:  // 放大摆动：占 zoom
            owned = (c->zoom_ch && c->zoom_ch == ch);
            break;
        default: // 摇动(1/2/3)：占 pan/tilt
            owned = (c->pan_ch && c->pan_ch == ch) || (c->tilt_ch && c->tilt_ch == ch);
            break;
        }
    }
    fx_unlock();
    return owned;
}

static void fx_task(void *arg) {
    while (1) {
        // 先取快照再计算，避免持锁过久阻塞 BLE；s_fx 读取在锁内复制 cfg
        fx_lock();
        for (int i = 0; i < FX_MAX_COUNT; i++) {
            fx_inst_t *f = &s_fx[i];
            if (f->cfg.running && f->cfg.fx_id != 0) fx_tick(f);
        }
        fx_unlock();
        vTaskDelay(pdMS_TO_TICKS(FX_TICK_MS));
    }
}

void fx_start_task(void) {
    if (!s_fx_mux) s_fx_mux = xSemaphoreCreateMutex();
    memset(s_fx, 0, sizeof(s_fx));
    xTaskCreatePinnedToCore(fx_task, "fx", 4096, NULL, 5, NULL, 1);
    ESP_LOGI(TAG, "fx engine v3 started (%d slots, %dms tick, 16bit fine, fixed-point speed)",
             FX_MAX_COUNT, FX_TICK_MS);
}

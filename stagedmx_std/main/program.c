/**
 * 多程序并行播放引擎 — 稀疏存储 + HTP 合并输出。
 * 每步只记录动过的通道 (ch,val)，播放时只覆盖这些通道，其余保持当前值。
 * 多程序并行时对同一通道取 HTP（最大值），互不冲突。
 *
 * 并发：BLE host task（写）与 prog_task（读）通过互斥量保护 s_progs。
 */
#include "program.h"
#include "dmx_state.h"
#include "fx.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "esp_log.h"
#include <string.h>

static const char *TAG = "prog";

typedef struct {
    char          name[PROG_NAME_LEN];
    prog_step_t   steps[PROG_MAX_STEPS];
    int           count;
    volatile bool playing;
    volatile bool loop;
    volatile int  step_idx;
    volatile int  elapsed;
} program_t;

static program_t s_progs[PROG_MAX_COUNT];
static SemaphoreHandle_t s_prog_mux = NULL;   // 保护 s_progs 的写/读

static void prog_lock(void)   { if (s_prog_mux) xSemaphoreTake(s_prog_mux, portMAX_DELAY); }
static void prog_unlock(void) { if (s_prog_mux) xSemaphoreGive(s_prog_mux); }

void program_clear(uint8_t prog_id) {
    if (prog_id >= PROG_MAX_COUNT) return;
    prog_lock();
    s_progs[prog_id].playing = false;
    s_progs[prog_id].count = 0;
    snprintf(s_progs[prog_id].name, PROG_NAME_LEN, "Prog%d", prog_id);
    prog_unlock();
}

void program_append(uint8_t prog_id, uint16_t time_ms,
                    const prog_item_t *items, uint8_t count) {
    if (prog_id >= PROG_MAX_COUNT) return;
    prog_lock();
    program_t *p = &s_progs[prog_id];
    if (p->count >= PROG_MAX_STEPS) { prog_unlock(); return; }
    if (count > PROG_MAX_ITEMS_STEP) count = PROG_MAX_ITEMS_STEP;
    if (count == 0) { prog_unlock(); return; }
    prog_step_t *s = &p->steps[p->count];
    s->count = count;
    memcpy(s->items, items, sizeof(prog_item_t) * count);
    s->time_ms = time_ms < 20 ? 20 : time_ms;
    p->count++;
    prog_unlock();
}

int program_step_count(uint8_t prog_id) {
    if (prog_id >= PROG_MAX_COUNT) return 0;
    prog_lock();
    int n = s_progs[prog_id].count;
    prog_unlock();
    return n;
}

void program_play(uint8_t prog_id, bool loop) {
    if (prog_id >= PROG_MAX_COUNT) return;
    prog_lock();
    program_t *p = &s_progs[prog_id];
    if (p->count == 0) { prog_unlock(); return; }
    p->step_idx = 0; p->elapsed = 0;
    p->loop = loop; p->playing = true;
    prog_unlock();
}

void program_stop(uint8_t prog_id) {
    if (prog_id >= PROG_MAX_COUNT) return;
    prog_lock();
    s_progs[prog_id].playing = false;
    prog_unlock();
}

void program_stop_all(void) {
    prog_lock();
    for (int i = 0; i < PROG_MAX_COUNT; i++) s_progs[i].playing = false;
    prog_unlock();
}

bool program_is_playing(uint8_t prog_id) {
    if (prog_id >= PROG_MAX_COUNT) return false;
    prog_lock();
    bool b = s_progs[prog_id].playing;
    prog_unlock();
    return b;
}

int program_playing_count(void) {
    prog_lock();
    int n = 0;
    for (int i = 0; i < PROG_MAX_COUNT; i++)
        if (s_progs[i].playing) n++;
    prog_unlock();
    return n;
}

const char* program_name(uint8_t prog_id) {
    if (prog_id >= PROG_MAX_COUNT) return "?";
    prog_lock();
    const char *n = s_progs[prog_id].name;
    prog_unlock();
    return n;
}

// ---- HTP 合并任务 ----
// 用“层”模型：先以当前 dmx_state 为基底，再把每个活跃程序的稀疏步
// 逐通道 HTP(取大) 写回。没被程序覆盖的通道保持推杆/场景当前值。
static void prog_task(void *arg) {
    uint8_t merged[1 + 512];   // [0]=起始码 + 512 通道（与 dmx_state_copy_frame 对齐）
    while (1) {
        dmx_state_copy_frame(merged);   // 基底 = 当前推杆/场景值
        bool any = false;
        prog_lock();   // 锁住 s_progs，避免 BLE 写入时读到半状态
        for (int i = 0; i < PROG_MAX_COUNT; i++) {
            program_t *p = &s_progs[i];
            if (!p->playing || p->count == 0) continue;
            any = true;
            int idx = p->step_idx % p->count;
            const prog_step_t *s = &p->steps[idx];
            for (int k = 0; k < s->count; k++) {
                uint16_t ch = s->items[k].ch;   // 1-based
                uint8_t  v  = s->items[k].val;
                if (ch >= 1 && ch <= 512 && v > merged[ch - 1]) {
                    // 效果优先：被效果占用的通道（pan/tilt/dim/rgb）程序不覆盖
                    if (fx_owns_channel(ch)) continue;
                    merged[ch - 1] = v;
                }
            }
            p->elapsed += 10;
            if (p->elapsed >= (int)s->time_ms) {
                p->elapsed = 0;
                if (++p->step_idx >= p->count) {
                    if (p->loop) p->step_idx = 0;
                    else { p->playing = false; p->step_idx = 0; }
                }
            }
        }
        prog_unlock();
        if (any) dmx_state_set_frame(merged);
        vTaskDelay(pdMS_TO_TICKS(10));
    }
}

void program_start_task(void) {
    if (!s_prog_mux) s_prog_mux = xSemaphoreCreateMutex();
    prog_lock();
    for (int i = 0; i < PROG_MAX_COUNT; i++) {
        memset(&s_progs[i], 0, sizeof(program_t));
        snprintf(s_progs[i].name, PROG_NAME_LEN, "Prog%d", i);
    }
    prog_unlock();
    xTaskCreatePinnedToCore(prog_task, "program", 4096, NULL, 5, NULL, 1);
    ESP_LOGI(TAG, "multi-program sparse HTP engine (max %d prog x %d steps x %d items)",
             PROG_MAX_COUNT, PROG_MAX_STEPS, PROG_MAX_ITEMS_STEP);
}

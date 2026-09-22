/**
 * 多程序并行播放引擎 — 稀疏存储 + HTP 合并。
 * 每步只记录动过的通道 (ch,val)，播放时只覆盖这些通道，其余保持当前值。
 * 多程序并行时对同一通道取 HTP（最大值），互不冲突。
 *
 * v6：
 *   - 不再自己开任务，也不直接写 dmx_state；改为渲染管线的一层：
 *     由 render.c 每 10ms 调用 program_render(buf)，在整帧缓冲上做 HTP 合并并推进步进。
 *   - **修掉旧版的 off-by-one**：旧代码用 `merged[ch-1]` 索引一个 [起始码 + 512通道]
 *     的缓冲，等于把每个通道值都写早了一个通道（通道 512 永远写不到，通道 1 写进起始码位）。
 *     现在 buf 是纯通道数组（buf[0] = 全局通道 1），索引 `buf[ch-1]` 才是对的。
 *
 * 并发：BLE host task（写 s_progs）与渲染任务（读 s_progs）通过互斥量保护。
 */
#include "program.h"
#include "dmx_state.h"
#include "fx.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "esp_log.h"
#include "esp_heap_caps.h"
#include <string.h>

static const char *TAG = "prog";

#define PROG_TICK_MS 10

typedef struct {
    char          name[PROG_NAME_LEN];
    prog_step_t   steps[PROG_MAX_STEPS];
    int           count;
    volatile bool playing;
    volatile bool loop;
    volatile int  step_idx;
    volatile int  elapsed;
} program_t;

/**
 * 程序表放在 **PSRAM**。
 *
 * PROG_MAX_ITEMS_STEP=255 时每程序约 64KB（8 程序 ≈ 514KB），内部 RAM 放不下；
 * 板载 8MB PSRAM，且 PSRAM 已启用 CAPS_ALLOC。渲染任务每 tick 只读"当前步"的
 * 若干条记录，PSRAM 的访问延迟对性能没有影响（DMX ISR 完全不碰这里）。
 */
static program_t *s_progs = NULL;
static SemaphoreHandle_t s_prog_mux = NULL;

static void prog_lock(void)   { if (s_prog_mux) xSemaphoreTake(s_prog_mux, portMAX_DELAY); }
static void prog_unlock(void) { if (s_prog_mux) xSemaphoreGive(s_prog_mux); }

/**
 * 统一的参数校验：程序表是否已分配 + prog_id 是否合法。
 * 程序表在 PSRAM，分配失败时（极罕见）所有接口都退化为空操作，绝不空指针崩溃。
 */
#define PROG_OK(id)  (s_progs != NULL && (id) < PROG_MAX_COUNT)
#define PROG_ANY()   (s_progs != NULL)

void program_clear(uint8_t prog_id)
{
    if (!PROG_OK(prog_id)) return;
    prog_lock();
    s_progs[prog_id].playing = false;
    s_progs[prog_id].count = 0;
    snprintf(s_progs[prog_id].name, PROG_NAME_LEN, "Prog%d", prog_id);
    prog_unlock();
}

void program_append(uint8_t prog_id, uint16_t time_ms,
                    const prog_item_t *items, uint8_t count)
{
    if (!PROG_OK(prog_id)) return;
    prog_lock();
    program_t *p = &s_progs[prog_id];
    if (p->count >= PROG_MAX_STEPS) { prog_unlock(); return; }
    // 不用再钳 count：它的类型是 uint8_t（≤255），而 PROG_MAX_ITEMS_STEP 正好是 255。
    // 原来那句 `if (count > PROG_MAX_ITEMS_STEP) count = ...` 编译器直接报
    // "comparison is always false" —— 留着反而让人以为上限是别的值。
    if (count == 0) { prog_unlock(); return; }
    prog_step_t *s = &p->steps[p->count];
    s->count = count;
    memcpy(s->items, items, sizeof(prog_item_t) * count);
    s->time_ms = time_ms < 20 ? 20 : time_ms;
    p->count++;
    prog_unlock();
}

int program_step_count(uint8_t prog_id)
{
    if (!PROG_OK(prog_id)) return 0;
    prog_lock();
    int n = s_progs[prog_id].count;
    prog_unlock();
    return n;
}

void program_play(uint8_t prog_id, bool loop)
{
    if (!PROG_OK(prog_id)) return;
    prog_lock();
    program_t *p = &s_progs[prog_id];
    if (p->count == 0) { prog_unlock(); return; }
    p->step_idx = 0; p->elapsed = 0;
    p->loop = loop; p->playing = true;
    prog_unlock();
}

void program_stop(uint8_t prog_id)
{
    if (!PROG_OK(prog_id)) return;
    prog_lock();
    s_progs[prog_id].playing = false;
    prog_unlock();
}

void program_stop_all(void)
{
    if (!PROG_ANY()) return;
    prog_lock();
    for (int i = 0; i < PROG_MAX_COUNT; i++) s_progs[i].playing = false;
    prog_unlock();
}

bool program_is_playing(uint8_t prog_id)
{
    if (!PROG_OK(prog_id)) return false;
    prog_lock();
    bool b = s_progs[prog_id].playing;
    prog_unlock();
    return b;
}

int program_playing_count(void)
{
    if (!PROG_ANY()) return 0;
    prog_lock();
    int n = 0;
    for (int i = 0; i < PROG_MAX_COUNT; i++)
        if (s_progs[i].playing) n++;
    prog_unlock();
    return n;
}

const char* program_name(uint8_t prog_id)
{
    if (!PROG_OK(prog_id)) return "?";
    prog_lock();
    const char *n = s_progs[prog_id].name;
    prog_unlock();
    return n;
}

// ---- 渲染层：HTP 合并 + 步进推进 ----

void program_render(uint8_t *buf)
{
    if (!PROG_ANY()) return;
    prog_lock();   // 锁住 s_progs，避免 BLE 写入时读到半状态
    for (int i = 0; i < PROG_MAX_COUNT; i++) {
        program_t *p = &s_progs[i];
        if (!p->playing || p->count == 0) continue;
        int idx = p->step_idx;
        if (idx < 0 || idx >= p->count) idx = 0;
        const prog_step_t *s = &p->steps[idx];
        for (int k = 0; k < s->count; k++) {
            uint16_t ch = s->items[k].ch;   // 1-based 全局通道
            uint8_t  v  = s->items[k].val;
            if (ch < 1 || ch > DMX_CHANNELS) continue;
            // 效果优先：被效果占用的通道，程序不覆盖
            if (fx_owns_channel(ch)) continue;
            if (v > buf[ch - 1]) buf[ch - 1] = v;   // HTP 取大
        }
        p->elapsed += PROG_TICK_MS;
        if (p->elapsed >= (int)s->time_ms) {
            p->elapsed = 0;
            if (++p->step_idx >= p->count) {
                if (p->loop) p->step_idx = 0;
                else { p->playing = false; p->step_idx = 0; }
            }
        }
    }
    prog_unlock();
}

void program_init(void)
{
    if (!s_prog_mux) s_prog_mux = xSemaphoreCreateMutex();
    if (!s_progs) {
        s_progs = (program_t *)heap_caps_malloc(sizeof(program_t) * PROG_MAX_COUNT,
                                                MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
        if (!s_progs) {
            ESP_LOGE(TAG, "PSRAM 分配失败（需要 %u 字节），程序功能不可用",
                     (unsigned)(sizeof(program_t) * PROG_MAX_COUNT));
            return;
        }
    }
    prog_lock();
    for (int i = 0; i < PROG_MAX_COUNT; i++) {
        memset(&s_progs[i], 0, sizeof(program_t));
        snprintf(s_progs[i].name, PROG_NAME_LEN, "Prog%d", i);
    }
    prog_unlock();
    ESP_LOGI(TAG, "multi-program sparse HTP engine (max %d prog x %d steps x %d items, %u KB in PSRAM)",
             PROG_MAX_COUNT, PROG_MAX_STEPS, PROG_MAX_ITEMS_STEP,
             (unsigned)(sizeof(program_t) * PROG_MAX_COUNT / 1024));
}

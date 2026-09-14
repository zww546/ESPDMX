#include "dmx_state.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include <string.h>

// 纯通道数据：s_ch[0] = 全局通道 1，s_ch[1023] = 全局通道 1024
static uint8_t s_ch[DMX_CHANNELS];
static portMUX_TYPE s_mux = portMUX_INITIALIZER_UNLOCKED;
static volatile bool s_dirty = false;
static volatile uint32_t s_wr_ver = 0;   // 外部写入版本号（渲染管线乐观并发用）

void dmx_state_init(void)
{
    portENTER_CRITICAL(&s_mux);
    memset(s_ch, 0, sizeof(s_ch));
    portEXIT_CRITICAL(&s_mux);
    s_dirty = true;
}

bool dmx_state_set_range(uint16_t start, const uint8_t *values, uint16_t count)
{
    if (start < 1 || count == 0) return false;
    if (start > DMX_CHANNELS) return false;
    if (start + count - 1 > DMX_CHANNELS) {
        count = DMX_CHANNELS - start + 1; // 裁剪
    }
    bool changed = false;
    uint16_t off = start - 1;
    portENTER_CRITICAL(&s_mux);
    for (uint16_t i = 0; i < count; i++) {
        if (s_ch[off + i] != values[i]) {
            s_ch[off + i] = values[i];
            changed = true;
        }
    }
    // ⚠ 版本号必须与数据在**同一个临界区**内自增。
    //   放到 portEXIT_CRITICAL 之后会留下一个窗口：数据已写入、版本号还没加，
    //   此时渲染任务的 snapshot→commit_expect 会看到"版本未变"而提交，
    //   把刚到的推子值覆盖掉（现场表现为"松手后值不对"）——正是乐观并发要防的事。
    if (changed) { s_dirty = true; s_wr_ver++; }
    portEXIT_CRITICAL(&s_mux);
    return changed;
}

void dmx_state_set_all(uint8_t v)
{
    portENTER_CRITICAL(&s_mux);
    memset(s_ch, v, DMX_CHANNELS);
    s_dirty = true;
    s_wr_ver++;          // 同上：与数据同临界区
    portEXIT_CRITICAL(&s_mux);
}

uint8_t dmx_state_get(uint16_t ch)
{
    if (ch < 1 || ch > DMX_CHANNELS) return 0;
    return s_ch[ch - 1];
}

void dmx_state_set(uint16_t ch, uint8_t v)
{
    if (ch < 1 || ch > DMX_CHANNELS) return;
    portENTER_CRITICAL(&s_mux);
    if (s_ch[ch - 1] != v) {
        s_ch[ch - 1] = v;
        s_dirty = true;
        s_wr_ver++;              // 与数据同临界区（见 set_range 的说明）
    }
    portEXIT_CRITICAL(&s_mux);
}

// ---- 整帧提交 ----

void dmx_state_snapshot(uint8_t *out)
{
    portENTER_CRITICAL(&s_mux);
    memcpy(out, s_ch, DMX_CHANNELS);
    portEXIT_CRITICAL(&s_mux);
}

void dmx_state_commit(const uint8_t *in)
{
    portENTER_CRITICAL(&s_mux);
    memcpy(s_ch, in, DMX_CHANNELS);
    s_dirty = true;
    portEXIT_CRITICAL(&s_mux);
}

uint32_t dmx_state_write_version(void)
{
    return s_wr_ver;
}

bool dmx_state_commit_expect(const uint8_t *in, uint32_t expect)
{
    portENTER_CRITICAL(&s_mux);
    if (s_wr_ver != expect) {          // 期间有推子写入 → 本次合成结果过期
        portEXIT_CRITICAL(&s_mux);
        return false;
    }
    memcpy(s_ch, in, DMX_CHANNELS);
    portEXIT_CRITICAL(&s_mux);
    s_dirty = true;
    return true;
}

// ---- 给某个 DMX 口取一帧（起始码 + 512 通道）----

void dmx_state_copy_port_frame(uint8_t *out, uint8_t universe)
{
    if (universe >= DMX_UNIVERSES) universe = 0;
    uint16_t off = (uint16_t)universe * DMX_UNIVERSE_SIZE;
    portENTER_CRITICAL(&s_mux);
    out[0] = 0x00; // DMX start code
    memcpy(&out[1], &s_ch[off], DMX_UNIVERSE_SIZE);
    portEXIT_CRITICAL(&s_mux);
}

#include "dmx_state.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include <string.h>

// [0] = start code (0x00), [1..512] = channels
static uint8_t s_frame[1 + DMX_CHANNELS];
static portMUX_TYPE s_mux = portMUX_INITIALIZER_UNLOCKED;
static volatile bool s_dirty = false;

void dmx_state_init(void)
{
    portENTER_CRITICAL(&s_mux);
    memset(s_frame, 0, sizeof(s_frame));
    s_frame[0] = 0x00; // DMX start code
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
    portENTER_CRITICAL(&s_mux);
    for (uint16_t i = 0; i < count; i++) {
        if (s_frame[start + i] != values[i]) {
            s_frame[start + i] = values[i];
            changed = true;
        }
    }
    portEXIT_CRITICAL(&s_mux);
    if (changed) s_dirty = true;
    return changed;
}

void dmx_state_set_all(uint8_t v)
{
    portENTER_CRITICAL(&s_mux);
    memset(&s_frame[1], v, DMX_CHANNELS);
    portEXIT_CRITICAL(&s_mux);
    s_dirty = true;
}

void dmx_state_set_frame(const uint8_t *vals)
{
    portENTER_CRITICAL(&s_mux);
    memcpy(&s_frame[1], vals, DMX_CHANNELS);
    portEXIT_CRITICAL(&s_mux);
    s_dirty = true;
}

void dmx_state_copy_frame(uint8_t *out)
{
    portENTER_CRITICAL(&s_mux);
    memcpy(out, s_frame, sizeof(s_frame));
    portEXIT_CRITICAL(&s_mux);
}

uint8_t dmx_state_get(uint16_t ch)
{
    if (ch < 1 || ch > DMX_CHANNELS) return 0;
    return s_frame[ch];
}

void dmx_state_set(uint16_t ch, uint8_t v)
{
    if (ch < 1 || ch > DMX_CHANNELS) return;
    portENTER_CRITICAL(&s_mux);
    if (s_frame[ch] != v) {
        s_frame[ch] = v;
        portEXIT_CRITICAL(&s_mux);
        s_dirty = true;
    } else {
        portEXIT_CRITICAL(&s_mux);
    }
}

bool dmx_state_take_dirty(void)
{
    if (s_dirty) { s_dirty = false; return true; }
    return false;
}

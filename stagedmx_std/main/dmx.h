#pragma once
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

void dmx_start(void);
void dmx_force_sync(void);
uint32_t dmx_tx_frames(void);
bool     dmx_tx_ok(void);
uint32_t dmx_tx_heals(void);
bool     dmx_tx_installed(void);
bool     dmx_tx_enabled(void);
uint32_t dmx_tx_baud(void);

#ifdef __cplusplus
}
#endif

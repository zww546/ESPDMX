#pragma once
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

void ble_dmx_init(void);
bool ble_dmx_is_connected(void);
const char *ble_dmx_name(void);

/** 通过 BLE notify 特征(0xFF02)发送数据到手机App。仅在已连接时有效。 */
void ble_dmx_notify(const uint8_t *data, uint16_t len);

#ifdef __cplusplus
}
#endif

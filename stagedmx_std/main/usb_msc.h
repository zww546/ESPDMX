#pragma once
#include <stdbool.h>

/** 启动 USB MSC U盘模式（ESP32-S3 作为 USB 存储设备）。*/
void usb_msc_start(void);

/** 停止 USB MSC U盘模式。*/
void usb_msc_stop(void);

/** MSC 是否已启用。*/
bool usb_msc_is_active(void);

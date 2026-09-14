#pragma once
// 日志桩：直接打到 stdout（测试时要看波形就把它打开）
#include <stdio.h>

#define ESP_LOGI(tag, fmt, ...) do { (void)(tag); } while (0)
#define ESP_LOGW(tag, fmt, ...) do { (void)(tag); } while (0)
#define ESP_LOGE(tag, fmt, ...) do { (void)(tag); } while (0)

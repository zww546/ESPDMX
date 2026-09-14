#pragma once
// 宿主端测试用的最小 FreeRTOS 桩（只为让真实 fx.c / dmx_state.c 能编译运行）
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

typedef int BaseType_t;
typedef unsigned int UBaseType_t;
typedef uint32_t TickType_t;

typedef struct { int dummy; } portMUX_TYPE;
#define portMUX_INITIALIZER_UNLOCKED { 0 }
#define portENTER_CRITICAL(m)   do { (void)(m); } while (0)
#define portEXIT_CRITICAL(m)    do { (void)(m); } while (0)

#define portMAX_DELAY       0xFFFFFFFFu
#define pdTRUE              1
#define pdFALSE             0
#define pdPASS              1
#define pdMS_TO_TICKS(ms)   ((TickType_t)(ms))
#define portTICK_PERIOD_MS  1

// 宿主端测试共用的 FreeRTOS 桩实现（供 test_fx.c / test_render.c 链接）
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "esp_heap_caps.h"
#include <stdlib.h>

SemaphoreHandle_t xSemaphoreCreateMutex(void) { return malloc(4); }
BaseType_t xSemaphoreTake(SemaphoreHandle_t s, TickType_t w) { (void)s; (void)w; return pdTRUE; }
BaseType_t xSemaphoreGive(SemaphoreHandle_t s) { (void)s; return pdTRUE; }
void vTaskDelay(TickType_t t) { (void)t; }
void vTaskDelete(TaskHandle_t t) { (void)t; }

// 固件里程序表分配在 PSRAM；宿主端直接用普通堆
void *heap_caps_malloc(size_t size, uint32_t caps) { (void)caps; return malloc(size); }

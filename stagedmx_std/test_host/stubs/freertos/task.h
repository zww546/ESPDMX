#pragma once
#include "FreeRTOS.h"

typedef void *TaskHandle_t;

void vTaskDelay(TickType_t ticks);
void vTaskDelete(TaskHandle_t t);

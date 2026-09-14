#pragma once
// 宿主端桩：只提供 program.c 用到的 PSRAM 分配接口
#include <stddef.h>
#include <stdint.h>

#define MALLOC_CAP_SPIRAM   (1 << 10)
#define MALLOC_CAP_8BIT     (1 << 2)

void *heap_caps_malloc(size_t size, uint32_t caps);

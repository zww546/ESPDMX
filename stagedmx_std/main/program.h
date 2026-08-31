#pragma once
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// 多程序并行播放引擎（稀疏存储版）。
// 专业控台模型：每步只记录“动过的通道”(ch,val) 列表，播放时只覆盖这些通道，
// 其余通道保持当前值 → 多个程序各管各的通道，互不冲突。
// 最多 4 个程序，每个最多 64 步，每步最多 32 个通道项，可同时播放多个。
// DMX 输出 = 所有活跃程序的 HTP（取最大值）合并。

#define PROG_MAX_COUNT       8
#define PROG_MAX_STEPS       64
#define PROG_MAX_ITEMS_STEP  64      // 每步最多记录的通道项数（一步变化通道数上限）
#define PROG_NAME_LEN        16

typedef struct {
    uint16_t ch;    // 1-based DMX 通道
    uint8_t  val;   // 通道值 0..255
} prog_item_t;

typedef struct {
    prog_item_t items[PROG_MAX_ITEMS_STEP];
    uint8_t     count;          // 本步通道项数
    uint16_t    time_ms;        // ≥20
} prog_step_t;

void program_start_task(void);

// ---- 程序管理 ----
void program_clear(uint8_t prog_id);                    // 清空指定程序
// 存一步（稀疏）：count 个 (ch,val) 项
void program_append(uint8_t prog_id, uint16_t time_ms,
                    const prog_item_t *items, uint8_t count);
int  program_step_count(uint8_t prog_id);               // 步数

// ---- 播放控制 ----
void program_play(uint8_t prog_id, bool loop);          // 开始播放（可指定是否循环）
void program_stop(uint8_t prog_id);                     // 停止
void program_stop_all(void);                            // 全部停止
bool program_is_playing(uint8_t prog_id);
int  program_playing_count(void);                       // 正在播放的程序数

// ---- 查询 ----
const char* program_name(uint8_t prog_id);              // 程序名（默认 "Prog0"~"Prog3"）

#ifdef __cplusplus
}
#endif

#pragma once
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// 多程序并行播放引擎（稀疏存储版）。
// 专业控台模型：每步只记录“动过的通道”(ch,val) 列表，播放时只覆盖这些通道，
// 其余通道保持当前值 → 多个程序各管各的通道，互不冲突。
// 最多 8 个程序，每个最多 64 步，每步最多 255 个通道项（见 PROG_MAX_ITEMS_STEP），可同时播放多个。
// 输出 = 所有活跃程序的 HTP（取最大值）合并，再交给效果层。
//
// v6：播放逻辑改为**渲染管线的一层**（render.c 每 10ms 调一次 program_render(buf)），
//     不再自己开任务、不再直接写 dmx_state。
//     通道号是 1-based **全局通道**（1..512 = 宇宙1，513..1024 = 宇宙2）。

#define PROG_MAX_COUNT       8
#define PROG_MAX_STEPS       64
/**
 * 每步最多记录的通道项数。
 *
 * 255 = 协议上限（0x12 帧的 count 占 1 字节）。原来只有 64 且是**静默截断**：
 * 录制多台灯（如 5 台 × 20ch）时超出部分被悄悄丢弃，表现为"程序只驱动一部分灯"。
 *
 * 内存代价：prog_item_t 对齐后 4B → 每步约 1KB，8 程序 × 64 步 ≈ 514KB，
 * 内部 RAM 装不下，因此程序表用 PSRAM 分配（见 program.c），
 * 顺带把原来占用的 ~130KB 内部 RAM 让出来。
 */
#define PROG_MAX_ITEMS_STEP  255
#define PROG_NAME_LEN        16

typedef struct {
    uint16_t ch;    // 1-based 全局 DMX 通道
    uint8_t  val;   // 通道值 0..255
} prog_item_t;

typedef struct {
    prog_item_t items[PROG_MAX_ITEMS_STEP];
    uint8_t     count;          // 本步通道项数
    uint16_t    time_ms;        // ≥20
} prog_step_t;

// 初始化（创建互斥量、清空程序表）。由 render_start_task() 调用。
void program_init(void);

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

// ---- 渲染层（由 render.c 每 10ms 调用）----
// 把所有正在播放的程序按其当前步做 HTP 合并到整帧缓冲 buf（1024 字节，buf[0]=通道1）。
// 被效果占用的通道会被跳过（效果优先）。同时推进各程序的步进计时。
void program_render(uint8_t *buf);

// ---- 查询 ----
const char* program_name(uint8_t prog_id);              // 程序名（默认 "Prog0"~"Prog7"）

#ifdef __cplusplus
}
#endif

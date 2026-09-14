#pragma once
#ifdef __cplusplus
extern "C" {
#endif

// 渲染管线（v6）：一个 10ms 任务跑在 core 1，把"谁在驱动这些通道"各层合成一帧。
//
//   snapshot → 程序层(HTP) → 效果层(阵列波形) → commit
//
// 为什么这么做：
//   1. **整帧提交**：临界区从"每通道一次"降到"每 tick 两次"，给阵列效果留出性能余量；
//   2. **消除竞态**：以前程序任务和效果任务各自 read-modify-write dmx_state，
//      两个生产者会互相覆盖（程序那一层是把整帧写回去的）；
//   3. 专业控台也是这个模型：先算出这一帧，再输出。
//
// 与 DMX 输出任务的关系：输出任务（core 1, prio 6）只读 dmx_state，不受影响。
void render_start_task(void);

#ifdef __cplusplus
}
#endif

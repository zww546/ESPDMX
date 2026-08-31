#pragma once
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// 板载效果引擎 — 独立层，可离线运行（BLE 断开后继续）。
// 效果作为独立层叠加在程序/推杆之上：每个效果控制一组通道（如 PAN/TILT/DIM/RGB），
// 按正弦波形在“基底值”上做偏移；效果启停与程序播放互不冲突。
// 基底 = 效果启动时捕获的通道值（推子/程序当前值），效果运行期间不实时回读，
// 避免效果自身输出污染基底造成漂移；调整幅度/速度重发配置不会重置基底。
// 效果类型与 App FxEngine.presets 对齐：
//   1=圆形摇动  2=水平摇动  3=垂直摇动  4=频闪  5=RGB变色
//   6=放大摆动  7=调焦摆动  8=色盘摆动  9=图案盘摆动
//   10=图案盘自转  11=固定图案摇动
//
// v2：幅度用 16bit（0..65535 对应灯的全行程），支持 fine 通道（16bit 精细运动），
// 不同灯的行程差异由 App 端换算（角度→16bit 偏移），固件只做波形叠加。
// v4：基底启动时捕获一次；新增 zoom/focus/color/gobo/gobo_rot 通道与效果 6~11。

#define FX_MAX_COUNT  8      // 最多同时运行的效果数（跟随实例，固件内存允许）

typedef struct {
    uint8_t  fx_id;          // 1..11，0=空槽
    // 控制通道（1-based，0=未用）及对应 fine 通道（0=无 fine）
    uint16_t pan_ch,     pan_fine_ch;
    uint16_t tilt_ch,    tilt_fine_ch;
    uint16_t dim_ch,     dim_fine_ch;
    uint16_t r_ch, g_ch, b_ch;
    // v4：新增属性通道
    uint16_t zoom_ch,    zoom_fine_ch;
    uint16_t focus_ch,   focus_fine_ch;
    uint16_t color_ch;               // 色盘
    uint16_t gobo_ch;                // 图案盘
    uint16_t gobo_rot_ch;            // 图案盘旋转
    uint16_t amp16;          // 幅度 0..65535（0=不动，65535=全行程，峰值偏移）
    uint16_t speed;          // 速度 0..65535，越大越快：每 10ms tick 相位推进量×256（8.8 定点）
    volatile bool running;
} fx_cfg_t;

void fx_start_task(void);

// ---- 效果控制 ----
// 配置并启动一个效果槽（slot 0..3），覆盖同槽已有效果
void fx_set(uint8_t slot, const fx_cfg_t *cfg);
// 停止指定槽效果
void fx_stop(uint8_t slot);
void fx_stop_all(void);
// 查询
bool fx_is_running(uint8_t slot);
int  fx_running_count(void);
uint8_t fx_active_id(uint8_t slot);

// 某通道是否被运行中的效果占用（效果优先：程序合并时跳过这些通道）
bool fx_owns_channel(uint16_t ch);

#ifdef __cplusplus
}
#endif

#pragma once
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// ============================================================================
// RDM 控制器（ANSI E1.20）—— 扫描 / 读参数 / 改地址 / 识别
//
// 用 esp_dmx 组件自带的 controller API（rdm_discover_devices_simple、
// rdm_send_get_device_info、rdm_send_request…），物理层靠 UART 的 RTS 自动换向。
//
// ⚠ RDM 与 DMX 输出**共用同一条 A/B 线**，且是双向协议：
//   扫描期间该宇宙的 DMX 输出会被暂停（灯保持最后一帧），跑完自动恢复。
//   一次完整扫描（发现 + 逐台读参数）通常几百毫秒到数秒，取决于设备数量与响应速度。
//
// 设备参数表放在 PSRAM（板载 8MB），不占内部 RAM。
// ============================================================================

#define RDM_MAX_DEVICES     32      // 每个宇宙最多记录多少台
#define RDM_LABEL_LEN       33      // RDM 字符串参数最长 32 字节 + '\0'

/** 一台 RDM 设备的全部参数（GET 到的都放这里）。 */
typedef struct {
    uint8_t  uid[6];                    // 48bit UID：高 16bit 厂商 ID + 低 32bit 序号
    bool     valid;
    // ---- DEVICE_INFO (0x0060) ----
    uint16_t rdm_version;               // 协议版本，高字节次版本、低字节主版本
    uint16_t model_id;                  // 厂商内部型号 ID
    uint16_t product_category;          // 产品类别（E1.20 附录）
    uint32_t software_version_id;       // 软件版本 ID
    uint16_t footprint;                 // DMX 占用通道数
    uint8_t  personality;               // 当前模式号（1-based）
    uint8_t  personality_count;         // 模式总数
    uint16_t start_addr;                // 当前 DMX 起始地址
    uint16_t sub_device_count;
    uint8_t  sensor_count;
    // ---- 文本参数 ----
    char     manufacturer[RDM_LABEL_LEN];   // MANUFACTURER_LABEL (0x0081)
    char     model_desc[RDM_LABEL_LEN];     // DEVICE_MODEL_DESCRIPTION (0x0080)
    char     software_label[RDM_LABEL_LEN]; // SOFTWARE_VERSION_LABEL (0x00C0)
    char     device_label[RDM_LABEL_LEN];   // DEVICE_LABEL (0x0082)
    char     personality_desc[RDM_LABEL_LEN]; // DMX_PERSONALITY_DESCRIPTION (0x00E1)
} rdm_device_t;

/** 初始化（分配 PSRAM）。由 render_start_task() 或 app_main 调用一次。 */
void rdm_init(void);

/**
 * 扫描某宇宙的 RDM 总线并读取每台设备的参数。
 * 阻塞；期间该宇宙 DMX 输出暂停，返回前恢复。
 * @param universe 0 = A 通道(宇宙1)，1 = B 通道(宇宙2)
 * @return 发现的设备数（<0 表示失败）
 */
int rdm_scan(uint8_t universe);

int rdm_device_count(uint8_t universe);
const rdm_device_t *rdm_device_get(uint8_t universe, int index);

/** 远程改地址（SET DMX_START_ADDRESS）。 */
bool rdm_set_address(uint8_t universe, const uint8_t uid[6], uint16_t addr);

/** 一条"UID → 新地址"的指派。 */
typedef struct {
    uint8_t  uid[6];
    uint16_t addr;
} rdm_addr_set_t;

/**
 * 批量改址：按列表逐台写 DMX_START_ADDRESS。
 *
 * 为什么单独做批量而不是让 App 连发 N 条单台命令：每台单发都要
 * **暂停 DMX → 收发 → 恢复 DMX** 一轮，N 台就是 N 轮churn，现场会看到闪断。
 * 批量只暂停一次，写完统一恢复。
 *
 * @return 成功台数；<0 表示失败（看 rdm_last_error()）
 */
int rdm_set_addresses(uint8_t universe, const rdm_addr_set_t *list, int count);

/** 识别：让灯具闪烁（SET IDENTIFY_DEVICE）。 */
bool rdm_identify(uint8_t universe, const uint8_t uid[6], bool on);

/** 上一次操作的错误描述（供 UI 提示）。 */
const char *rdm_last_error(void);

#ifdef __cplusplus
}
#endif

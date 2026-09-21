#include "rdm.h"
#include "dmx.h"
#include "dmx_state.h"      // DMX_UNIVERSES
#include "esp_dmx.h"
#include "esp_log.h"
#include "esp_heap_caps.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include <string.h>
#include <stdarg.h>

// esp_dmx 的 RDM 控制器 API（组件 INCLUDE_DIRS = "src"，所以下面这几个路径是对的）
#include "rdm/controller/include/discovery.h"
#include "rdm/controller/include/product_info.h"
#include "rdm/controller/include/device_control.h"
#include "rdm/controller/include/dmx_setup.h"
#include "rdm/controller/include/utils.h"

static const char *TAG = "rdm";

// 设备表放 PSRAM：32 台 × 2 宇宙 × ~200B ≈ 13KB，内部 RAM 没必要占
static rdm_device_t *s_dev = NULL;
static int  s_count[DMX_UNIVERSES];
static char s_err[96];

// 定义在后面；模拟模式填表要用
static rdm_device_t *dev_slot(uint8_t universe, int idx);

static void set_err(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(s_err, sizeof(s_err), fmt, ap);
    va_end(ap);
}

const char *rdm_last_error(void) { return s_err; }

// ==================== 说明 ====================
// 这里只做**真实** RDM 扫描。
//
// 曾经有一份"模拟模式"（一个默认打开的开关）：扫描时不碰总线、直接填一张
// 内置虚拟灯具表，用来在没有真灯时验证 App 的 UI。它有两个害处：
//   1. 默认开 → 真灯在场也扫不到，排查时极易误判成"总线/接线有问题"；
//   2. 它让"扫描成功"这件事变得不可信。
// 现在有了 stagedmx_sniff（第二块 S3 当灯具模拟器，走真实 RDM 协议），
// 那份假数据已无必要，整体移除。


void rdm_init(void)
{
    if (!s_dev) {
        s_dev = (rdm_device_t *)heap_caps_malloc(
            sizeof(rdm_device_t) * RDM_MAX_DEVICES * DMX_UNIVERSES,
            MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
        if (!s_dev) {
            ESP_LOGE(TAG, "PSRAM 分配失败（需要 %u 字节），RDM 不可用",
                     (unsigned)(sizeof(rdm_device_t) * RDM_MAX_DEVICES * DMX_UNIVERSES));
            return;
        }
    }
    memset(s_dev, 0, sizeof(rdm_device_t) * RDM_MAX_DEVICES * DMX_UNIVERSES);
    memset(s_count, 0, sizeof(s_count));
    s_err[0] = '\0';
    ESP_LOGI(TAG, "RDM controller ready (%d KB in PSRAM)",
             (int)(sizeof(rdm_device_t) * RDM_MAX_DEVICES * DMX_UNIVERSES / 1024));
}

static rdm_device_t *dev_slot(uint8_t universe, int idx)
{
    if (!s_dev || universe >= DMX_UNIVERSES) return NULL;
    if (idx < 0 || idx >= RDM_MAX_DEVICES) return NULL;
    return &s_dev[universe * RDM_MAX_DEVICES + idx];
}

int rdm_device_count(uint8_t universe)
{
    if (universe >= DMX_UNIVERSES) return 0;
    return s_count[universe];
}

const rdm_device_t *rdm_device_get(uint8_t universe, int index)
{
    return dev_slot(universe, index);
}

/** 读一个 ASCII 字符串参数（MANUFACTURER_LABEL / MODEL_DESCRIPTION / DEVICE_LABEL）。 */
static void get_label(dmx_port_t port, const rdm_uid_t *uid, rdm_pid_t pid, char *out)
{
    out[0] = '\0';
    const rdm_request_t req = {
        .dest_uid = uid,
        .sub_device = RDM_SUB_DEVICE_ROOT,
        .cc = RDM_CC_GET_COMMAND,
        .pid = pid,
    };
    rdm_ack_t ack;
    const size_t n = rdm_send_request(port, &req, "a$", out, RDM_LABEL_LEN, &ack);
    if (n == 0 || (ack.type != RDM_RESPONSE_TYPE_ACK &&
                   ack.type != RDM_RESPONSE_TYPE_ACK_OVERFLOW)) {
        out[0] = '\0';       // 设备不支持该参数很正常，不算错误
    }
    out[RDM_LABEL_LEN - 1] = '\0';
}

/** 逐台读参数（DEVICE_INFO 必读，文本参数缺了也不影响设备可用）。 */
static void read_device_params(uint8_t universe, dmx_port_t port, int idx)
{
    rdm_device_t *d = dev_slot(universe, idx);
    if (!d) return;
    rdm_uid_t uid;
    uid.man_id = (uint16_t)((d->uid[0] << 8) | d->uid[1]);
    uid.dev_id = ((uint32_t)d->uid[2] << 24) | ((uint32_t)d->uid[3] << 16) |
                 ((uint32_t)d->uid[4] << 8) | (uint32_t)d->uid[5];

    // 1) DEVICE_INFO —— 最核心的一项，包含地址/占用通道/模式/型号/版本
    rdm_device_info_t info;
    memset(&info, 0, sizeof(info));
    rdm_ack_t ack;
    const size_t n = rdm_send_get_device_info(port, &uid, RDM_SUB_DEVICE_ROOT, &info, &ack);
    if (n > 0) {
        d->model_id            = info.model_id;
        d->product_category    = info.product_category;
        d->software_version_id = info.software_version_id;
        d->footprint           = info.footprint;
        d->personality         = info.personality.current;
        d->personality_count   = info.personality.count;
        d->start_addr          = info.dmx_start_address;
        d->sub_device_count    = info.sub_device_count;
        d->sensor_count        = info.sensor_count;
    } else {
        ESP_LOGW(TAG, "U%d #%d DEVICE_INFO 无应答", universe + 1, idx);
    }

    // 2) 文本参数（设备不支持时返回空串）
    get_label(port, &uid, RDM_PID_MANUFACTURER_LABEL,  d->manufacturer);
    get_label(port, &uid, RDM_PID_DEVICE_MODEL_DESCRIPTION, d->model_desc);
    get_label(port, &uid, RDM_PID_DEVICE_LABEL,        d->device_label);
    if (rdm_send_get_software_version_label(port, &uid, RDM_SUB_DEVICE_ROOT,
                                            d->software_label, RDM_LABEL_LEN, &ack) == 0) {
        d->software_label[0] = '\0';
    }

    // 3) 当前模式的名称（DMX_PERSONALITY_DESCRIPTION 需要带模式号做参数数据）
    d->personality_desc[0] = '\0';
    if (d->personality > 0) {
        const uint8_t pn = d->personality;
        // ⚠ rdm_request_t 里有**两个** format，别搞混：
        //     request->format  —— 请求里参数数据的格式（这里要发 1 字节模式号）
        //     rdm_send_request() 的第 3 个参数 —— 应答里参数数据的格式
        //   漏掉 request->format 会直接踩 utils.c:23 的断言
        //   （request->format != NULL || request->pd == NULL）→ 控台重启。
        const rdm_request_t req = {
            .dest_uid = &uid,
            .sub_device = RDM_SUB_DEVICE_ROOT,
            .cc = RDM_CC_GET_COMMAND,
            .pid = RDM_PID_DMX_PERSONALITY_DESCRIPTION,
            .format = "b",          // ← 请求：1 字节模式号
            .pd = &pn,
            .pdl = 1,
        };
        // 应答 = 模式号(1B) + 最大通道数(2B) + 名称(ASCII)
        // 所以要按 "bwa$" 读，才能把名称落到 pd+3；只写 "w$" 的话名称永远是空的。
        uint8_t pd[3 + RDM_LABEL_LEN];
        memset(pd, 0, sizeof(pd));
        if (rdm_send_request(port, &req, "bwa$", pd, sizeof(pd), &ack) > 0) {
            const size_t copy = RDM_LABEL_LEN - 1;
            memcpy(d->personality_desc, pd + 3, copy);
            d->personality_desc[copy] = '\0';
        }
    }

    ESP_LOGI(TAG, "U%d #%d UID %02X%02X:%02X%02X%02X%02X addr=%u footprint=%u v%u.%u %s %s",
             universe + 1, idx, d->uid[0], d->uid[1], d->uid[2], d->uid[3], d->uid[4], d->uid[5],
             d->start_addr, d->footprint, d->personality, d->personality_count,
             d->manufacturer, d->model_desc);
}

int rdm_scan(uint8_t universe)
{
    s_err[0] = '\0';
    if (!s_dev) { set_err("RDM 未初始化（PSRAM 分配失败）"); return -1; }
    if (universe >= DMX_UNIVERSES) { set_err("宇宙号无效"); return -1; }
    if (!dmx_port_ready(universe)) { set_err("该宇宙的 DMX 驱动未就绪"); return -1; }

    const dmx_port_t port = (dmx_port_t)dmx_port_of(universe);
    s_count[universe] = 0;

    // ---- 1) 暂停 DMX 输出，把总线交给 RDM ----
    if (!dmx_output_pause(universe)) {
        set_err("无法暂停 DMX 输出（输出任务没响应）");
        return -1;
    }
    if (!dmx_rdm_mode(universe, true)) {
        dmx_output_resume(universe);
        set_err("无法切换到 RDM 模式（引脚配置失败）");
        return -1;
    }

    int found = 0;
    rdm_uid_t uids[RDM_MAX_DEVICES];
    memset(uids, 0, sizeof(uids));

    // ---- 2) 设备发现（二分查找，内部已处理冲突消解与静默）----
    ESP_LOGI(TAG, "U%d RDM 扫描开始…", universe + 1);
    const int64_t t0 = esp_timer_get_time();
    found = rdm_discover_devices_simple(port, uids, RDM_MAX_DEVICES);
    const int64_t dt_ms = (esp_timer_get_time() - t0) / 1000;
    ESP_LOGI(TAG, "U%d 发现阶段耗时 %lld ms（<30ms 通常说明总线上没有 RDM 应答）",
             universe + 1, (long long)dt_ms);
    if (found < 0) {
        set_err("设备发现失败（RDM 总线无响应）");
        found = 0;
    } else if (found > RDM_MAX_DEVICES) {
        found = RDM_MAX_DEVICES;
    }

    // ---- 3) 逐台读参数 ----
    for (int i = 0; i < found; i++) {
        rdm_device_t *d = dev_slot(universe, i);
        if (!d) break;
        d->uid[0] = (uint8_t)(uids[i].man_id >> 8);
        d->uid[1] = (uint8_t)(uids[i].man_id & 0xFF);
        d->uid[2] = (uint8_t)(uids[i].dev_id >> 24);
        d->uid[3] = (uint8_t)((uids[i].dev_id >> 16) & 0xFF);
        d->uid[4] = (uint8_t)((uids[i].dev_id >> 8) & 0xFF);
        d->uid[5] = (uint8_t)(uids[i].dev_id & 0xFF);
        d->valid = true;
        s_count[universe] = i + 1;
        read_device_params(universe, port, i);
        vTaskDelay(pdMS_TO_TICKS(2));   // 设备之间留一点间隙
    }

    // ---- 4) 恢复 DMX 输出 ----
    dmx_rdm_mode(universe, false);
    dmx_output_resume(universe);

    ESP_LOGI(TAG, "U%d RDM 扫描结束：发现 %d 台", universe + 1, found);
    if (found == 0) set_err("未发现 RDM 设备（检查灯具是否支持 RDM、A/B 是否接反）");
    return found;
}

/** 把 6 字节 UID 转成驱动要的结构。 */
static void to_uid(const uint8_t uid[6], rdm_uid_t *out)
{
    out->man_id = (uint16_t)((uid[0] << 8) | uid[1]);
    out->dev_id = ((uint32_t)uid[2] << 24) | ((uint32_t)uid[3] << 16) |
                  ((uint32_t)uid[4] << 8) | (uint32_t)uid[5];
}

bool rdm_set_address(uint8_t universe, const uint8_t uid[6], uint16_t addr)
{
    s_err[0] = '\0';
    if (!s_dev || universe >= DMX_UNIVERSES || !dmx_port_ready(universe)) {
        set_err("RDM 不可用");
        return false;
    }
    const dmx_port_t port = (dmx_port_t)dmx_port_of(universe);
    rdm_uid_t u;
    to_uid(uid, &u);

    if (!dmx_output_pause(universe)) { set_err("无法暂停 DMX 输出"); return false; }
    if (!dmx_rdm_mode(universe, true)) {
        dmx_output_resume(universe); set_err("无法进入 RDM 模式"); return false;
    }

    rdm_ack_t ack;
    const bool ok = rdm_send_set_dmx_start_address(port, &u, RDM_SUB_DEVICE_ROOT, addr, &ack);

    dmx_rdm_mode(universe, false);
    dmx_output_resume(universe);

    if (!ok) {
        set_err("改址失败：设备无应答或拒绝（%s）",
                ack.type == RDM_RESPONSE_TYPE_NACK_REASON ? "NACK" : "超时");
        return false;
    }
    // 同步更新本机缓存，UI 立刻能看到新地址
    for (int i = 0; i < s_count[universe]; i++) {
        rdm_device_t *d = dev_slot(universe, i);
        if (d && memcmp(d->uid, uid, 6) == 0) { d->start_addr = addr; break; }
    }
    ESP_LOGI(TAG, "U%d 改址成功：%02X%02X:%02X%02X%02X%02X → %u",
             universe + 1, uid[0], uid[1], uid[2], uid[3], uid[4], uid[5], addr);
    return true;
}

/**
 * 批量改址：**只暂停一次 DMX**，逐台写 DMX_START_ADDRESS，最后统一恢复。
 * 每台之间留一点间隙，让总线和灯具都缓过来。
 */
int rdm_set_addresses(uint8_t universe, const rdm_addr_set_t *list, int count)
{
    s_err[0] = '\0';
    if (!s_dev || universe >= DMX_UNIVERSES || !dmx_port_ready(universe)) {
        set_err("RDM 不可用");
        return -1;
    }
    if (!list || count <= 0) { set_err("没有要改的灯具"); return -1; }

    const dmx_port_t port = (dmx_port_t)dmx_port_of(universe);
    if (!dmx_output_pause(universe)) { set_err("无法暂停 DMX 输出"); return -1; }
    if (!dmx_rdm_mode(universe, true)) {
        dmx_output_resume(universe); set_err("无法进入 RDM 模式"); return -1;
    }

    int ok = 0;
    for (int i = 0; i < count; i++) {
        rdm_uid_t u;
        to_uid(list[i].uid, &u);
        rdm_ack_t ack;
        if (rdm_send_set_dmx_start_address(port, &u, RDM_SUB_DEVICE_ROOT, list[i].addr, &ack)) {
            ok++;
            // 同步本机缓存，UI 立刻能看到新地址
            for (int k = 0; k < s_count[universe]; k++) {
                rdm_device_t *d = dev_slot(universe, k);
                if (d && memcmp(d->uid, list[i].uid, 6) == 0) { d->start_addr = list[i].addr; break; }
            }
        } else {
            ESP_LOGW(TAG, "批量改址：%02X%02X:%02X%02X%02X%02X → %u 无应答",
                     list[i].uid[0], list[i].uid[1], list[i].uid[2],
                     list[i].uid[3], list[i].uid[4], list[i].uid[5], list[i].addr);
        }
        vTaskDelay(pdMS_TO_TICKS(5));
    }

    dmx_rdm_mode(universe, false);
    dmx_output_resume(universe);
    if (ok == 0) set_err("全部无应答（灯具可能不支持 RDM）");
    ESP_LOGI(TAG, "U%d 批量改址：%d/%d 台成功", universe + 1, ok, count);
    return ok;
}

bool rdm_identify(uint8_t universe, const uint8_t uid[6], bool on){
    s_err[0] = '\0';
    if (!s_dev || universe >= DMX_UNIVERSES || !dmx_port_ready(universe)) {
        set_err("RDM 不可用");
        return false;
    }
    const dmx_port_t port = (dmx_port_t)dmx_port_of(universe);
    rdm_uid_t u;
    to_uid(uid, &u);

    if (!dmx_output_pause(universe)) { set_err("无法暂停 DMX 输出"); return false; }
    if (!dmx_rdm_mode(universe, true)) {
        dmx_output_resume(universe); set_err("无法进入 RDM 模式"); return false;
    }

    rdm_ack_t ack;
    const bool ok = rdm_send_set_identify_device(port, &u, RDM_SUB_DEVICE_ROOT,
                                                 on ? 1 : 0, &ack);

    dmx_rdm_mode(universe, false);
    dmx_output_resume(universe);

    if (!ok) set_err("识别命令失败：设备无应答");
    return ok;
}

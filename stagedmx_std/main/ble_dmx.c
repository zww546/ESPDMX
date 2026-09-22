#include "ble_dmx.h"
#include "dmx_state.h"
#include "dmx.h"
#include "program.h"
#include "fx.h"
#include "fx_proto.h"
#include "rdm.h"
#include "usb_msc.h"
#include "file_xfer.h"
#include <string.h>
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

static const char *TAG = "ble_dmx";
#define DEVICE_NAME "StageDMX-01"

static uint8_t  s_own_addr_type;
static uint16_t s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
static uint16_t s_notify_val_handle;
static volatile bool s_connected = false;

static void advertise(void);
static void file_notify_cb(const uint8_t *data, uint16_t len);
static void state_sync_request(void);

bool ble_dmx_is_connected(void) { return s_connected; }
const char *ble_dmx_name(void)  { return DEVICE_NAME; }

// ---------- 状态同步（App 重启 / 单片机重启后保持一致）----------
// App 写 0x05 请求整机状态，固件回：
//   0x82 flags(1) uptime(4, 秒, 大端) fxCount(1) progMask(1)
//   0x83 seq(1) startHi startLo count(1) data[count]      （1024 通道分块，每块 ≤255 → 5 帧）
//   0x84 slot(1) fxId(1) ampHi ampLo speedHi speedLo      （每个运行中的效果一帧）
//   0x85                                                   （结束）
// App 用 uptime 判断单片机是否重启过：重启过 → 用 App 状态覆盖；
// 否则（App 自己重启/重连）→ 采纳设备状态，使开关与数值显示与单片机一致。
#define STATE_MAX_PAYLOAD 260

static SemaphoreHandle_t s_state_sem = NULL;
static uint8_t s_state_buf[STATE_MAX_PAYLOAD];
static volatile uint8_t s_state_seq = 0;

static void state_sync_request(void)
{
    if (s_state_sem) xSemaphoreGive(s_state_sem);
}

static void state_sync_task(void *arg)
{
    static uint8_t frame[DMX_CHANNELS];
    while (1) {
        // 2 秒超时 = "心跳"：即使没有 0x05 请求，也定期把 16 字节状态头发给 App，
        // 否则状态总览条会停在连接那一刻的数值（fps 恒为 0）。
        // 只发头、不重发 1024 通道，BLE 负担可忽略。
        const bool requested = (xSemaphoreTake(s_state_sem, pdMS_TO_TICKS(2000)) == pdTRUE);
        if (!s_connected) continue;

        uint32_t up_s = (uint32_t)(esp_timer_get_time() / 1000000);
        uint8_t prog_mask = 0;
        for (int i = 0; i < PROG_MAX_COUNT && i < 8; i++) {
            if (program_is_playing(i)) prog_mask |= (uint8_t)(1u << i);
        }
        int fx_count = fx_running_count();

        uint8_t *h = s_state_buf;
        h[0] = 0x82; h[1] = 0x00;
        h[2] = (uint8_t)(up_s >> 24); h[3] = (uint8_t)(up_s >> 16);
        h[4] = (uint8_t)(up_s >> 8);  h[5] = (uint8_t)up_s;
        h[6] = (uint8_t)fx_count;
        h[7] = prog_mask;
        // ---- v9 追加：DMX 遥测（供 App 状态总览条）----
        // ⚠ 新字段只能**追加在尾部**：App 按帧长判断，新旧混用不会越界。
        //   [8] DMX 正常位(bit0=U1,bit1=U2) [9..12] 累计失败帧(大端)
        //   [13] U1 fps [14] U2 fps [15] 保留
        uint32_t f1 = 0, x1 = 0, f2 = 0, x2 = 0;
        uint8_t ok1 = 0, ok2 = 0, fps1 = 0, fps2 = 0;
        dmx_get_stats(0, &f1, &x1, &ok1, &fps1);
        if (DMX_UNIVERSES > 1) dmx_get_stats(1, &f2, &x2, &ok2, &fps2);
        uint32_t fails = x1 + x2;
        h[8] = (uint8_t)((ok1 ? 1 : 0) | (ok2 ? 2 : 0));
        h[9]  = (uint8_t)(fails >> 24); h[10] = (uint8_t)(fails >> 16);
        h[11] = (uint8_t)(fails >> 8);  h[12] = (uint8_t)fails;
        h[13] = fps1;
        h[14] = fps2;
        h[15] = 0;
        ble_dmx_notify(h, 16);
        if (!requested) continue;   // 心跳只发状态头，通道快照仅在真正请求时发

        vTaskDelay(pdMS_TO_TICKS(10));

        // 1024 通道快照（纯通道数组，buf[0] = 全局通道 1；无起始码）
        dmx_state_snapshot(frame);
        for (uint16_t start = 1; start <= DMX_CHANNELS; start += 255) {
            uint16_t count = (uint16_t)(DMX_CHANNELS - start + 1);
            if (count > 255) count = 255;
            s_state_buf[0] = 0x83;
            s_state_buf[1] = s_state_seq++;
            s_state_buf[2] = (uint8_t)(start >> 8);
            s_state_buf[3] = (uint8_t)(start & 0xFF);
            s_state_buf[4] = (uint8_t)count;
            memcpy(&s_state_buf[5], &frame[start - 1], count);
            ble_dmx_notify(s_state_buf, (uint16_t)(5 + count));
            vTaskDelay(pdMS_TO_TICKS(8));
        }

        for (int s = 0; s < FX_MAX_COUNT; s++) {
            uint8_t id = 0; uint16_t amp = 0, spd = 0;
            if (!fx_get_info((uint8_t)s, &id, &amp, &spd)) continue;
            uint8_t *b = s_state_buf;
            b[0] = 0x84; b[1] = (uint8_t)s; b[2] = id;
            b[3] = (uint8_t)(amp >> 8); b[4] = (uint8_t)(amp & 0xFF);
            b[5] = (uint8_t)(spd >> 8); b[6] = (uint8_t)(spd & 0xFF);
            ble_dmx_notify(b, 7);
            vTaskDelay(pdMS_TO_TICKS(8));
        }

        uint8_t end = 0x85;
        ble_dmx_notify(&end, 1);
        ESP_LOGI(TAG, "state sync sent (uptime=%us fx=%d prog=0x%02x)", (unsigned)up_s, fx_count, prog_mask);
    }
}

// ---------- 指令帧解析 ----------
// 工具: 解析帧内 "lenX data…" 可变长字符串段，消除 0x31-0x3C 文件命令的重复样板。

// 解析一个 len+data 段，返回下一字段的偏移（成功）或 -1（越界）。
static int parse_str(const uint8_t *d, int len, int pos,
                     const uint8_t **out, int *outLen)
{
    if (pos + 1 > len) return -1;
    int sl = d[pos];
    if (pos + 1 + sl > len) return -1;
    *out = &d[pos + 1];
    *outLen = sl;
    return pos + 1 + sl;
}

// 拷贝字符串段到安全缓冲（带截断与结束符）。空段 → ""。
static void copy_str(const uint8_t *src, int slen, char *dst, int cap)
{
    int n = slen < cap - 1 ? slen : cap - 1;
    memcpy(dst, src, n);
    dst[n] = '\0';
}

// 解析一段（目录/名称/路径），拷贝到 dir。返回下一字段偏移；失败 -1。
static int parse_dir(const uint8_t *d, int len, int pos, char *dir, int dirCap)
{
    const uint8_t *p; int sl;
    int next = parse_str(d, len, pos, &p, &sl);
    if (next < 0) return -1;
    copy_str(p, sl, dir, dirCap);
    return next;
}

// 解析连续两段 "dirLen dir… nameLen name…"（dir 可为空=根目录）。
// 返回下一字段偏移（供 size/dstDir 等继续读）；失败 -1。
static int parse_dir_name(const uint8_t *d, int len, int pos,
                          char *dir, int dirCap, char *name, int nameCap)
{
    int next = parse_dir(d, len, pos, dir, dirCap);
    if (next < 0) return -1;
    return parse_dir(d, len, next, name, nameCap);
}

// ==================== RDM 请求队列 + 工作任务 ====================
// 扫描/改址/识别都是阻塞操作（要暂停 DMX、等灯具应答），而 handle_frame 跑在
// NimBLE host 任务上 —— 直接同步执行会阻塞 BLE 甚至断连。所以：
//   handle_frame 只登记请求 → rdm_task 串行执行 → 用通知回传结果。
typedef enum { RDM_REQ_NONE = 0, RDM_REQ_SCAN, RDM_REQ_IDENTIFY, RDM_REQ_SETADDR,
               RDM_REQ_SETADDRS } rdm_req_t;

static volatile rdm_req_t s_rdm_req = RDM_REQ_NONE;
static uint8_t  s_rdm_uni;
static uint8_t  s_rdm_uid[6];
static uint8_t  s_rdm_on;
static uint16_t s_rdm_addr;
// 批量改址的批次数据（0x44）。请求只传"去处理它"，数据本身放这里。
static rdm_addr_set_t s_rdm_batch[32];
static uint8_t        s_rdm_batch_n;

static SemaphoreHandle_t s_rdm_sem = NULL;

static void rdm_post(rdm_req_t req, uint8_t universe, const uint8_t *uid,
                     uint8_t on, uint16_t addr)
{
    if (s_rdm_req != RDM_REQ_NONE) {      // 上一个还没跑完
        uint8_t busy[2] = { 0x8B, 1 };    // 1 = 失败
        ble_dmx_notify(busy, 2);
        return;
    }
    s_rdm_uni = universe;
    if (uid) memcpy(s_rdm_uid, uid, 6);
    s_rdm_on = on;
    s_rdm_addr = addr;
    s_rdm_req = req;
    if (s_rdm_sem) xSemaphoreGive(s_rdm_sem);
}

/** 把驱动用的 rdm_device_t 序列化成一帧 0x8A（含全部 GET 到的参数）。 */
static void rdm_send_device(uint8_t universe, int idx)
{
    const rdm_device_t *dev = rdm_device_get(universe, idx);
    if (!dev) return;
    static uint8_t buf[260];
    int i = 0;
    buf[i++] = 0x8A;
    buf[i++] = (uint8_t)idx;
    buf[i++] = universe;
    memcpy(&buf[i], dev->uid, 6); i += 6;
    buf[i++] = (uint8_t)(dev->start_addr >> 8);
    buf[i++] = (uint8_t)(dev->start_addr & 0xFF);
    buf[i++] = (uint8_t)(dev->footprint >> 8);
    buf[i++] = (uint8_t)(dev->footprint & 0xFF);
    buf[i++] = (uint8_t)(dev->model_id >> 8);
    buf[i++] = (uint8_t)(dev->model_id & 0xFF);
    buf[i++] = (uint8_t)(dev->product_category >> 8);
    buf[i++] = (uint8_t)(dev->product_category & 0xFF);
    buf[i++] = (uint8_t)(dev->software_version_id >> 24);
    buf[i++] = (uint8_t)(dev->software_version_id >> 16);
    buf[i++] = (uint8_t)(dev->software_version_id >> 8);
    buf[i++] = (uint8_t)(dev->software_version_id);
    buf[i++] = dev->personality;
    buf[i++] = dev->personality_count;
    buf[i++] = (uint8_t)(dev->sub_device_count >> 8);
    buf[i++] = (uint8_t)(dev->sub_device_count & 0xFF);
    buf[i++] = dev->sensor_count;
    // 5 段文本：每段 len(1) + 内容
    const char *labels[5] = { dev->manufacturer, dev->model_desc,
                              dev->software_label, dev->device_label,
                              dev->personality_desc };
    for (int k = 0; k < 5; k++) {
        size_t n = strlen(labels[k]);
        if (n > 32) n = 32;
        buf[i++] = (uint8_t)n;
        memcpy(&buf[i], labels[k], n);
        i += n;
    }
    ble_dmx_notify(buf, (uint16_t)i);
}

static void rdm_reply_result(bool ok, const char *what)
{
    uint8_t buf[128];
    const char *err = ok ? "" : rdm_last_error();
    size_t n = strlen(err);
    if (n > 100) n = 100;
    int i = 0;
    buf[i++] = 0x8B;                 // 操作结果（识别/改址）
    buf[i++] = ok ? 0 : 1;
    buf[i++] = (uint8_t)n;
    memcpy(&buf[i], err, n);
    i += n;
    ble_dmx_notify(buf, (uint16_t)i);
    ESP_LOGI(TAG, "%s: %s", what, ok ? "OK" : err);
}

static void rdm_task(void *arg)
{
    while (1) {
        if (xSemaphoreTake(s_rdm_sem, portMAX_DELAY) != pdTRUE) continue;
        rdm_req_t req = s_rdm_req;
        if (req == RDM_REQ_NONE) continue;
        const uint8_t uni = s_rdm_uni;
        switch (req) {
        case RDM_REQ_SCAN: {
            int found = rdm_scan(uni);
            // 0x89 扫描头：0x89 count(1) universe(1) ok(1) errLen(1) err…
            uint8_t buf[128];
            // ⚠ 错误文本不再只在 found<=0 时带出去。扫描成功但**DMX 驱动没重装回来**
            //   （或部分设备参数没读到）时 rdm_scan 照样返回 found>0，而 s_err 里有话要说 ——
            //   以前这里会把它丢掉，App 就只看到一句"扫描结束"，完全不知道那个宇宙已经停发了。
            //   rdm_scan 开始时清过 s_err，所以成功且一切正常时它就是空串。
            const char *err = rdm_last_error();
            size_t n = strlen(err);
            if (n > 100) n = 100;
            int i = 0;
            buf[i++] = 0x89;
            buf[i++] = (uint8_t)(found < 0 ? 0 : found);
            buf[i++] = uni;
            buf[i++] = (found > 0) ? 0 : 1;
            buf[i++] = (uint8_t)n;
            memcpy(&buf[i], err, n);
            i += n;
            ESP_LOGI(TAG, "RDM 回 0x89: found=%d err=\"%s\" 帧长=%d connected=%d",
                     found, err, i, (int)s_connected);
            ble_dmx_notify(buf, (uint16_t)i);
            for (int k = 0; k < (found > 0 ? found : 0); k++) {
                rdm_send_device(uni, k);
                vTaskDelay(pdMS_TO_TICKS(20));   // 给 BLE 栈留出发送时间
            }
            break;
        }
        case RDM_REQ_IDENTIFY:
            rdm_reply_result(rdm_identify(uni, s_rdm_uid, s_rdm_on != 0), "RDM identify");
            break;
        case RDM_REQ_SETADDR:
            rdm_reply_result(rdm_set_address(uni, s_rdm_uid, s_rdm_addr), "RDM set address");
            break;
        case RDM_REQ_SETADDRS: {
            int ok = rdm_set_addresses(uni, s_rdm_batch, s_rdm_batch_n);
            bool all = (ok == s_rdm_batch_n);
            char msg[72];
            if (all) snprintf(msg, sizeof(msg), "%d/%d 台已改址", ok, s_rdm_batch_n);
            else snprintf(msg, sizeof(msg), "%d/%d 台成功；%s", ok, s_rdm_batch_n,
                          rdm_last_error());
            rdm_reply_result(all, msg);
            break;
        }
        default: break;
        }
        s_rdm_req = RDM_REQ_NONE;
    }
}

static void handle_frame(const uint8_t *d, uint16_t len)
{
    if (len < 1) return;
    switch (d[0]) {    case 0x01: { // set range: 0x01 startHi startLo count v0..
        if (len < 4) return;
        uint16_t start = ((uint16_t)d[1] << 8) | d[2];
        uint16_t count = d[3];
        if (count == 0) count = 256;           // count 字段 0 视为 256（协议上限 255，防御）
        if (len < 4 + count) count = len - 4;
        if (count > 0) dmx_state_set_range(start, &d[4], count);
#ifdef RX_TRACE
        ESP_LOGI(TAG, "RX 0x01 start=%u count=%u v0=%u", start, count, count ? d[4] : 0);
#endif
        break;
    }
    case 0x02: dmx_state_set_all(0);   break;   // blackout
    case 0x03: dmx_state_set_all(255); break;   // full on
    case 0x04: break;                            // ping
    case 0x05: state_sync_request();  break;     // 请求整机状态（App 重连/重启后同步）
    case 0x10: { // 清空程序: 0x10 prog_id
        uint8_t pid = (len >= 2) ? d[1] : 0;
        program_clear(pid);
        break;
    }
    case 0x12: { // 存步(稀疏): 0x12 prog_id timeHi timeLo count (chHi chLo val)*
        if (len < 5) return;
        uint8_t pid = d[1];
        uint16_t t = ((uint16_t)d[2] << 8) | d[3];
        uint8_t count = d[4];
        // ⚠ 这里**不需要**再钳到 PROG_MAX_ITEMS_STEP：count 是 uint8_t（≤255），
        //   而 PROG_MAX_ITEMS_STEP 正好是 255（协议上限，0x12 帧的 count 占 1 字节）。
        //   原来那句 `if (count > PROG_MAX_ITEMS_STEP) count = ...` 编译器直接报
        //   "comparison is always false" —— 留着它反而让人以为上限是别的值。
        //   真正的边界是下面这句：按实际帧长反推能装几项。
        if (len < 5 + (size_t)count * 3) count = (len - 5) / 3;
        prog_item_t items[PROG_MAX_ITEMS_STEP];
        for (uint8_t i = 0; i < count; i++) {
            items[i].ch = ((uint16_t)d[5 + i*3] << 8) | d[6 + i*3];
            items[i].val = d[7 + i*3];
        }
        program_append(pid, t, items, count);
        break;
    }
    case 0x13: { // 播放: 0x13 prog_id flags (flags bit0=loop)
        uint8_t pid = (len >= 2) ? d[1] : 0;
        bool loop = (len >= 3) ? (d[2] & 0x01) : true;
        program_play(pid, loop);
        break;
    }
    case 0x14: { // 停止: 0x14 prog_id
        uint8_t pid = (len >= 2) ? d[1] : 0;
        program_stop(pid);
        break;
    }
    case 0x15: program_stop_all(); break; // 全部停止

    // ---- 效果层（板载离线运行）----
    case 0x20: { // 配置+启动效果：解析抽到 fx_proto.c（可在宿主端测协议契约）
        uint8_t slot = 0;
        fx_cfg_t cfg;
        if (!fx_cfg_parse(d, len, &slot, &cfg)) return;
        fx_set(slot, &cfg);
        break;
    }
    case 0x21: { // 停止效果: 0x21 slot
        uint8_t slot = (len >= 2) ? d[1] : 0;
        fx_stop(slot);
        break;
    }
    case 0x22: fx_stop_all(); break; // 全部停止

    // ---- RDM（v8）----
    // ⚠ 扫描要阻塞几百毫秒到数秒，绝不能在这里同步跑 —— 这跑在 NimBLE host 任务上，
    //   阻塞会让 BLE 连接断掉。所以只登记请求，交给 rdm_task 执行，结果用通知回传。
    //   应答：0x89 扫描头（含错误文本）+ 每台一条 0x8A（含全部 GET 到的参数）
    case 0x40: { // 扫描: 0x40 universe
        if (len < 2) return;
        rdm_post(RDM_REQ_SCAN, d[1], NULL, 0, 0);
        break;
    }
    case 0x41: { // 识别: 0x41 universe uid(6) on
        if (len < 9) return;
        rdm_post(RDM_REQ_IDENTIFY, d[1], &d[2], d[8] ? 1 : 0, 0);
        break;
    }
    case 0x42: { // 改址: 0x42 universe uid(6) addrHi addrLo
        if (len < 10) return;
        uint16_t addr = ((uint16_t)d[8] << 8) | d[9];
        rdm_post(RDM_REQ_SETADDR, d[1], &d[2], 0, addr);
        break;
    }
    case 0x44: { // 批量改址: 0x44 universe count (uid(6) addrHi addrLo)*
        // 拖动排序后"按顺序自动分配地址"用这条：一次暂停 DMX、写完整批、再恢复
        if (len < 3) return;
        uint8_t uni = d[1];
        uint8_t n = d[2];
        if (n > 32) n = 32;                       // 单帧上限（3 + 32*8 = 259 字节）
        if (len < 3 + (size_t)n * 8) n = (len - 3) / 8;
        if (n == 0) return;
        // ⚠ 先把批次数据写进静态区，再登记请求 —— 反过来的话 rdm 任务
        //   可能在数据写完之前就被唤醒，读到上一批的残留。
        for (uint8_t i = 0; i < n; i++) {
            memcpy(s_rdm_batch[i].uid, &d[3 + i * 8], 6);
            s_rdm_batch[i].addr = ((uint16_t)d[3 + i * 8 + 6] << 8) | d[3 + i * 8 + 7];
        }
        s_rdm_batch_n = n;
        rdm_post(RDM_REQ_SETADDRS, uni, NULL, 0, 0);
        break;
    }

    // ---- 文件传输（灯库上传/下载，全部支持子目录 dir）----
    // 通用解析：帧前部为 dirLen dir…（dirLen=0 → 根目录），后跟 nameLen name…
    case 0x31: { // UPLOAD_START: 0x31 dirLen dir… nameLen name… sizeHi sizeLo
        char dir[256], name[128];
        int next = parse_dir_name(d, len, 1, dir, 256, name, 128);
        if (next < 0 || next + 2 > len) return;
        uint32_t size = ((uint32_t)d[next] << 8) | d[next + 1];
        bool ok = file_xfer_upload_begin(dir, name, size);
        uint8_t resp[2] = {0x91, ok ? 0 : 1};
        ble_dmx_notify(resp, 2);
        break;
    }
    case 0x32: { // UPLOAD_CHUNK: 0x32 seq data…
        if (len < 3) return;                 // cmd + seq + at least 1 data byte
        file_xfer_upload_chunk(&d[2], len - 2);  // skip cmd(1) + seq(1)
        break;
    }
    case 0x33: { // UPLOAD_END
        bool ok = file_xfer_upload_end();
        uint8_t resp[2] = {0x91, ok ? 0 : 1};
        ble_dmx_notify(resp, 2);
        break;
    }
    case 0x34: { // LIST_FILES: 0x34 [dirLen dir…]（无参数 = 根目录，回 0x92）
        char dir[256]; dir[0] = '\0';
        // ⚠ 无参数（len==1）也是合法的“根目录”请求：旧代码无条件调用 parse_dir，
        //   而 parse_dir 在缺少 dirLen 字节时会失败 → 整个 case 直接 return，
        //   App 请求根目录（发裸 0x34）时永远收不到 0x92，表现为“设备灯库/文件管理 加载不出”。
        if (len > 1 && parse_dir(d, len, 1, dir, 256) < 0) return;
        file_xfer_list(dir, file_notify_cb);
        break;
    }
    case 0x35: { // DOWNLOAD_FILE: 0x35 dirLen dir… nameLen name…
        char dir[256], name[128];
        if (parse_dir_name(d, len, 1, dir, 256, name, 128) < 0) return;
        file_xfer_download(dir, name, file_notify_cb);
        break;
    }
    case 0x36: { // DELETE_FILE: 0x36 dirLen dir… nameLen name…
        char dir[256], name[128];
        if (parse_dir_name(d, len, 1, dir, 256, name, 128) < 0) return;
        bool ok = file_xfer_delete(dir, name);
        uint8_t resp[2] = {0x95, ok ? 0 : 1};
        ble_dmx_notify(resp, 2);
        break;
    }
    case 0x37: { // MKDIR: 0x37 dirLen dir… nameLen name…
        char dir[256], name[128];
        if (parse_dir_name(d, len, 1, dir, 256, name, 128) < 0) return;
        bool ok = file_xfer_mkdir(dir, name);
        uint8_t resp[2] = {0x96, ok ? 0 : 1};
        ble_dmx_notify(resp, 2);
        break;
    }
    case 0x38: { // RMDIR: 0x38 dirLen dir… nameLen name…
        char dir[256], name[128];
        if (parse_dir_name(d, len, 1, dir, 256, name, 128) < 0) return;
        bool ok = file_xfer_rmdir(dir, name);
        uint8_t resp[2] = {0x96, ok ? 0 : 1};
        ble_dmx_notify(resp, 2);
        break;
    }
    case 0x39: { // RENAME: 0x39 dirLen dir… oldLen old… newLen new…
        char dir[256], oname[128], nname[128];
        int next = parse_dir(d, len, 1, dir, 256);
        if (next < 0) return;
        next = parse_dir(d, len, next, oname, 128);
        if (next < 0) return;
        if (parse_dir(d, len, next, nname, 128) < 0) return;
        bool ok = file_xfer_rename(dir, oname, nname);
        uint8_t resp[2] = {0x96, ok ? 0 : 1};
        ble_dmx_notify(resp, 2);
        break;
    }
    case 0x3A: { // MOVE: 0x3A dirLen dir… nameLen name… dstDirLen dstDir…
        char dir[256], name[128], dst_dir[256];
        int next = parse_dir_name(d, len, 1, dir, 256, name, 128);
        if (next < 0) return;
        if (parse_dir(d, len, next, dst_dir, 256) < 0) return;
        bool ok = file_xfer_move(dir, name, dst_dir);
        uint8_t resp[2] = {0x96, ok ? 0 : 1};
        ble_dmx_notify(resp, 2);
        break;
    }
    case 0x3B: { // COPY: 0x3B dirLen dir… nameLen name… dstDirLen dstDir…
        char dir[256], name[128], dst_dir[256];
        int next = parse_dir_name(d, len, 1, dir, 256, name, 128);
        if (next < 0) return;
        if (parse_dir(d, len, next, dst_dir, 256) < 0) return;
        bool ok = file_xfer_copy(dir, name, dst_dir);
        uint8_t resp[2] = {0x96, ok ? 0 : 1};
        ble_dmx_notify(resp, 2);
        break;
    }
    case 0x3C: { // LIST_DIRS: 全量目录树（回 0x97 多帧 + 0x98 结束帧）
        file_xfer_list_dirs(file_notify_cb);
        break;
    }

    // ---- 自定义系统命令 ----
    case 0xA0: { // 0xA0 cmd arg
        if (len < 3) return;
        uint8_t cmd = d[1];
        uint8_t arg = d[2];
        switch (cmd) {
        case 0x30: // USB MSC U盘模式
            if (arg) usb_msc_start();
            else     usb_msc_stop();
            break;
        default: break;
        }
        break;
    }

    default:   break;
    }
}

// ---------- GATT ----------
static int chr_write_cb(uint16_t conn_handle, uint16_t attr_handle,
                        struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR)
        return BLE_ATT_ERR_UNLIKELY;
    static uint8_t buf[540];
    uint16_t len = 0;
    int rc = ble_hs_mbuf_to_flat(ctxt->om, buf, sizeof(buf), &len);
    if (rc != 0) return BLE_ATT_ERR_UNLIKELY;
    handle_frame(buf, len);
    return 0;
}

static int chr_notify_cb(uint16_t conn_handle, uint16_t attr_handle,
                         struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    return 0; // 只用于 notify，无读写
}

static const struct ble_gatt_svc_def gatt_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = BLE_UUID16_DECLARE(0xFF00),
        .characteristics = (struct ble_gatt_chr_def[]){
            {
                .uuid = BLE_UUID16_DECLARE(0xFF01),
                .access_cb = chr_write_cb,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
            },
            {
                .uuid = BLE_UUID16_DECLARE(0xFF02),
                .access_cb = chr_notify_cb,
                .flags = BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &s_notify_val_handle,
            },
            { 0 }
        },
    },
    { 0 }
};

static int gatt_svr_init(void)
{
    int rc;
    ble_svc_gap_init();
    ble_svc_gatt_init();
    rc = ble_gatts_count_cfg(gatt_svcs);
    if (rc != 0) return rc;
    rc = ble_gatts_add_svcs(gatt_svcs);
    return rc;
}

// ---------- GAP ----------
static int gap_event(struct ble_gap_event *event, void *arg)
{
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0) {
            s_conn_handle = event->connect.conn_handle;
            s_connected = true;
            ESP_LOGI(TAG, "connected");
        } else {
            advertise();
        }
        break;
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "disconnected; reason=%d", event->disconnect.reason);
        s_connected = false;
        s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
        advertise();
        break;
    case BLE_GAP_EVENT_ADV_COMPLETE:
        advertise();
        break;
    case BLE_GAP_EVENT_MTU:
        ESP_LOGI(TAG, "mtu update: %d", event->mtu.value);
        break;
    default:
        break;
    }
    return 0;
}

static void advertise(void)
{
    struct ble_hs_adv_fields fields;
    memset(&fields, 0, sizeof(fields));
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.tx_pwr_lvl_is_present = 1;
    fields.tx_pwr_lvl = BLE_HS_ADV_TX_PWR_LVL_AUTO;

    static ble_uuid16_t uuid16 = BLE_UUID16_INIT(0xFF00);
    fields.uuids16 = &uuid16;
    fields.num_uuids16 = 1;
    fields.uuids16_is_complete = 1;

    const char *name = ble_svc_gap_device_name();
    fields.name = (uint8_t *)name;
    fields.name_len = strlen(name);
    fields.name_is_complete = 1;

    int rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) { ESP_LOGE(TAG, "adv_set_fields rc=%d", rc); return; }

    struct ble_gap_adv_params adv_params;
    memset(&adv_params, 0, sizeof(adv_params));
    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    rc = ble_gap_adv_start(s_own_addr_type, NULL, BLE_HS_FOREVER,
                           &adv_params, gap_event, NULL);
    if (rc != 0) ESP_LOGE(TAG, "adv_start rc=%d", rc);
    else ESP_LOGI(TAG, "advertising as %s", name);
}

static void on_sync(void)
{
    int rc = ble_hs_util_ensure_addr(0);
    if (rc != 0) { ESP_LOGE(TAG, "ensure_addr rc=%d", rc); return; }
    rc = ble_hs_id_infer_auto(0, &s_own_addr_type);
    if (rc != 0) { ESP_LOGE(TAG, "infer_auto rc=%d", rc); return; }
    advertise();
}

static void on_reset(int reason)
{
    ESP_LOGW(TAG, "host reset; reason=%d", reason);
}

static void host_task(void *param)
{
    nimble_port_run();          // 阻塞直到 nimble_port_stop()
    nimble_port_freertos_deinit();
}

void ble_dmx_notify(const uint8_t *data, uint16_t len)
{
    if (!s_connected || len == 0) return;
    struct os_mbuf *om = ble_hs_mbuf_from_flat(data, len);
    if (!om) return;
    ble_gatts_notify_custom(s_conn_handle, s_notify_val_handle, om);
}

// ---------- 文件传输回调（传给 file_xfer）----------
static void file_notify_cb(const uint8_t *data, uint16_t len)
{
    ble_dmx_notify(data, len);
}

void ble_dmx_init(void)
{
    esp_err_t err = nimble_port_init();
    if (err != ESP_OK) { ESP_LOGE(TAG, "nimble_port_init failed: %d", err); return; }

    ble_hs_cfg.sync_cb  = on_sync;
    ble_hs_cfg.reset_cb = on_reset;

    int rc = gatt_svr_init();
    if (rc != 0) { ESP_LOGE(TAG, "gatt_svr_init rc=%d", rc); return; }

    ble_svc_gap_device_name_set(DEVICE_NAME);
    nimble_port_freertos_init(host_task);

    // 状态同步发送任务：显式钉在 core 0（射频/协议栈那一核），
    // 它的 512 通道突发上报 + 文件操作绝不能跑到 core 1 干扰 DMX。
    if (!s_state_sem) s_state_sem = xSemaphoreCreateBinary();
    xTaskCreatePinnedToCore(state_sync_task, "ble_state", 4096, NULL, 4, NULL, 0);

    // RDM 工作任务：也钉在 core 0。它要暂停 DMX 输出并等待灯具应答，
    // 放 core 1 会和 DMX 输出任务抢时间片（虽然 RDM 期间 DMX 本来就停了，
    // 但恢复的那一帧不容许被抢占）。
    if (!s_rdm_sem) s_rdm_sem = xSemaphoreCreateBinary();
    xTaskCreatePinnedToCore(rdm_task, "rdm", 4096, NULL, 4, NULL, 0);
    ESP_LOGI(TAG, "BLE init done");
}

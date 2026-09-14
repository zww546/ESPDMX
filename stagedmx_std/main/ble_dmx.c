#include "ble_dmx.h"
#include "dmx_state.h"
#include "dmx.h"
#include "program.h"
#include "fx.h"
#include "fx_proto.h"
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
        if (xSemaphoreTake(s_state_sem, portMAX_DELAY) != pdTRUE) continue;
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
        ble_dmx_notify(h, 8);
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

static void handle_frame(const uint8_t *d, uint16_t len)
{
    if (len < 1) return;
    switch (d[0]) {
    case 0x01: { // set range: 0x01 startHi startLo count v0..
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
        if (count > PROG_MAX_ITEMS_STEP) count = PROG_MAX_ITEMS_STEP;
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
    ESP_LOGI(TAG, "BLE init done");
}

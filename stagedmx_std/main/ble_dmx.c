#include "ble_dmx.h"
#include "dmx_state.h"
#include "dmx.h"
#include "program.h"
#include "fx.h"
#include "usb_msc.h"
#include "file_xfer.h"
#include <string.h>
#include "esp_log.h"
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

bool ble_dmx_is_connected(void) { return s_connected; }
const char *ble_dmx_name(void)  { return DEVICE_NAME; }

// ---------- 指令帧解析 ----------
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
        break;
    }
    case 0x02: dmx_state_set_all(0);   break;   // blackout
    case 0x03: dmx_state_set_all(255); break;   // full on
    case 0x04: break;                            // ping
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
    case 0x20: { // 配置+启动效果(v4): 0x20 slot fx_id pan panF tilt tiltF dim dimF r g b zoom zoomF focus focusF color gobo goboRot ampHi ampLo speedHi speedLo
        if (len < 39) return;
        uint8_t slot = d[1];
        if (slot >= FX_MAX_COUNT) return;
        fx_cfg_t cfg;
        memset(&cfg, 0, sizeof(cfg));
        cfg.fx_id   = d[2];
        int i = 3;
        #define RD16() (((uint16_t)d[i] << 8) | d[i+1]); i += 2
        cfg.pan_ch      = RD16(); cfg.pan_fine_ch  = RD16();
        cfg.tilt_ch     = RD16(); cfg.tilt_fine_ch = RD16();
        cfg.dim_ch      = RD16(); cfg.dim_fine_ch  = RD16();
        cfg.r_ch        = RD16(); cfg.g_ch         = RD16();
        cfg.b_ch        = RD16();
        cfg.zoom_ch     = RD16(); cfg.zoom_fine_ch = RD16();
        cfg.focus_ch    = RD16(); cfg.focus_fine_ch = RD16();
        cfg.color_ch    = RD16();
        cfg.gobo_ch     = RD16();
        cfg.gobo_rot_ch = RD16();
        cfg.amp16       = RD16();
        cfg.speed       = RD16();
        #undef RD16
        if (cfg.fx_id >= 1 && cfg.fx_id <= 11) fx_set(slot, &cfg);
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
        if (len < 5) return;
        uint8_t dl = d[1];
        if (2 + dl + 2 > len) return;
        uint8_t nl = d[2 + dl];
        if (2 + dl + 1 + nl + 2 > len) return;
        char dir[256], name[128];
        if (dl > 0) { memcpy(dir, &d[2], dl < 255 ? dl : 255); dir[dl < 255 ? dl : 255] = '\0'; }
        else dir[0] = '\0';
        memcpy(name, &d[3 + dl], nl < 127 ? nl : 127); name[nl < 127 ? nl : 127] = '\0';
        uint32_t size = ((uint32_t)d[3 + dl + nl] << 8) | d[4 + dl + nl];
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
    case 0x34: { // LIST_FILES: 0x34 [dirLen dir…]（无参数 = 根目录）
        char dir[256];
        dir[0] = '\0';
        if (len >= 2) {
            uint8_t dl = d[1];
            if (2 + dl > len) return;
            if (dl > 0) { memcpy(dir, &d[2], dl < 255 ? dl : 255); dir[dl < 255 ? dl : 255] = '\0'; }
        }
        file_xfer_list(dir, file_notify_cb);
        break;
    }
    case 0x35: { // DOWNLOAD_FILE: 0x35 dirLen dir… nameLen name…
        if (len < 3) return;
        uint8_t dl = d[1];
        if (2 + dl + 1 > len) return;
        uint8_t nl = d[2 + dl];
        if (2 + dl + 1 + nl > len) return;
        char dir[256], name[128];
        if (dl > 0) { memcpy(dir, &d[2], dl < 255 ? dl : 255); dir[dl < 255 ? dl : 255] = '\0'; }
        else dir[0] = '\0';
        memcpy(name, &d[3 + dl], nl < 127 ? nl : 127); name[nl < 127 ? nl : 127] = '\0';
        file_xfer_download(dir, name, file_notify_cb);
        break;
    }
    case 0x36: { // DELETE_FILE: 0x36 dirLen dir… nameLen name…
        if (len < 3) return;
        uint8_t dl = d[1];
        if (2 + dl + 1 > len) return;
        uint8_t nl = d[2 + dl];
        if (2 + dl + 1 + nl > len) return;
        char dir[256], name[128];
        if (dl > 0) { memcpy(dir, &d[2], dl < 255 ? dl : 255); dir[dl < 255 ? dl : 255] = '\0'; }
        else dir[0] = '\0';
        memcpy(name, &d[3 + dl], nl < 127 ? nl : 127); name[nl < 127 ? nl : 127] = '\0';
        bool ok = file_xfer_delete(dir, name);
        uint8_t resp[2] = {0x95, ok ? 0 : 1};
        ble_dmx_notify(resp, 2);
        break;
    }
    case 0x37: { // MKDIR: 0x37 dirLen dir… nameLen name…
        if (len < 3) return;
        uint8_t dl = d[1];
        if (2 + dl + 1 > len) return;
        uint8_t nl = d[2 + dl];
        if (2 + dl + 1 + nl > len) return;
        char dir[256], name[128];
        if (dl > 0) { memcpy(dir, &d[2], dl < 255 ? dl : 255); dir[dl < 255 ? dl : 255] = '\0'; }
        else dir[0] = '\0';
        memcpy(name, &d[3 + dl], nl < 127 ? nl : 127); name[nl < 127 ? nl : 127] = '\0';
        bool ok = file_xfer_mkdir(dir, name);
        uint8_t resp[2] = {0x96, ok ? 0 : 1};
        ble_dmx_notify(resp, 2);
        break;
    }
    case 0x38: { // RMDIR: 0x38 dirLen dir… nameLen name…
        if (len < 3) return;
        uint8_t dl = d[1];
        if (2 + dl + 1 > len) return;
        uint8_t nl = d[2 + dl];
        if (2 + dl + 1 + nl > len) return;
        char dir[256], name[128];
        if (dl > 0) { memcpy(dir, &d[2], dl < 255 ? dl : 255); dir[dl < 255 ? dl : 255] = '\0'; }
        else dir[0] = '\0';
        memcpy(name, &d[3 + dl], nl < 127 ? nl : 127); name[nl < 127 ? nl : 127] = '\0';
        bool ok = file_xfer_rmdir(dir, name);
        uint8_t resp[2] = {0x96, ok ? 0 : 1};
        ble_dmx_notify(resp, 2);
        break;
    }
    case 0x39: { // RENAME: 0x39 dirLen dir… oldLen old… newLen new…
        if (len < 4) return;
        uint8_t dl = d[1];
        if (2 + dl + 1 > len) return;
        uint8_t ol = d[2 + dl];
        if (2 + dl + 1 + ol + 1 > len) return;
        uint8_t nl = d[3 + dl + ol];
        if (2 + dl + 1 + ol + 1 + nl > len) return;
        char dir[256], oname[128], nname[128];
        if (dl > 0) { memcpy(dir, &d[2], dl < 255 ? dl : 255); dir[dl < 255 ? dl : 255] = '\0'; }
        else dir[0] = '\0';
        memcpy(oname, &d[3 + dl], ol < 127 ? ol : 127); oname[ol < 127 ? ol : 127] = '\0';
        memcpy(nname, &d[4 + dl + ol], nl < 127 ? nl : 127); nname[nl < 127 ? nl : 127] = '\0';
        bool ok = file_xfer_rename(dir, oname, nname);
        uint8_t resp[2] = {0x96, ok ? 0 : 1};
        ble_dmx_notify(resp, 2);
        break;
    }
    case 0x3A: { // MOVE: 0x3A dirLen dir… nameLen name… dstDirLen dstDir…
        if (len < 4) return;
        uint8_t dl = d[1];
        if (2 + dl + 1 > len) return;
        uint8_t nl = d[2 + dl];
        if (2 + dl + 1 + nl + 1 > len) return;
        uint8_t ddl = d[3 + dl + nl];
        if (2 + dl + 1 + nl + 1 + ddl > len) return;
        char dir[256], name[128], dst_dir[256];
        if (dl > 0) { memcpy(dir, &d[2], dl < 255 ? dl : 255); dir[dl < 255 ? dl : 255] = '\0'; }
        else dir[0] = '\0';
        memcpy(name, &d[3 + dl], nl < 127 ? nl : 127); name[nl < 127 ? nl : 127] = '\0';
        if (ddl > 0) { memcpy(dst_dir, &d[4 + dl + nl], ddl < 255 ? ddl : 255); dst_dir[ddl < 255 ? ddl : 255] = '\0'; }
        else dst_dir[0] = '\0';
        bool ok = file_xfer_move(dir, name, dst_dir);
        uint8_t resp[2] = {0x96, ok ? 0 : 1};
        ble_dmx_notify(resp, 2);
        break;
    }
    case 0x3B: { // COPY: 0x3B dirLen dir… nameLen name… dstDirLen dstDir…
        if (len < 4) return;
        uint8_t dl = d[1];
        if (2 + dl + 1 > len) return;
        uint8_t nl = d[2 + dl];
        if (2 + dl + 1 + nl + 1 > len) return;
        uint8_t ddl = d[3 + dl + nl];
        if (2 + dl + 1 + nl + 1 + ddl > len) return;
        char dir[256], name[128], dst_dir[256];
        if (dl > 0) { memcpy(dir, &d[2], dl < 255 ? dl : 255); dir[dl < 255 ? dl : 255] = '\0'; }
        else dir[0] = '\0';
        memcpy(name, &d[3 + dl], nl < 127 ? nl : 127); name[nl < 127 ? nl : 127] = '\0';
        if (ddl > 0) { memcpy(dst_dir, &d[4 + dl + nl], ddl < 255 ? ddl : 255); dst_dir[ddl < 255 ? ddl : 255] = '\0'; }
        else dst_dir[0] = '\0';
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
            dmx_force_sync();   // BLE重连: 通知DMX强制刷新
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
    ESP_LOGI(TAG, "BLE init done");
}

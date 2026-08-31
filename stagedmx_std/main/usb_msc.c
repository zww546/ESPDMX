/**
 * USB MSC U盘模式 — ESP32-S3 作为 USB 大容量存储设备
 *
 * 使用 TinyUSB v2 + SPI Flash 分区（wear-leveling FAT），
 * 通过 BLE 命令 0xA0 0x30 启停。
 * 启用后 ESP32-S3 USB OTG 作为闪存盘，控台/电脑可直读灯库文件。
 *
 * 注意：ESP32-S3 标准 DevKitC-1 的板载 USB 口接的是 USB-Serial/JTAG 控制器，
 * USB-OTG（GPIO19/20）需单独引出到 USB 母座才能使用本 U盘功能。
 */
#include "usb_msc.h"
#include "file_xfer.h"
#include <string.h>
#include "esp_log.h"
#include "esp_partition.h"
#include "wear_levelling.h"
#include "tinyusb.h"
#include "tinyusb_default_config.h"
#include "tinyusb_msc.h"
#include "ff.h"   // FM_FAT：U盘卷格式化为 FAT12/16（2MB 分区不适用 FAT32）

static const char *TAG = "usb_msc";
static bool s_active = false;
static wl_handle_t s_wl_handle = WL_INVALID_HANDLE;
static tinyusb_msc_storage_handle_t s_storage = NULL;

// ---- MSC 挂载回调（v2 事件模型）----
static void mount_changed_cb(tinyusb_msc_storage_handle_t handle,
                             tinyusb_msc_event_t *event, void *arg)
{
    ESP_LOGI(TAG, "MSC storage event id=%d mount_point=%d",
             event->id, event->mount_point);
}

// ---- 公共接口 ----
void usb_msc_start(void)
{
    if (s_active) return;

    // BLE 文件传输若已挂载 /fw，先释放该分区，避免与 wl_mount 冲突
    file_xfer_unmount();

    const esp_partition_t *partition = esp_partition_find_first(
        ESP_PARTITION_TYPE_DATA, ESP_PARTITION_SUBTYPE_DATA_FAT, "storage");
    if (!partition) {
        ESP_LOGE(TAG, "storage partition not found");
        return;
    }
    ESP_LOGI(TAG, "storage partition: addr=0x%lx size=%lu",
             partition->address, partition->size);

    // 1. 初始化 wear levelling（仅 WL，不挂 FAT，挂载交由 MSC 驱动管理）
    esp_err_t err = wl_mount(partition, &s_wl_handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "wl_mount failed: %d", err);
        return;
    }

    // 2. 安装 MSC 驱动（带挂载回调）
    tinyusb_msc_driver_config_t driver_cfg = {
        .callback = mount_changed_cb,
        .callback_arg = NULL,
    };
    err = tinyusb_msc_install_driver(&driver_cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "msc driver install failed: %d", err);
        goto fail_wl;
    }

    // 3. 创建 MSC 存储（初始挂载点 = USB 主机）
    tinyusb_msc_storage_config_t storage_cfg = {
        .medium.wl_handle = s_wl_handle,
        .mount_point = TINYUSB_MSC_STORAGE_MOUNT_USB,
        .fat_fs = {
            .base_path = "/fw",
            .config.max_files = 4,
            .config.allocation_unit_size = CONFIG_WL_SECTOR_SIZE,
            // 显式 FAT12/16（2MB 卷不适用 FAT32），专业控台标准
            .format_flags = FM_FAT,
        },
    };
    err = tinyusb_msc_new_storage_spiflash(&storage_cfg, &s_storage);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "msc storage create failed: %d", err);
        goto fail_driver;
    }

    // 4. 安装 USB 设备驱动
    tinyusb_config_t tusb_cfg = TINYUSB_DEFAULT_CONFIG();
    err = tinyusb_driver_install(&tusb_cfg);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "usb driver install failed: %d", err);
        goto fail_storage;
    }

    s_active = true;
    ESP_LOGI(TAG, "MSC started — USB drive visible");
    return;

fail_storage:
    tinyusb_msc_delete_storage(s_storage);
    s_storage = NULL;
fail_driver:
    tinyusb_msc_uninstall_driver();
fail_wl:
    wl_unmount(s_wl_handle);
    s_wl_handle = WL_INVALID_HANDLE;
}

void usb_msc_stop(void)
{
    if (!s_active) return;

    tinyusb_driver_uninstall();

    if (s_storage) {
        tinyusb_msc_delete_storage(s_storage);
        s_storage = NULL;
    }
    tinyusb_msc_uninstall_driver();

    if (s_wl_handle != WL_INVALID_HANDLE) {
        wl_unmount(s_wl_handle);
        s_wl_handle = WL_INVALID_HANDLE;
    }

    s_active = false;
    ESP_LOGI(TAG, "MSC stopped");
}

bool usb_msc_is_active(void)
{
    return s_active;
}

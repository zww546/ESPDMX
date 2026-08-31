/**
 * file_xfer.c — ESP32 灯库文件 Wi-Fi-less 传输
 *
 * 存储位置: /fw (storage 分区, FAT/wear-leveling, 2MB)
 * 文件格式: MA2 XML 灯库 (.xml)
 *
 * 协议帧格式:
 *   App→ESP: 0x31 UPLOAD_START  nameLen name… sizeHi sizeLo
 *             0x32 UPLOAD_CHUNK  seq data…
 *             0x33 UPLOAD_END
 *   ESP→App: 0x91 UPLOAD_RESULT  status(0=ok)
 *   App→ESP: 0x34 LIST_FILES
 *   ESP→App: 0x92 FILE_LIST  count [nameLen name… sizeHi sizeLo]×count
 *   App→ESP: 0x35 DOWNLOAD_FILE  nameLen name…
 *   ESP→App: 0x93 FILE_CHUNK  seq totalChunks dataLen data…
 *   ESP→App: 0x94 FILE_END     status(0=ok 1=not found)
 */
#include "file_xfer.h"
#include "usb_msc.h"
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <sys/stat.h>
#include <dirent.h>
#include <unistd.h>
#include <fcntl.h>
#include "esp_log.h"
#include "esp_vfs_fat.h"
#include "wear_levelling.h"

static const char *TAG = "file_xfer";
static bool s_mounted = false;
static wl_handle_t s_wl = WL_INVALID_HANDLE;
static FILE *s_upload_fp = NULL;

#define MOUNT_POINT "/fw"
#define CHUNK_SIZE   200   // BLE 每帧数据载荷（留空间给帧头）

bool file_xfer_is_mounted(void)
{
    return s_mounted;
}

void file_xfer_unmount(void)
{
    if (!s_mounted) return;
    if (s_upload_fp) { fclose(s_upload_fp); s_upload_fp = NULL; }
    esp_vfs_fat_spiflash_unmount_rw_wl(MOUNT_POINT, s_wl);
    s_wl = WL_INVALID_HANDLE;
    s_mounted = false;
    ESP_LOGI(TAG, "/fw unmounted");
}

bool file_xfer_mount(void)
{
    if (s_mounted && s_wl != WL_INVALID_HANDLE) return true;

    // 句柄失效或未挂载时，先清理残留的 VFS 注册，避免重复挂载状态错乱
    // （esp_vfs_fat_spiflash_mount_rw_wl 在路径已注册时会静默复用旧状态，导致句柄错位）
    if (s_mounted) {
        // s_mounted 标记与句柄不一致：重置标记
        s_mounted = false;
    }

    // USB MSC U盘模式占用了 storage 分区时，不能同时挂载 /fw
    if (usb_msc_is_active()) {
        ESP_LOGW(TAG, "USB MSC active, /fw unavailable");
        return false;
    }

    esp_vfs_fat_mount_config_t cfg = {
        .format_if_mount_failed = true,
        .max_files = 4,
        .allocation_unit_size = CONFIG_WL_SECTOR_SIZE,
    };
    esp_err_t err = esp_vfs_fat_spiflash_mount_rw_wl(
        MOUNT_POINT, "storage", &cfg, &s_wl);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "mount /fw failed: %d (%s)", err, esp_err_to_name(err));
        return false;
    }
    s_mounted = true;
    ESP_LOGI(TAG, "/fw mounted (wl=%p)", (void*)s_wl);
    return true;
}

// ---- 文件列表 ----
// 帧: 0x92 count [nameLen(1B) name sizeHi sizeLo]×count
void file_xfer_list(file_xfer_notify_t notify_cb)
{
    if (!notify_cb || !file_xfer_mount()) return;

    DIR *d = opendir(MOUNT_POINT);
    if (!d) return;

    // 第一遍: 收集所有条目（文件夹 + 文件），type=1 目录 / 0 文件
    struct {
        char name[128];
        uint32_t size;
        bool is_dir;
    } files[64];
    int count = 0;

    struct dirent *ent;
    while ((ent = readdir(d)) != NULL && count < 64) {
        if (ent->d_type == DT_DIR) {
            if (ent->d_name[0] == '.') continue;   // 跳过 . ..
            strncpy(files[count].name, ent->d_name, 127);
            files[count].size = 0;
            files[count].is_dir = true;
            count++;
        } else if (ent->d_type == DT_REG) {
            char path[288];
            snprintf(path, sizeof(path), MOUNT_POINT "/%s", ent->d_name);
            struct stat st;
            if (stat(path, &st) != 0) continue;
            strncpy(files[count].name, ent->d_name, 127);
            files[count].size = (uint32_t)st.st_size;
            files[count].is_dir = false;
            count++;
        }
    }
    closedir(d);

    // 第二遍: 分包发送(每包最多 6 个记录，保持单帧 512 以内)
    // 帧: 0x92 count [nameLen name type sizeHi sizeLo]*
    int offset = 0;
    while (offset < count) {
        int batch = count - offset;
        if (batch > 6) batch = 6;

        uint8_t buf[512];
        buf[0] = 0x92;
        buf[1] = batch;
        int pos = 2;

        for (int i = 0; i < batch; i++) {
            int idx = offset + i;
            uint8_t nl = (uint8_t)strlen(files[idx].name);
            buf[pos++] = nl;
            memcpy(&buf[pos], files[idx].name, nl); pos += nl;
            buf[pos++] = files[idx].is_dir ? 1 : 0;
            buf[pos++] = (files[idx].size >> 8) & 0xFF;
            buf[pos++] = files[idx].size & 0xFF;
        }
        notify_cb(buf, pos);
        offset += batch;
    }

    // 空列表也必须回一帧，否则 App 端永远等不到响应（无法显示"没有灯库"）
    if (count == 0) {
        uint8_t empty[2] = {0x92, 0};
        notify_cb(empty, 2);
    }

    ESP_LOGI(TAG, "listed %d entries", count);
}

// ---- 上传(写) ----
bool file_xfer_upload_begin(const char *name, uint32_t size)
{
    if (!file_xfer_mount()) return false;
    if (s_upload_fp) { fclose(s_upload_fp); s_upload_fp = NULL; }

    // 安全检查：文件名不包含路径分隔符
    if (strchr(name, '/') || strchr(name, '\\')) return false;

    char path[288];
    snprintf(path, sizeof(path), MOUNT_POINT "/%s", name);
    s_upload_fp = fopen(path, "w");
    if (!s_upload_fp) {
        ESP_LOGE(TAG, "upload: fopen %s failed (errno=%d: %s)", path, errno, strerror(errno));
        return false;
    }
    ESP_LOGI(TAG, "upload begin: %s (%lu bytes)", name, size);
    (void)size;
    return true;
}

int file_xfer_upload_chunk(const uint8_t *data, uint16_t len)
{
    if (!s_upload_fp) return -1;
    return (int)fwrite(data, 1, len, s_upload_fp);
}

bool file_xfer_upload_end(void)
{
    if (!s_upload_fp) return false;
    fclose(s_upload_fp);
    s_upload_fp = NULL;
    ESP_LOGI(TAG, "upload complete");
    return true;
}

// ---- 下载(读) ----
void file_xfer_download(const char *name, file_xfer_notify_t notify_cb)
{
    if (!notify_cb || !file_xfer_mount()) return;

    if (strchr(name, '/') || strchr(name, '\\')) {
        uint8_t endf[2] = {0x94, 1}; // fail
        notify_cb(endf, 2);
        return;
    }

    char path[288];
    snprintf(path, sizeof(path), MOUNT_POINT "/%s", name);
    FILE *fp = fopen(path, "r");
    if (!fp) {
        ESP_LOGW(TAG, "download: %s not found", name);
        uint8_t endf[2] = {0x94, 1};
        notify_cb(endf, 2);
        return;
    }

    // 获取文件大小
    fseek(fp, 0, SEEK_END);
    long fsize = ftell(fp);
    fseek(fp, 0, SEEK_SET);

    uint16_t total_chunks = (uint16_t)((fsize + CHUNK_SIZE - 1) / CHUNK_SIZE);
    if (total_chunks == 0) total_chunks = 1; // 空文件也发一帧

    uint16_t seq = 0;
    uint8_t buf[256];
    while (1) {
        size_t n = fread(buf + 4, 1, CHUNK_SIZE, fp);
        buf[0] = 0x93;
        buf[1] = seq & 0xFF;
        buf[2] = total_chunks & 0xFF;
        buf[3] = (uint8_t)n;
        notify_cb(buf, 4 + (uint16_t)n);
        seq++;
        if (n < CHUNK_SIZE) break;
    }
    fclose(fp);

    // 发送结束帧
    uint8_t endf[2] = {0x94, 0}; // ok
    notify_cb(endf, 2);

    ESP_LOGI(TAG, "download: %s (%ld bytes, %d chunks)", name, fsize, seq);
}

// ---- 删除 ----
bool file_xfer_delete(const char *name)
{
    if (!file_xfer_mount()) return false;
    if (strchr(name, '/') || strchr(name, '\\')) return false;

    char path[288];
    snprintf(path, sizeof(path), MOUNT_POINT "/%s", name);
    int rc = unlink(path);
    if (rc != 0) {
        ESP_LOGW(TAG, "delete %s failed: %d", name, rc);
        return false;
    }
    ESP_LOGI(TAG, "deleted %s", name);
    return true;
}

// ---- 目录 ----
bool file_xfer_mkdir(const char *name)
{
    if (!file_xfer_mount()) return false;
    if (strchr(name, '/') || strchr(name, '\\')) return false;

    char path[288];
    snprintf(path, sizeof(path), MOUNT_POINT "/%s", name);
    int rc = mkdir(path, 0755);
    if (rc != 0) {
        ESP_LOGW(TAG, "mkdir %s failed: %d", name, rc);
        return false;
    }
    ESP_LOGI(TAG, "mkdir %s", name);
    return true;
}

bool file_xfer_rmdir(const char *name)
{
    if (!file_xfer_mount()) return false;
    if (strchr(name, '/') || strchr(name, '\\')) return false;

    char path[288];
    snprintf(path, sizeof(path), MOUNT_POINT "/%s", name);
    int rc = rmdir(path);
    if (rc != 0) {
        ESP_LOGW(TAG, "rmdir %s failed: %d", name, rc);
        return false;
    }
    ESP_LOGI(TAG, "rmdir %s", name);
    return true;
}

// ---- 重命名（文件/文件夹）----
bool file_xfer_rename(const char *old_name, const char *new_name)
{
    if (!file_xfer_mount()) return false;
    if (strchr(old_name, '/') || strchr(old_name, '\\') ||
        strchr(new_name, '/') || strchr(new_name, '\\')) return false;

    char oldp[288], newp[288];
    snprintf(oldp, sizeof(oldp), MOUNT_POINT "/%s", old_name);
    snprintf(newp, sizeof(newp), MOUNT_POINT "/%s", new_name);
    int rc = rename(oldp, newp);
    if (rc != 0) {
        ESP_LOGW(TAG, "rename %s -> %s failed: %d", old_name, new_name, rc);
        return false;
    }
    ESP_LOGI(TAG, "renamed %s -> %s", old_name, new_name);
    return true;
}

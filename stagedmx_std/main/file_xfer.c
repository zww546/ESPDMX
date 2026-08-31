/**
 * file_xfer.c — ESP32 灯库文件 Wi-Fi-less 传输（支持子目录）
 *
 * 存储位置: /fw (storage 分区, FAT/wear-leveling, 2MB)
 * 所有操作都带 dir 参数（相对 /fw 的目录路径，可为空串 = 根目录）：
 *   dir 允许多级（如 "a/b"，不含前导/结尾 '/'，不含 ".." 与 '\'）
 *   name 为单级条目名（不含 '/' '\' ".."）
 *
 * 协议帧格式:
 *   App→ESP: 0x31 UPLOAD_START  dirLen dir… nameLen name… sizeHi sizeLo
 *             0x32 UPLOAD_CHUNK  seq data…
 *             0x33 UPLOAD_END
 *             0x34 LIST_FILES    [dirLen dir…]（无参数 = 根目录）
 *             0x35 DOWNLOAD      dirLen dir… nameLen name…
 *             0x36 DELETE        dirLen dir… nameLen name…
 *             0x37 MKDIR         dirLen dir… nameLen name…
 *             0x38 RMDIR         dirLen dir… nameLen name…
 *             0x39 RENAME        dirLen dir… oldLen old… newLen new…
 *             0x3A MOVE          dirLen dir… nameLen name… dstDirLen dstDir…
 *             0x3B COPY          dirLen dir… nameLen name… dstDirLen dstDir…
 *             0x3C LIST_DIRS     （全量目录树，回 0x97 多帧 + 0x98 结束帧）
 *   ESP→App: 0x91 UPLOAD_RESULT  status(0=ok)
 *             0x92 FILE_LIST     count [nameLen name type sizeHi sizeLo]×count
 *             0x93 FILE_CHUNK    seq totalChunks dataLen data…
 *             0x94 FILE_END      status(0=ok 1=not found)
 *             0x95 DELETE_RESULT status
 *             0x96 DIR_RESULT    status（mkdir/rmdir/rename/move/copy）
 *             0x97 DIRS_LIST     count [dirLen dir…]×count
 *             0x98 DIRS_END      （全量目录收集完成）
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

// ---- 路径辅助 ----
// dir 允许多级，但不能以 '/' 开头/结尾、不含 ".." 与 '\'
static bool path_valid(const char *p)
{
    if (!p) return false;
    if (p[0] == '/') return false;
    size_t l = strlen(p);
    if (l == 0) return true;
    if (p[l - 1] == '/') return false;
    if (strstr(p, "..") || strchr(p, '\\')) return false;
    return true;
}

// name 为单级条目：非空，不含 '/' '\' ".."
static bool name_valid(const char *n)
{
    if (!n || !n[0]) return false;
    if (strchr(n, '/') || strchr(n, '\\') || strstr(n, "..")) return false;
    return true;
}

// 构造 /fw/<dir>/<name>（dir 可为空串；name 为 NULL 时只到 dir）
static void build_path(char *out, size_t outsz, const char *dir, const char *name)
{
    if (dir && dir[0]) {
        if (name && name[0]) snprintf(out, outsz, MOUNT_POINT "/%s/%s", dir, name);
        else                 snprintf(out, outsz, MOUNT_POINT "/%s", dir);
    } else {
        if (name && name[0]) snprintf(out, outsz, MOUNT_POINT "/%s", name);
        else                 snprintf(out, outsz, MOUNT_POINT);
    }
}

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
// 帧: 0x92 count [nameLen(1B) name type sizeHi sizeLo]×count
void file_xfer_list(const char *dir, file_xfer_notify_t notify_cb)
{
    if (!notify_cb || !file_xfer_mount()) return;
    if (!path_valid(dir)) return;

    char base[512];
    build_path(base, sizeof(base), dir, NULL);

    DIR *d = opendir(base);
    if (!d) return;

    // 第一遍: 收集所有条目（文件夹 + 文件），type=1 目录 / 0 文件
    // 注意: 该函数运行在 NimBLE host_task（默认仅 4KB 栈）内，
    //       大数组绝不能放栈上（会栈溢出破坏 mbuf 池导致随机崩溃），
    //       因此用 static 存放。list 为同步单线程调用，static 安全。
    static struct {
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
            files[count].name[127] = '\0';          // 防御: 超长名强制终止
            files[count].size = 0;
            files[count].is_dir = true;
            count++;
        } else if (ent->d_type == DT_REG) {
            char path[1024];
            snprintf(path, sizeof(path), "%s/%s", base, ent->d_name);
            struct stat st;
            if (stat(path, &st) != 0) continue;
            strncpy(files[count].name, ent->d_name, 127);
            files[count].name[127] = '\0';          // 防御: 超长名强制终止
            files[count].size = (uint32_t)st.st_size;
            files[count].is_dir = false;
            count++;
        }
    }
    closedir(d);

    // 第二遍: 分包发送(每包最多 6 个记录，保持单帧 512 以内)
    // 帧: 0x92 count [nameLen name type sizeHi sizeLo]*
    // 注意: 单帧载荷必须 < ATT MTU-3(509)，且 buf 只有 512；
    //       长文件名会导致 6 条放不下，按可用空间截断本批。
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
            // 防御: 放不下本条记录则提前结束本批（至少能放下 1 条，见下）
            if (pos + 1 + nl + 3 > 500) { batch = i; break; }
            buf[pos++] = nl;
            memcpy(&buf[pos], files[idx].name, nl); pos += nl;
            buf[pos++] = files[idx].is_dir ? 1 : 0;
            buf[pos++] = (files[idx].size >> 8) & 0xFF;
            buf[pos++] = files[idx].size & 0xFF;
        }
        if (batch == 0) batch = 1;   // 单条超 500 只可能因 name 过长，兜底发 1 条
        buf[1] = (uint8_t)batch;
        notify_cb(buf, pos);
        offset += batch;
    }

    // 空列表也必须回一帧，否则 App 端永远等不到响应（无法显示"没有灯库"）
    if (count == 0) {
        uint8_t empty[2] = {0x92, 0};
        notify_cb(empty, 2);
    }

    ESP_LOGI(TAG, "listed %d entries in '%s'", count, dir && dir[0] ? dir : "/");
}

// ---- 全量目录树 ----
// 递归收集 /fw 下所有目录（含多级），回 0x97 多帧（每帧 ≤8 条）+ 0x98 结束帧
static void list_dirs_rec(const char *base, const char *rel,
                          file_xfer_notify_t notify_cb,
                          uint8_t *buf, int *pos, int *nframe)
{
    DIR *d = opendir(base);
    if (!d) return;
    struct dirent *ent;
    while ((ent = readdir(d)) != NULL) {
        if (ent->d_type != DT_DIR || ent->d_name[0] == '.') continue;

        char child_rel[512];
        if (rel && rel[0]) snprintf(child_rel, sizeof(child_rel), "%s/%s", rel, ent->d_name);
        else               snprintf(child_rel, sizeof(child_rel), "%s", ent->d_name);
        if (strlen(child_rel) > 250) continue;   // 协议 dirLen 上限 255，过深目录跳过

        uint8_t dl = (uint8_t)strlen(child_rel);
        // 本帧放不下 → 先发当前帧再开新帧（目录路径 ≤250，必能放入）
        if (*pos + 1 + dl > 500) {
            buf[1] = (uint8_t)*nframe;
            notify_cb(buf, *pos);
            *pos = 2; *nframe = 0;
        }
        buf[(*pos)++] = dl;
        memcpy(&buf[*pos], child_rel, dl); *pos += dl;
        (*nframe)++;

        char child_base[512];
        snprintf(child_base, sizeof(child_base), "%s/%s", base, ent->d_name);
        list_dirs_rec(child_base, child_rel, notify_cb, buf, pos, nframe);
    }
    closedir(d);
}

void file_xfer_list_dirs(file_xfer_notify_t notify_cb)
{
    if (!notify_cb || !file_xfer_mount()) return;
    uint8_t buf[512];
    buf[0] = 0x97;
    buf[1] = 0;   // 本帧目录数（发帧时填写）
    int pos = 2, nframe = 0;
    list_dirs_rec(MOUNT_POINT, "", notify_cb, buf, &pos, &nframe);
    if (nframe > 0) {
        buf[1] = (uint8_t)nframe;
        notify_cb(buf, pos);
    } else {
        uint8_t empty[2] = {0x97, 0};
        notify_cb(empty, 2);
    }
    uint8_t endf[2] = {0x98, 0};
    notify_cb(endf, 2);
    ESP_LOGI(TAG, "dirs collected");
}

// ---- 上传(写) ----
bool file_xfer_upload_begin(const char *dir, const char *name, uint32_t size)
{
    if (!file_xfer_mount()) return false;
    if (!path_valid(dir) || !name_valid(name)) return false;
    if (s_upload_fp) { fclose(s_upload_fp); s_upload_fp = NULL; }

    char path[512];
    build_path(path, sizeof(path), dir, name);
    s_upload_fp = fopen(path, "w");
    if (!s_upload_fp) {
        ESP_LOGE(TAG, "upload: fopen %s failed (errno=%d: %s)", path, errno, strerror(errno));
        return false;
    }
    ESP_LOGI(TAG, "upload begin: %s (%lu bytes)", path, size);
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
void file_xfer_download(const char *dir, const char *name, file_xfer_notify_t notify_cb)
{
    if (!notify_cb || !file_xfer_mount()) return;
    if (!path_valid(dir) || !name_valid(name)) {
        uint8_t endf[2] = {0x94, 1}; // fail
        notify_cb(endf, 2);
        return;
    }

    char path[512];
    build_path(path, sizeof(path), dir, name);
    FILE *fp = fopen(path, "r");
    if (!fp) {
        ESP_LOGW(TAG, "download: %s not found", path);
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

    ESP_LOGI(TAG, "download: %s (%ld bytes, %d chunks)", path, fsize, seq);
}

// ---- 删除 ----
bool file_xfer_delete(const char *dir, const char *name)
{
    if (!file_xfer_mount()) return false;
    if (!path_valid(dir) || !name_valid(name)) return false;

    char path[512];
    build_path(path, sizeof(path), dir, name);
    int rc = unlink(path);
    if (rc != 0) {
        ESP_LOGW(TAG, "delete %s failed: %d", path, rc);
        return false;
    }
    ESP_LOGI(TAG, "deleted %s", path);
    return true;
}

// ---- 目录 ----
bool file_xfer_mkdir(const char *dir, const char *name)
{
    if (!file_xfer_mount()) return false;
    if (!path_valid(dir) || !name_valid(name)) return false;

    char path[512];
    build_path(path, sizeof(path), dir, name);
    int rc = mkdir(path, 0755);
    if (rc != 0) {
        ESP_LOGW(TAG, "mkdir %s failed: %d", path, rc);
        return false;
    }
    ESP_LOGI(TAG, "mkdir %s", path);
    return true;
}

bool file_xfer_rmdir(const char *dir, const char *name)
{
    if (!file_xfer_mount()) return false;
    if (!path_valid(dir) || !name_valid(name)) return false;

    char path[512];
    build_path(path, sizeof(path), dir, name);
    int rc = rmdir(path);
    if (rc != 0) {
        ESP_LOGW(TAG, "rmdir %s failed: %d", path, rc);
        return false;
    }
    ESP_LOGI(TAG, "rmdir %s", path);
    return true;
}

// ---- 重命名（文件/文件夹）----
bool file_xfer_rename(const char *dir, const char *old_name, const char *new_name)
{
    if (!file_xfer_mount()) return false;
    if (!path_valid(dir) || !name_valid(old_name) || !name_valid(new_name)) return false;

    char oldp[512], newp[512];
    build_path(oldp, sizeof(oldp), dir, old_name);
    build_path(newp, sizeof(newp), dir, new_name);
    int rc = rename(oldp, newp);
    if (rc != 0) {
        ESP_LOGW(TAG, "rename %s -> %s failed: %d", oldp, newp, rc);
        return false;
    }
    ESP_LOGI(TAG, "renamed %s -> %s", oldp, newp);
    return true;
}

// ---- 移动（条目 → 目标文件夹；dst_dir 为空串 = 根目录）----
bool file_xfer_move(const char *dir, const char *name, const char *dst_dir)
{
    if (!file_xfer_mount()) return false;
    if (!path_valid(dir) || !name_valid(name) || !path_valid(dst_dir)) return false;

    char src[512], dst[512];
    build_path(src, sizeof(src), dir, name);
    build_path(dst, sizeof(dst), dst_dir, name);
    int rc = rename(src, dst);
    if (rc != 0) {
        ESP_LOGW(TAG, "move %s -> %s failed: %d", src, dst, rc);
        return false;
    }
    ESP_LOGI(TAG, "moved %s -> %s", src, dst);
    return true;
}

// ---- 复制（文件 / 文件夹递归）----
static bool copy_one_file(const char *src, const char *dst)
{
    FILE *in = fopen(src, "rb");
    if (!in) return false;
    FILE *out = fopen(dst, "wb");
    if (!out) { fclose(in); return false; }
    uint8_t buf[512];
    size_t n;
    bool ok = true;
    while ((n = fread(buf, 1, sizeof(buf), in)) > 0) {
        if (fwrite(buf, 1, n, out) != n) { ok = false; break; }
    }
    fclose(in);
    fclose(out);
    return ok;
}

static bool copy_tree(const char *src, const char *dst)
{
    struct stat st;
    if (stat(src, &st) != 0) return false;
    if (S_ISDIR(st.st_mode)) {
        if (mkdir(dst, 0755) != 0 && errno != EEXIST) return false;
        DIR *d = opendir(src);
        if (!d) return false;
        struct dirent *ent;
        bool ok = true;
        while ((ent = readdir(d)) != NULL && ok) {
            if (ent->d_name[0] == '.') continue;
            char sp[512], dp[512];
            snprintf(sp, sizeof(sp), "%s/%s", src, ent->d_name);
            snprintf(dp, sizeof(dp), "%s/%s", dst, ent->d_name);
            ok = copy_tree(sp, dp);
        }
        closedir(d);
        return ok;
    }
    return copy_one_file(src, dst);
}

bool file_xfer_copy(const char *dir, const char *name, const char *dst_dir)
{
    if (!file_xfer_mount()) return false;
    if (!path_valid(dir) || !name_valid(name) || !path_valid(dst_dir)) return false;

    char src[512], dst[512];
    build_path(src, sizeof(src), dir, name);
    build_path(dst, sizeof(dst), dst_dir, name);

    // 禁止复制到自身（源与目标同路径）或复制到自己的子目录
    if (strcmp(src, dst) == 0) return false;
    size_t sl = strlen(src);
    if (strncmp(dst, src, sl) == 0 && dst[sl] == '/') return false;

    bool ok = copy_tree(src, dst);
    if (!ok) {
        ESP_LOGW(TAG, "copy %s -> %s failed", src, dst);
        return false;
    }
    ESP_LOGI(TAG, "copied %s -> %s", src, dst);
    return true;
}

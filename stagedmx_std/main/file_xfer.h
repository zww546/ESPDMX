#pragma once
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

/** 挂载 storage 分区到 /fw (如未挂载则自动挂载)。返回 true 表示已就绪。 */
bool file_xfer_mount(void);

/** /fw 当前是否已挂载。 */
bool file_xfer_is_mounted(void);

/** 卸载 /fw（供 USB MSC U盘模式占用分区前调用）。 */
void file_xfer_unmount(void);

/** 遍历 /fw/<dir> 下所有条目（文件+文件夹），通过 notify_cb 分批发送文件列表。
 *  dir 为相对 /fw 的目录（空串 = 根目录，允许多级如 "a/b"，不含 ".."）。
 *  notify_cb 签名为 void(uint8_t *data, uint16_t len)，data 为已构建好的 BLE 帧。 */
typedef void (*file_xfer_notify_t)(const uint8_t *data, uint16_t len);
void file_xfer_list(const char *dir, file_xfer_notify_t notify_cb);

/** 遍历 /fw 全量目录树，发送 0x97 多帧 + 0x98 结束帧。 */
void file_xfer_list_dirs(file_xfer_notify_t notify_cb);

/** 开始上传: 在 /fw/<dir> 下创建文件。成功返 true。 */
bool file_xfer_upload_begin(const char *dir, const char *name, uint32_t size);

/** 写入一个数据块到当前上传文件。返回写入的字节数，<=0 表示失败。 */
int file_xfer_upload_chunk(const uint8_t *data, uint16_t len);

/** 结束上传: 关闭文件。返回 true 表示成功。 */
bool file_xfer_upload_end(void);

/** 开始下载: 打开 /fw/<dir>/<name> 文件，通过 notify_cb 分批发送数据块(0x93)和结束帧(0x94)。
 *  注意: 这个函数会同步完成所有分块发送，调用后会阻塞直到发送完毕。*/
void file_xfer_download(const char *dir, const char *name, file_xfer_notify_t notify_cb);

/** 删除 /fw/<dir> 下指定文件。成功返回 true。 */
bool file_xfer_delete(const char *dir, const char *name);

/** 在 /fw/<dir> 下创建文件夹（name 不含路径分隔符）。成功返回 true。 */
bool file_xfer_mkdir(const char *dir, const char *name);

/** 删除 /fw/<dir> 下空文件夹（name 不含路径分隔符）。成功返回 true。 */
bool file_xfer_rmdir(const char *dir, const char *name);

/** 重命名 /fw/<dir> 下文件或文件夹。成功返回 true。 */
bool file_xfer_rename(const char *dir, const char *old_name, const char *new_name);

/** 移动 /fw/<dir>/<name> 到目标文件夹 /fw/<dst_dir>/<name>
 *  （dst_dir 为空串 = 根目录；目标文件夹必须已存在）。成功返回 true。 */
bool file_xfer_move(const char *dir, const char *name, const char *dst_dir);

/** 复制 /fw/<dir>/<name> 到 /fw/<dst_dir>/<name>（文件夹递归复制）。
 *  禁止复制到自身或自己的子目录。成功返回 true。 */
bool file_xfer_copy(const char *dir, const char *name, const char *dst_dir);

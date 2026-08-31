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

/** 遍历 /fw 下所有 .xml 文件，通过 notify_cb 分批发送文件列表。
 *  notify_cb 签名为 void(uint8_t *data, uint16_t len)，data 为已构建好的 BLE 帧。 */
typedef void (*file_xfer_notify_t)(const uint8_t *data, uint16_t len);
void file_xfer_list(file_xfer_notify_t notify_cb);

/** 开始上传: 在 /fw 下创建文件，返回文件描述符指针(内部维护)。成功返 true。 */
bool file_xfer_upload_begin(const char *name, uint32_t size);

/** 写入一个数据块到当前上传文件。返回写入的字节数，<=0 表示失败。 */
int file_xfer_upload_chunk(const uint8_t *data, uint16_t len);

/** 结束上传: 关闭文件。返回 true 表示成功。 */
bool file_xfer_upload_end(void);

/** 开始下载: 打开 /fw/name 文件，通过 notify_cb 分批发送数据块(0x93)和结束帧(0x94)。
 *  注意: 这个函数会同步完成所有分块发送，调用后会阻塞直到发送完毕。*/
void file_xfer_download(const char *name, file_xfer_notify_t notify_cb);

/** 删除 /fw 下指定文件。成功返回 true。 */
bool file_xfer_delete(const char *name);

/** 在 /fw 下创建文件夹（name 不含路径分隔符）。成功返回 true。 */
bool file_xfer_mkdir(const char *name);

/** 删除 /fw 下空文件夹（name 不含路径分隔符）。成功返回 true。 */
bool file_xfer_rmdir(const char *name);

/** 重命名 /fw 下文件或文件夹。成功返回 true。 */
bool file_xfer_rename(const char *old_name, const char *new_name);

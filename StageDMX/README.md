# StageDMX — 便携舞台灯 DMX 控制器（Android）

手机 App 通过 **BLE** 连接 **ESP32-S3 + SP3485**，把通道数据转成 **DMX512** 驱动舞台灯具（帕灯 / 摇头灯 / 染色灯等）。

> 摇头灯、帕灯本质都是 DMX512 通道控制，所以**通用通道滑条即可控制一切灯具**；具体灯型的“颜色/摇杆”界面只是通道的可视化语义糖，后续可叠加。

## 功能（v1）
- BLE 扫描 / 连接（按 DMX 服务过滤，支持“扫描全部”兜底）
- 通用 512 通道，滑条实时调光（0–255），可设置显示通道数
- 全黑 / 全亮 一键
- 场景保存 / 调用 / 删除（本地持久化，512 通道快照）
- ~30Hz 节流批量下发 + BLE 写队列串行化，拖动流畅不卡死

## 技术栈
- Kotlin + ViewBinding + Coroutines
- AGP 8.11.1 / Kotlin 2.1.0 / Gradle 8.13（腾讯镜像）/ compileSdk 36 / minSdk 26 / targetSdk 36
- 依赖走阿里云镜像；无第三方 BLE 库，直接用 Android 原生 `BluetoothGatt`

## 工程结构
```
app/src/main/java/com/example/stagedmx/
  DmxProtocol.kt   # UUID + 帧编码（与 ESP32 端约定，见 PROTOCOL.md）
  BleManager.kt    # 扫描/连接/MTU/通知/写队列（兼容 API 26~34）
  DmxEngine.kt     # 512 通道状态 + 节流发送 + 整帧下发
  SceneStore.kt    # 场景持久化（SharedPreferences + Base64）
  ChannelAdapter.kt# 通道滑条列表
  MainActivity.kt  # 权限/开蓝牙/UI 编排/场景
```

## 编译运行
> WSL 无法编译（SDK 是 Windows 侧），需在 Windows 的 Android Studio 打开本工程 Sync + Run。

1. Android Studio 打开 `E:\Desktop\AndroidStudioProjects\StageDMX`
2. Gradle Sync（首次下依赖，走阿里云/腾讯镜像）
3. 真机运行（BLE 需真机，模拟器无蓝牙）；首次点“扫描连接”会申请蓝牙权限

## 权限
- Android 12+：`BLUETOOTH_SCAN`(neverForLocation) + `BLUETOOTH_CONNECT`
- Android 11-：`BLUETOOTH` + `BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION`

## ESP32 端
见 `PROTOCOL.md`。固件需实现相同 GATT（服务 0xFF00 / 写 0xFF01 / 通知 0xFF02），
BLE 收帧更新 512 字节缓冲，另用定时任务把缓冲以 DMX512（250k 8N2 + Break/MAB）从 SP3485 循环输出。

## 后续可扩展
- 帕灯 / 摇头灯灯型模板（通道语义化：RGB 调色盘、Pan/Tilt 摇杆）
- 多设备同时连接与分组
- 场景淡入淡出（Fade）、Chase 走灯、音乐律动

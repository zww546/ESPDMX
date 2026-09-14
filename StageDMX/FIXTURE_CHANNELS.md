# 灯库通道读取清单（`FixtureParser` 实际解析结果）

本文件由解析器逻辑对 `release_assets` 里的样例灯库逐通道跑出来，用于核对“是否读完所有通道”。每行 = DMX 通道号 → App 显示名（原始名/attribute）。

> 修好之后：**每种灯库解析出的通道号与灯库文件声明的通道数完全一致**（细调/fine 段也已补成独立通道）。

## Ares-P7 34CH.R20

- 灯型：**OMARTE Ares-P7 34CH / r20**（Pearl R20）
- 通道总数：**34**，解析到定义 **34** 个，**无缺失**

| 通道 | attribute / 原始名 | App 显示 |
|---|---|---|
| 1 | dim | 调光 |
| 2 | shutter | 频闪 |
| 3 | color1 | 色盘 |
| 4 | colorcmy1 | 青 |
| 5 | colorcmy2 | 品红 |
| 6 | colorcmy3 | 黄 |
| 7 | - | CTC |
| 8 | iris | 光圈 |
| 9 | gobo1 | 图案盘1 |
| 10 | gobo1_pos | 图案盘1旋转 |
| 11 | gobo2 | 图案盘2 |
| 12 | - | BLADE1 |
| 13 | - | BLADE2 |
| 14 | - | BLADE3 |
| 15 | - | BLADE4 |
| 16 | - | BLADE5 |
| 17 | - | BLADE66 |
| 18 | - | BLADE7 |
| 19 | - | BLADE8 |
| 20 | - | FRAMING ROT |
| 21 | - | Animation |
| 22 | prisma1 | 棱镜1 |
| 23 | prisma1_pos | 棱镜1旋转 |
| 24 | - | Prism2 |
| 25 | - | Prism2Rot |
| 26 | zoom | 放大 |
| 27 | zoom | 放大 |
| 28 | pan | 水平 |
| 29 | pan_fine | 水平微调 |
| 30 | tilt | 垂直 |
| 31 | tilt_fine | 垂直微调 |
| 32 | - | P/T SPEED |
| 33 | - | Lamp Control |
| 34 | - | Control |

## Ares-P7 39CH.R20

- 灯型：**OMARTE Ares-P7 39CH / r20**（Pearl R20）
- 通道总数：**39**，解析到定义 **39** 个，**无缺失**

| 通道 | attribute / 原始名 | App 显示 |
|---|---|---|
| 1 | dim | 调光 |
| 2 | dim_fine | 调光微调 |
| 3 | shutter | 频闪 |
| 4 | color1 | 色盘 |
| 5 | colorcmy1 | 青 |
| 6 | colorcmy2 | 品红 |
| 7 | colorcmy3 | 黄 |
| 8 | - | CTC |
| 9 | - | Macro Color |
| 10 | iris | 光圈 |
| 11 | gobo1 | 图案盘1 |
| 12 | gobo1_pos | 图案盘1旋转 |
| 13 | gobo1_pos_fine | 图案盘1旋转微调 |
| 14 | gobo2 | 图案盘2 |
| 15 | - | BLADE1 |
| 16 | - | BLADE2 |
| 17 | - | BLADE3 |
| 18 | - | BLADE4 |
| 19 | - | BLADE5 |
| 20 | - | BLADE66 |
| 21 | - | BLADE7 |
| 22 | - | BLADE8 |
| 23 | - | FRAMING ROT |
| 24 | - | Animation |
| 25 | prisma1 | 棱镜1 |
| 26 | prisma1_pos | 棱镜1旋转 |
| 27 | - | Prism2 |
| 28 | - | Prism2Rot |
| 29 | zoom | 放大 |
| 30 | zoom_fine | 放大微调 |
| 31 | zoom | 放大 |
| 32 | zoom_fine | 放大微调 |
| 33 | pan | 水平 |
| 34 | pan_fine | 水平微调 |
| 35 | tilt | 垂直 |
| 36 | tilt_fine | 垂直微调 |
| 37 | - | P/T SPEED |
| 38 | - | Lamp Control |
| 39 | - | Control |

## OMARTE_ARES-P7.d4

- 灯型：**OMARTE ARES-P6 / 39 Channels**（Titan D4）
- 通道总数：**39**，解析到定义 **39** 个，**无缺失**

| 通道 | attribute / 原始名 | App 显示 |
|---|---|---|
| 1 | dim | 调光 |
| 2 | dim_fine | 调光微调 |
| 3 | shutter | 频闪 |
| 4 | color1 | 色盘 |
| 5 | colorcmy1 | 青 |
| 6 | colorcmy2 | 品红 |
| 7 | colorcmy3 | 黄 |
| 8 | ctc | 色温 |
| 9 | colormacros | 色盘宏 |
| 10 | iris | 光圈 |
| 11 | gobo1 | 图案盘1 |
| 12 | gobo1_pos | 图案盘1旋转 |
| 13 | gobo1_pos_fine | 图案盘1旋转微调 |
| 14 | gobo2 | 图案盘2 |
| 15 | blade1a | 切割1 |
| 16 | blade1b | 切割2 |
| 17 | blade2a | 切割3 |
| 18 | blade2b | 切割4 |
| 19 | blade3a | 切割5 |
| 20 | blade3b | 切割6 |
| 21 | blade4a | 切割7 |
| 22 | blade4b | 切割8 |
| 23 | shaper_rot | 切割旋转 |
| 24 | animationwheel | 动画轮 |
| 25 | prisma1 | 棱镜1 |
| 26 | prisma1_pos | 棱镜1旋转 |
| 27 | prisma1 | 棱镜1 |
| 28 | prisma1_pos | 棱镜1旋转 |
| 29 | focus | 调焦 |
| 30 | focus_fine | 调焦微调 |
| 31 | zoom | 放大 |
| 32 | zoom_fine | 放大微调 |
| 33 | pan | 水平 |
| 34 | pan_fine | 水平微调 |
| 35 | tilt | 垂直 |
| 36 | tilt_fine | 垂直微调 |
| 37 | ptspeed | 水平垂直速度 |
| 38 | lampcontrol | 灯泡控制 |
| 39 | fixtureglobalreset | 全局复位 |

## OMARTE_ARES-P7.d4

- 灯型：**OMARTE ARES-P6 / 34 Channels**（Titan D4）
- 通道总数：**34**，解析到定义 **33** 个，未定义通道号 [8]

| 通道 | attribute / 原始名 | App 显示 |
|---|---|---|
| 1 | dim | 调光 |
| 2 | dim_fine | 调光微调 |
| 3 | color1 | 色盘 |
| 4 | colorcmy1 | 青 |
| 5 | colorcmy2 | 品红 |
| 6 | colorcmy3 | 黄 |
| 7 | ctc | 色温 |
| 9 | iris | 光圈 |
| 10 | gobo1 | 图案盘1 |
| 11 | gobo1_pos | 图案盘1旋转 |
| 12 | gobo1_pos_fine | 图案盘1旋转微调 |
| 13 | blade1a | 切割1 |
| 14 | blade1b | 切割2 |
| 15 | blade2a | 切割3 |
| 16 | blade2b | 切割4 |
| 17 | blade3a | 切割5 |
| 18 | blade3b | 切割6 |
| 19 | blade4a | 切割7 |
| 20 | blade4b | 切割8 |
| 21 | shaper_rot | 切割旋转 |
| 22 | prisma1 | 棱镜1 |
| 23 | prisma1_pos | 棱镜1旋转 |
| 24 | prisma1 | 棱镜1 |
| 25 | prisma1_pos | 棱镜1旋转 |
| 26 | focus | 调焦 |
| 27 | focus_fine | 调焦微调 |
| 28 | pan | 水平 |
| 29 | pan_fine | 水平微调 |
| 30 | tilt | 垂直 |
| 31 | tilt_fine | 垂直微调 |
| 32 | ptspeed | 水平垂直速度 |
| 33 | lampcontrol | 灯泡控制 |
| 34 | fixtureglobalreset | 全局复位 |

## omarte@ares-p7@34_channels.xml

- 灯型：**Omarte ARES-P7 / 34 Channels**（MA2 XML）
- 通道总数：**34**，解析到定义 **34** 个，**无缺失**

| 通道 | attribute / 原始名 | App 显示 |
|---|---|---|
| 1 | DIM | 调光 |
| 2 | SHUTTER | 频闪 |
| 3 | COLOR1 | 色盘 |
| 4 | COLORRGB1 | 红/C |
| 5 | COLORRGB2 | 绿/M |
| 6 | COLORRGB3 | 蓝/Y |
| 7 | CTC | 色温 |
| 8 | IRIS | 光圈 |
| 9 | GOBO1 | 图案盘1 |
| 10 | GOBO1_POS | 图案盘1旋转 |
| 11 | GOBO2 | 图案盘2 |
| 12 | BLADE1A | 切割1 |
| 13 | BLADE1B | 切割2 |
| 14 | BLADE2A | 切割3 |
| 15 | BLADE2B | 切割4 |
| 16 | BLADE3A | 切割5 |
| 17 | BLADE3B | 切割6 |
| 18 | BLADE4A | 切割7 |
| 19 | BLADE4B | 切割8 |
| 20 | SHAPER ROT | Index |
| 21 | ANIMATIONWHEEL | 动画轮 |
| 22 | PRISMA1 | 棱镜1 |
| 23 | PRISMA1_POS | 棱镜1旋转 |
| 24 | PRISMA2 | 棱镜2 |
| 25 | PRISMA2_POS | 棱镜2旋转 |
| 26 | FOCUS | 调焦 |
| 27 | ZOOM | 放大 |
| 28 | PAN | 水平 |
| 29 | PAN_FINE | 水平微调 |
| 30 | TILT | 垂直 |
| 31 | TILT_FINE | 垂直微调 |
| 32 | PTSPEED | 水平垂直速度 |
| 33 | LAMPCONTROL | 灯泡控制 |
| 34 | FIXTUREGLOBALRESET | 全局复位 |

## omarte@ares-p7@39_channels.xml

- 灯型：**Omarte ARES-P7 / 39 Channels**（MA2 XML）
- 通道总数：**39**，解析到定义 **39** 个，**无缺失**

| 通道 | attribute / 原始名 | App 显示 |
|---|---|---|
| 1 | DIM | 调光 |
| 2 | DIM_FINE | 调光微调 |
| 3 | SHUTTER | 频闪 |
| 4 | COLOR1 | 色盘 |
| 5 | COLORRGB1 | 红/C |
| 6 | COLORRGB2 | 绿/M |
| 7 | COLORRGB3 | 蓝/Y |
| 8 | CTC | 色温 |
| 9 | COLOR1_MARCO | 颜色宏 |
| 10 | IRIS | 光圈 |
| 11 | GOBO1 | 图案盘1 |
| 12 | GOBO1_POS | 图案盘1旋转 |
| 13 | GOBO1_POS_FINE | 图案盘1旋转微调 |
| 14 | GOBO2 | 图案盘2 |
| 15 | BLADE1A | 切割1 |
| 16 | BLADE1B | 切割2 |
| 17 | BLADE2A | 切割3 |
| 18 | BLADE2B | 切割4 |
| 19 | BLADE3A | 切割5 |
| 20 | BLADE3B | 切割6 |
| 21 | BLADE4A | 切割7 |
| 22 | BLADE4B | 切割8 |
| 23 | SHAPER ROT | Index |
| 24 | ANIMATIONWHEEL | 动画轮 |
| 25 | PRISMA1 | 棱镜1 |
| 26 | PRISMA1_POS | 棱镜1旋转 |
| 27 | PRISMA2 | 棱镜2 |
| 28 | PRISMA2_POS | 棱镜2旋转 |
| 29 | FOCUS | 调焦 |
| 30 | FOCUS_FINE | 调焦微调 |
| 31 | ZOOM | 放大 |
| 32 | ZOOM_FINE | 放大微调 |
| 33 | PAN | 水平 |
| 34 | PAN_FINE | 水平微调 |
| 35 | TILT | 垂直 |
| 36 | TILT_FINE | 垂直微调 |
| 37 | PTSPEED | 水平垂直速度 |
| 38 | LAMPCONTROL | 灯泡控制 |
| 39 | FIXTUREGLOBALRESET | 全局复位 |

## omarte@eos-f1000a@17ch.xml

- 灯型：**Omarte Eos-F1000A / 17ch**（MA2 XML）
- 通道总数：**17**，解析到定义 **17** 个，**无缺失**

| 通道 | attribute / 原始名 | App 显示 |
|---|---|---|
| 1 | DIM | 调光 |
| 2 | SHUTTER | 频闪 |
| 3 | COLORRGB1 | 红/C |
| 4 | COLORRGB2 | 绿/M |
| 5 | COLORRGB3 | 蓝/Y |
| 6 | COLORRGB5 | 白 |
| 7 | SCROLLER | 色片 |
| 8 | COLORMACRORATE | 色片速度 |
| 9 | ZOOM | 放大 |
| 10 | ZOOMROTATION | 放大旋转 |
| 11 | PAN | 水平 |
| 12 | PAN_FINE | 水平微调 |
| 13 | TILT | 垂直 |
| 14 | TILT_FINE | 垂直微调 |
| 15 | CTO | 色温 |
| 16 | FIXTUREGLOBALRESET | 全局复位 |
| 17 | COLOR1_MARCO | 颜色宏 |

## omarte@eos-f1000a@19ch.xml

- 灯型：**Omarte Eos-F1000A / 19ch**（MA2 XML）
- 通道总数：**19**，解析到定义 **19** 个，**无缺失**

| 通道 | attribute / 原始名 | App 显示 |
|---|---|---|
| 1 | DIM | 调光 |
| 2 | SHUTTER | 频闪 |
| 3 | COLORRGB1 | 红/C |
| 4 | COLORRGB2 | 绿/M |
| 5 | COLORRGB3 | 蓝/Y |
| 6 | COLORRGB5 | 白 |
| 7 | COLORRGB1 | 红/C |
| 8 | COLORRGB2 | 绿/M |
| 9 | COLORRGB3 | 蓝/Y |
| 10 | COLORRGB5 | 白 |
| 11 | COLORMACROS | 色盘宏 |
| 12 | ZOOM | 放大 |
| 13 | ZOOMROTATION | 放大旋转 |
| 14 | PAN | 水平 |
| 15 | PAN_FINE | 水平微调 |
| 16 | TILT | 垂直 |
| 17 | TILT_FINE | 垂直微调 |
| 18 | CTO | 色温 |
| 19 | FIXTUREGLOBALRESET | 全局复位 |

## omarte_EOS-F1000@17ch.R20

- 灯型：**omarte EOS-F1000 / r20**（Pearl R20）
- 通道总数：**17**，解析到定义 **17** 个，**无缺失**

| 通道 | attribute / 原始名 | App 显示 |
|---|---|---|
| 1 | dim | 调光 |
| 2 | shutter | 频闪 |
| 3 | colorrgb1 | 红/C |
| 4 | colorrgb2 | 绿/M |
| 5 | colorrgb3 | 蓝/Y |
| 6 | color1 | 色盘 |
| 7 | prisma1 | 棱镜1 |
| 8 | prisma1_pos | 棱镜1旋转 |
| 9 | zoom | 放大 |
| 10 | focus | 调焦 |
| 11 | pan | 水平 |
| 12 | pan_fine | 水平微调 |
| 13 | tilt | 垂直 |
| 14 | tilt_fine | 垂直微调 |
| 15 | - | CTO |
| 16 | - | Control |
| 17 | - | Colour Macro |

## Omarte_EOS-F1000@19ch.d4

- 灯型：**Omarte EOS-F1000 / 19ch**（Titan D4）
- 通道总数：**19**，解析到定义 **19** 个，**无缺失**

| 通道 | attribute / 原始名 | App 显示 |
|---|---|---|
| 1 | dim | 调光 |
| 2 | shutter | 频闪 |
| 3 | colorrgb1 | 红/C |
| 4 | colorrgb2 | 绿/M |
| 5 | colorrgb3 | 蓝/Y |
| 6 | colorrgb5 | 白 |
| 7 | colorrgb1 | 红/C |
| 8 | colorrgb2 | 绿/M |
| 9 | colorrgb3 | 蓝/Y |
| 10 | colorrgb5 | 白 |
| 11 | colormacros | 色盘宏 |
| 12 | zoom | 放大 |
| 13 | zoomrotation | 放大旋转 |
| 14 | pan | 水平 |
| 15 | pan_fine | 水平微调 |
| 16 | tilt | 垂直 |
| 17 | tilt_fine | 垂直微调 |
| 18 | cto | 色温 |
| 19 | fixtureglobalreset | 全局复位 |

## omarte_EOS-F1000@17ch.d4

- 灯型：**omarte EOS-F1000 / 17Ch**（Titan D4）
- 通道总数：**17**，解析到定义 **17** 个，**无缺失**

| 通道 | attribute / 原始名 | App 显示 |
|---|---|---|
| 1 | dim | 调光 |
| 2 | shutter | 频闪 |
| 3 | colorrgb1 | 红/C |
| 4 | colorrgb2 | 绿/M |
| 5 | colorrgb3 | 蓝/Y |
| 6 | colorrgb5 | 白 |
| 7 | colormacros | 色盘宏 |
| 8 | colormacrorate | 色片速度 |
| 9 | zoom | 放大 |
| 10 | zoomrotation | 放大旋转 |
| 11 | pan | 水平 |
| 12 | pan_fine | 水平微调 |
| 13 | tilt | 垂直 |
| 14 | tilt_fine | 垂直微调 |
| 15 | cto | 色温 |
| 16 | fixtureglobalreset | 全局复位 |
| 17 | colormacros | 色盘宏 |

## omarte@seer-f550a@17ch.xml

- 灯型：**omarte Seer-F550A / 17ch**（MA2 XML）
- 通道总数：**17**，解析到定义 **17** 个，**无缺失**

| 通道 | attribute / 原始名 | App 显示 |
|---|---|---|
| 1 | DIM | 调光 |
| 2 | DIM_FINE | 调光微调 |
| 3 | SHUTTER | 频闪 |
| 4 | COLOR1 | 色盘 |
| 5 | GOBO1 | 图案盘1 |
| 6 | PRISMA1 | 棱镜1 |
| 7 | PRISMA2 | 棱镜2 |
| 8 | PRISMA2_POS | 棱镜2旋转 |
| 9 | FOCUS | 调焦 |
| 10 | FOCUS_FINE | 调焦微调 |
| 11 | PAN | 水平 |
| 12 | PAN_FINE | 水平微调 |
| 13 | TILT | 垂直 |
| 14 | TILT_FINE | 垂直微调 |
| 15 | FROST | 雾化 |
| 16 | LAMPCONTROL | 灯泡控制 |
| 17 | FIXTUREGLOBALRESET | 全局复位 |

## omarte_Seer-F550beam@17ch.R20

- 灯型：**omarte Seer-F550A / r20**（Pearl R20）
- 通道总数：**17**，解析到定义 **17** 个，**无缺失**

| 通道 | attribute / 原始名 | App 显示 |
|---|---|---|
| 1 | dim | 调光 |
| 2 | dim_fine | 调光微调 |
| 3 | shutter | 频闪 |
| 4 | color1 | 色盘 |
| 5 | gobo1 | 图案盘1 |
| 6 | - | FixPrism |
| 7 | prisma1 | 棱镜1 |
| 8 | prisma1_pos | 棱镜1旋转 |
| 9 | focus | 调焦 |
| 10 | focus_fine | 调焦微调 |
| 11 | pan | 水平 |
| 12 | pan_fine | 水平微调 |
| 13 | tilt | 垂直 |
| 14 | tilt_fine | 垂直微调 |
| 15 | frost | 雾化 |
| 16 | - | Lamp Control |
| 17 | - | Control |

## omarte_Seer-F550beam@17ch.d4

- 灯型：**omarte Seer-F550A / 17ch**（Titan D4）
- 通道总数：**17**，解析到定义 **17** 个，**无缺失**

| 通道 | attribute / 原始名 | App 显示 |
|---|---|---|
| 1 | dim | 调光 |
| 2 | dim_fine | 调光微调 |
| 3 | shutter | 频闪 |
| 4 | color1 | 色盘 |
| 5 | gobo1 | 图案盘1 |
| 6 | prisma1 | 棱镜1 |
| 7 | prisma2 | 棱镜2 |
| 8 | prisma2_pos | 棱镜2旋转 |
| 9 | focus | 调焦 |
| 10 | focus_fine | 调焦微调 |
| 11 | pan | 水平 |
| 12 | pan_fine | 水平微调 |
| 13 | tilt | 垂直 |
| 14 | tilt_fine | 垂直微调 |
| 15 | frost | 雾化 |
| 16 | lampcontrol | 灯泡控制 |
| 17 | fixtureglobalreset | 全局复位 |

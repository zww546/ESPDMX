package com.example.stagedmx

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * 灯库解析器：MA Lighting grandMA2 XML、Avolites Titan .d4、AVOLITES Pearl .R20。
 *
 * 解析目标：把灯库里**每一个 DMX 通道**都还原出来（通道号 + 名称 + attribute + fine），
 * 因为 App 的推子页/效果引擎都按“灯内通道号”寻址，漏一个通道就少一条推子、效果也会取不到地址。
 *
 * 三种格式的通道号来源完全不同，这是历史上“通道没读全/对不上”的根因：
 *   - MA2 XML ：`<ChannelType coarse="n" fine="m">` 是**模块内相对编号**，
 *               必须用 `<Instance module_index patch>` 展开成全局 DMX 号（本文件已实现）。
 *               没有 `coarse` 的 ChannelType 表示该模块里没有实际 DMX 通道（虚拟/主控），跳过。
 *   - Titan .d4：`<Control>` 只是属性字典，**不是通道顺序**！真正的通道号在
 *               `<Mode><Include ChannelOffset="a,b">`，多单元灯具还要用
 *               `<Cells><Cell ChannelOffset="n" ModeLink="子模式">` 把子模式展开到偏移处。
 *               早期版本按 `<Control>` 出现顺序编号 → 通道号整体错位、且多模式/多单元灯具少读通道。
 *   - Pearl R20：`DMX` 段每行的第 4 列就是通道号（offset），直接取；16bit 通道的第二行
 *               bank=0 是 fine 段，同样按 offset 落位。
 */
object FixtureParser {

    /**
     * 灯具专属翻译表（按灯名前缀）。
     * 每种灯有自己的映射，attribute 相同但含义不同的通道分别处理：
     *   - EOS-F1000（摇头染色 Wash）: RGBW 混色 → 红/绿/蓝/白
     *   - Seer-F550（光束 Beam）   : 色盘/图案盘/棱镜/雾化/泡控
     *   - ARES-P7（CMY 混色）      : COLORRGB1/2/3 实为 C/M/Y，CTC 为色温 K
     * key 为灯名小写前缀，value 为 attribute → 中文 完整覆盖表
     * （未列出的 attribute 回退到全局 ATTR_MAP）。
     */
    val FIXTURE_OVERRIDES: Map<String, Map<String, String>> = mapOf(
        "eos" to mapOf(
            "dim" to "调光", "shutter" to "频闪",
            "colorrgb1" to "红", "colorrgb2" to "绿", "colorrgb3" to "蓝",
            "colorrgb5" to "白",
            "scroller" to "色片", "colormacrorate" to "色片速度",
            "zoom" to "放大", "zoomrotation" to "放大旋转",
            "pan" to "水平", "tilt" to "垂直",
            "cto" to "色温",
            "fixtureglobalreset" to "全局复位", "color1_marco" to "颜色宏",
        ),
        "seer" to mapOf(
            "dim" to "调光", "shutter" to "频闪",
            "color1" to "色盘",
            "gobo1" to "图案盘1",
            "prisma1" to "棱镜1", "prisma2" to "棱镜2", "prisma2_pos" to "棱镜2旋转",
            "focus" to "调焦",
            "pan" to "水平", "tilt" to "垂直",
            "frost" to "雾化",
            "lampcontrol" to "灯泡控制", "fixtureglobalreset" to "全局复位",
        ),
        "ares" to mapOf(
            "dim" to "调光", "shutter" to "频闪",
            "color1" to "色盘",
            "colorrgb1" to "C", "colorrgb2" to "M", "colorrgb3" to "Y",
            "ctc" to "K",
            "iris" to "光圈",
            "gobo1" to "图案盘1", "gobo1_pos" to "图案盘1旋转",
            "gobo2" to "图案盘2",
            "blade1a" to "切割1", "blade1b" to "切割2",
            "blade2a" to "切割3", "blade2b" to "切割4",
            "blade3a" to "切割5", "blade3b" to "切割6",
            "blade4a" to "切割7", "blade4b" to "切割8",
            "shaper_rot" to "切割旋转",
            "animationwheel" to "动画轮",
            "prisma1" to "棱镜1", "prisma1_pos" to "棱镜1旋转",
            "prisma2" to "棱镜2", "prisma2_pos" to "棱镜2旋转",
            "focus" to "调焦", "zoom" to "放大",
            "pan" to "水平", "tilt" to "垂直",
            "ptspeed" to "水平垂直速度",
            "lampcontrol" to "灯泡控制", "fixtureglobalreset" to "全局复位",
        ),
        // 后续其他厂商灯名前缀可继续加
    )

    /**
     * MA grandMA2 标准 attribute → 中文。
     * attribute 是灯库生态标准化的功能标识（COLOR1=色盘, GOBO1=图案盘...），
     * 比 subattribute_user_name（常是 Select/Pos/Index 等泛用词）可靠得多。
     * 不同灯型 attribute 组合不同，翻译自然各不相同。
     */
    val ATTR_MAP = mapOf(
        // 光强度 / 光束
        "dim" to "调光",
        "shutter" to "频闪", "strobe" to "频闪",
        // 色盘（色轮）
        "color1" to "色盘", "color2" to "色盘2", "color3" to "色盘3",
        "color4" to "色盘4", "color5" to "色盘5",
        // 混色（RGB/CMY/W）
        "colorrgb1" to "红", "colorrgb2" to "绿", "colorrgb3" to "蓝",
        "colorrgb4" to "琥珀", "colorrgb5" to "白",
        "colorcmy1" to "青", "colorcmy2" to "品红", "colorcmy3" to "黄",
        "scroller" to "色片",
        "colormacrorate" to "色片速度",
        "colormacros" to "色盘宏", "color1_marco" to "颜色宏",
        // 图案盘
        "gobo1" to "图案盘1", "gobo2" to "图案盘2",
        "gobo3" to "图案盘3", "gobo4" to "图案盘4",
        "gobo1_pos" to "图案盘1旋转", "gobo2_pos" to "图案盘2旋转",
        "gobo1_select" to "图案盘1", "gobo2_select" to "图案盘2",
        // 棱镜
        "prisma1" to "棱镜1", "prisma2" to "棱镜2",
        "prisma1_pos" to "棱镜1旋转", "prisma2_pos" to "棱镜2旋转",
        "prism1" to "棱镜1", "prism2" to "棱镜2",
        "prism1_pos" to "棱镜1旋转", "prism2_pos" to "棱镜2旋转",
        // 调焦 / 放大 / 雾化 / 光圈
        "focus" to "调焦", "zoom" to "放大", "zoomrotation" to "放大旋转",
        "frost" to "雾化", "iris" to "光圈",
        // 水平 / 垂直
        "pan" to "水平", "tilt" to "垂直",
        "pan_fine" to "水平微调", "tilt_fine" to "垂直微调",
        "ptspeed" to "水平垂直速度",
        // 切割片（框架切割）
        "blade1a" to "切割1", "blade1b" to "切割2",
        "blade2a" to "切割3", "blade2b" to "切割4",
        "blade3a" to "切割5", "blade3b" to "切割6",
        "blade4a" to "切割7", "blade4b" to "切割8",
        // 老虎 D4 用 BLADE1..8 表示 8 片切割片
        "blade1" to "切割1", "blade2" to "切割2",
        "blade3" to "切割3", "blade4" to "切割4",
        "blade5" to "切割5", "blade6" to "切割6",
        "blade7" to "切割7", "blade8" to "切割8",
        "shaper_rot" to "切割旋转",
        // 动画轮
        "animationwheel" to "动画轮",
        "animationwheel_pos" to "动画轮旋转",
        // 色温
        "cto" to "色温", "ctc" to "色温",
        // 灯泡 / 复位 / 控制
        "lampcontrol" to "灯泡控制",
        "fixtureglobalreset" to "全局复位",
    )

    /** 常见 DMX 通道名翻译（public 供 FixtureStore 加载 JSON 时复用）。 */
    val ZH_MAP = mapOf(
        "dim" to "调光", "dimmer" to "调光", "intensity" to "调光",
        "shutter" to "频闪", "strobe" to "频闪",
        "pan" to "水平", "tilt" to "垂直",
        "pan_fine" to "水平精调", "tilt_fine" to "垂直精调",
        "red" to "红", "r" to "红", "green" to "绿", "g" to "绿",
        "blue" to "蓝", "b" to "蓝", "white" to "W", "w" to "W",
        "amber" to "琥珀", "uv" to "紫外", "lime" to "柠檬", "cyan" to "青",
        "magenta" to "品红", "yellow" to "黄", "cto" to "色温", "ctc" to "色温",
        "color" to "颜色", "colour" to "颜色", "color1" to "颜色1",
        "color2" to "颜色2", "color3" to "颜色3",
        "colour1" to "颜色1", "colour2" to "颜色2",
        "color_select" to "选色", "color_preset" to "色盘",
        "color_macro" to "色盘", "color_speed" to "变色速度",
        "gobo" to "图案", "gobo1" to "图案1", "gobo2" to "图案2",
        "gobo3" to "图案3", "gobo4" to "图案4",
        "gobo_select" to "选图案", "gobo_rot" to "图案旋转",
        "gobo_index" to "图案定位", "gobo1_pos" to "图案盘定位",
        "gobo2_pos" to "固图定位",
        "animation" to "动画", "animationwheel" to "动画轮",
        "prism" to "棱镜", "prisma1" to "棱镜1", "prism1" to "棱镜1",
        "prism_rot" to "棱镜旋转", "prism_index" to "棱镜定位",
        "iris" to "光圈", "focus" to "调焦", "zoom" to "放大",
        "frost" to "雾化", "frost1" to "柔光1", "frost2" to "柔光2",
        "scroller" to "色片", "zoomrot" to "放大旋转",
        "random" to "随机", "rate" to "速度",
        "blade1a" to "切割1", "blade1b" to "切割2",
        "blade2a" to "切割3", "blade2b" to "切割4",
        "blade3a" to "切割5", "blade3b" to "切割6",
        "blade4a" to "切割7", "blade4b" to "切割8",
        "shaper_rot" to "切割旋转", "frame_rot" to "切割旋转",
        "speed" to "速度", "pt_speed" to "水平垂直速度",
        "pt_auto" to "水平垂直自动", "pt_macro" to "水平垂直宏",
        "control" to "控制", "reset" to "复位", "lamp" to "灯泡",
        "power" to "功率", "fan" to "风扇",
        "special" to "特殊", "effect" to "效果", "effect_speed" to "效果速度",
        "mode" to "模式", "function" to "功能",
        "select" to "选择", "index" to "定位", "pos" to "位置",
        "select2" to "选择2", "pos2" to "棱镜2旋转",
        "ptspeed" to "水平垂直速度", "colormarco" to "色盘",
        "lamp_off" to "灯泡开关", "prism2" to "棱镜2",
        "lampcontrol" to "灯泡控制",
        "c1" to "颜色1", "c2" to "颜色2", "c3" to "颜色3",
        "c4" to "颜色4", "c5" to "颜色5", "c6" to "颜色6",
        "c7" to "颜色7", "c8" to "颜色8", "c9" to "颜色9",
        "g1" to "图案1", "g2" to "图案2", "g3" to "图案3",
        "1a" to "切割1", "1b" to "切割2",
        "2a" to "切割3", "2b" to "切割4",
        "3a" to "切割5", "3b" to "切割6",
        "4a" to "切割7", "4b" to "切割8",
        // 上下文感知：attribute_name 组合键
        "shaper_rot_index" to "切割旋转", "shaper_rot_pos" to "切割旋转",
        "gobo1_pos_index" to "图案盘旋转", "gobo2_pos_index" to "固图旋转",
        "gobo1_pos_pos" to "图案盘旋转", "gobo2_pos_pos" to "固图旋转",
        "prisma1_index" to "棱镜旋转", "prism1_index" to "棱镜旋转",
        "prisma1_pos" to "棱镜旋转", "prism1_pos" to "棱镜旋转",
        "animationwheel_index" to "动画旋转", "animationwheel_pos" to "动画旋转",
        "animationwheel_select" to "动画选择",
        "color1_select" to "选色", "color2_select" to "选色2",
        "gobo1_select" to "图案盘", "gobo2_select" to "固图",
        "gobo1_select2" to "图案盘", "gobo2_select2" to "固图",
        // 更多上下文感知
        "prisma1_prism1" to "棱镜1", "prisma2_prism2" to "棱镜2",
        "prisma2_pos_pos2" to "棱镜2旋转",
        "fixtureglobalreset_reset" to "全局复位",
        "lampcontrol_lamp_off" to "灯泡开关",
        "lampcontrol_lampcontrol" to "灯泡控制",
        "colormacrorate_rate" to "色片速度",
        "colormacros_random" to "随机颜色",
        "zoomrotation_zoomrot" to "放大旋转",
        "scroller_scroller" to "色片",
        "color1_marco_colormarco" to "色盘",
        // Avolites Titan 常见 ID（P/T 名称）
        "macro" to "颜色宏", "macrospeed" to "宏速度",
        "fixprism" to "固定棱镜", "zoomroatation" to "放大旋转",
        "framing_rotation" to "切割旋转", "framingrotation" to "切割旋转",
    )

    /** 后缀词：attribute 里 `xxx_fine` / `xxx_pos` 这类组合词拆开翻译。 */
    private val ATTR_SUFFIX = mapOf(
        "ultrafine" to "超微调", "fine" to "微调",
        "rot" to "旋转", "rotation" to "旋转", "pos" to "旋转",
        "index" to "定位", "select" to "选择", "speed" to "速度",
        "macro" to "宏", "marco" to "宏", "reset" to "复位",
        "rate" to "速度", "time" to "时间",
    )

    /**
     * 规则化翻译：把 attribute 拆成「基础词 + 后缀 + 编号」逐个翻译再拼接。
     *
     * 为什么需要它：手工表再全也盖不住各家灯库的命名（`dim_fine`、`gobo1_pos_fine`、
     * `control1`、`gobo11`…），漏一个通道就显示英文，用户看到的就是"翻译不完全"。
     *
     *   dim_fine        → 调光 + 微调            = 调光微调
     *   gobo1_pos_fine  → 图案盘1 + 旋转 + 微调   = 图案盘1旋转微调
     *   control1        → 控制 + 1               = 控制1
     *   gobo11          → 图案盘1 + -1           = 图案盘1-1
     *
     * @return 翻译失败返回 null（交回上层继续兜底）
     */
    private fun translateByRule(attr: String): String? {
        if (attr.isEmpty()) return null
        var base = attr
        val tail = StringBuilder()
        // 反复剥离后缀（可能叠加：gobo1_pos_fine）
        var again = true
        while (again) {
            again = false
            for ((suf, zh) in ATTR_SUFFIX) {
                if (base.length > suf.length + 1 && base.endsWith("_$suf")) {
                    base = base.dropLast(suf.length + 1)
                    tail.insert(0, zh)
                    again = true
                    break
                }
            }
        }
        // 先整体查（gobo1 这类带编号的键在表里），查不到再剥末尾编号
        var zh = ATTR_MAP[base] ?: ZH_MAP[base]
        var numPart = ""
        if (zh == null) {
            var num = ""
            while (base.isNotEmpty() && base.last().isDigit()) {
                num = base.last() + num
                base = base.dropLast(1)
            }
            if (base.isEmpty() || num.isEmpty()) return null
            zh = ATTR_MAP[base] ?: ZH_MAP[base] ?: return null
            // 基础词本身以数字结尾（图案盘1）→ 用 -1 区分，避免读成 "图案盘11"
            numPart = if (zh.lastOrNull()?.isDigit() == true) "-$num" else num
        }
        return zh + numPart + tail
    }

    fun translate(name: String, attribute: String = "", fixtureName: String = ""): String {
        val key = name.lowercase().trim().replace(' ', '_').replace("<", "").replace(">", "")
        val attrKey = attribute.lowercase().trim().replace(' ', '_')
        // 0) 灯具专属覆盖（按灯名前缀，最优先）
        if (fixtureName.isNotEmpty()) {
            val fn = fixtureName.lowercase().trim()
            for ((prefix, map) in FIXTURE_OVERRIDES) {
                if (fn.startsWith(prefix) && attrKey.isNotEmpty()) {
                    map[attrKey]?.let { return it }
                }
            }
        }
        // 1) MA 标准 attribute 优先（最可靠：COLOR1→色盘, GOBO1→图案盘1...）
        if (attrKey.isNotEmpty()) {
            ATTR_MAP[attrKey]?.let { return it }
        }
        // 2) 规则拆分（覆盖 *_fine / *_pos / *数字 等组合）
        if (attrKey.isNotEmpty()) {
            translateByRule(attrKey)?.let { return it }
        }
        // 3) attribute_name 组合键
        if (attrKey.isNotEmpty()) {
            ZH_MAP["${attrKey}_$key"]?.let { return it }
        }
        // 4) 名字兜底
        return ZH_MAP[key] ?: name
    }

    // =====================================================================
    //  MA2 XML
    // =====================================================================

    /** 解析 MA2 XML 中的**所有** FixtureType（一个文件可能含多种模式）。 */
    fun parseMa2XmlAll(xml: String): List<FixtureDef> {
        return try {
            parseMa2All(xml)
        } catch (e: Exception) {
            Log.e("FixtureParser", "parse error", e)
            emptyList()
        }
    }

    /** 兼容旧接口：只取第一个灯型。 */
    fun parseMa2Xml(xml: String): FixtureDef? = parseMa2XmlAll(xml).firstOrNull()

    private fun parseMa2All(xml: String): List<FixtureDef> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        val out = mutableListOf<FixtureDef>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "FixtureType") {
                parseOneFixtureType(parser)?.let { out.add(it) }
            }
            event = parser.next()
        }
        return out
    }

    /** 解析单个 FixtureType 子树（进入时 parser 停在它的 START_TAG 上）。 */
    private fun parseOneFixtureType(parser: XmlPullParser): FixtureDef? {
        val name = parser.getAttributeValue(null, "name") ?: return null
        val mode = parser.getAttributeValue(null, "mode") ?: ""
        var manufacturer = ""

        // 按模块索引收集通道（coarse 是模块内相对编号）
        val moduleChannels = mutableMapOf<Int, MutableList<FixtureChannel>>()
        var currentModule = 0
        // 实例映射：module_index -> list of patch offsets（1-based DMX 起始地址）
        val instances = mutableMapOf<Int, MutableList<Int>>()

        var insideModule = false
        var insideChannel = false
        var chName = ""
        var chOrigName = ""
        var chDefault = 0
        var chHighlight = 255
        var chHasFine = false
        var chFineNumber: Int? = null
        var coarseDmx = 0
        var chAttribute = ""  // 用于上下文感知翻译
        var chPhysFrom = 0f
        var chPhysTo = 0f

        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.name == "FixtureType") break
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "manufacturer" -> {
                            parser.next()
                            manufacturer = parser.text?.trim() ?: manufacturer
                        }
                        "Module" -> {
                            insideModule = true
                            currentModule = parser.getAttributeValue(null, "index")?.toIntOrNull() ?: 0
                            moduleChannels.getOrPut(currentModule) { mutableListOf() }
                        }
                        "Instance" -> {
                            val mi = parser.getAttributeValue(null, "module_index")?.toIntOrNull() ?: 0
                            // patch=起始DMX通道号(1-based)，默认 1
                            val patch = parser.getAttributeValue(null, "patch")?.toIntOrNull() ?: 1
                            instances.getOrPut(mi) { mutableListOf() }.add(patch)
                        }
                        "ChannelType" -> {
                            if (insideModule) {
                                insideChannel = true
                                chAttribute = parser.getAttributeValue(null, "attribute") ?: ""
                                // coarse = 模块内通道编号(1-based)；缺失=该模块没有实际 DMX 通道
                                val coarse = parser.getAttributeValue(null, "coarse")
                                coarseDmx = coarse?.toIntOrNull() ?: 0
                                chDefault = parser.getAttributeValue(null, "default")
                                    ?.toFloatOrNull()?.toInt() ?: 0
                                chHighlight = parser.getAttributeValue(null, "highlight_value")
                                    ?.toFloatOrNull()?.toInt() ?: 255
                                val fine = parser.getAttributeValue(null, "fine")
                                if (fine != null) {
                                    chHasFine = true
                                    chFineNumber = fine.toIntOrNull()
                                }
                            }
                        }
                        "ChannelFunction" -> {
                            if (insideChannel) {
                                val rawName = parser.getAttributeValue(null, "subattribute_user_name")
                                    ?: parser.getAttributeValue(null, "attribute_user_name")
                                    ?: parser.getAttributeValue(null, "attribute")
                                    ?: "CH$coarseDmx"
                                chName = rawName
                                chOrigName = rawName
                                chPhysFrom = parser.getAttributeValue(null, "physfrom")?.toFloatOrNull() ?: 0f
                                chPhysTo = parser.getAttributeValue(null, "physto")?.toFloatOrNull() ?: 0f
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "ChannelType" -> {
                            if (insideChannel && coarseDmx > 0) {
                                moduleChannels.getOrPut(currentModule) { mutableListOf() }.add(FixtureChannel(
                                    number = coarseDmx,  // 模块内编号，后面展开
                                    name = chName,
                                    originalName = chOrigName,
                                    attribute = chAttribute,  // 保存 MA attribute 用于上下文翻译
                                    defaultValue = chDefault,
                                    highlightValue = chHighlight,
                                    hasFine = chHasFine,
                                    fineNumber = chFineNumber,
                                    physFrom = chPhysFrom,
                                    physTo = chPhysTo
                                ))
                            }
                            insideChannel = false
                            chHasFine = false
                            chFineNumber = null
                            chOrigName = ""
                            chPhysFrom = 0f
                            chPhysTo = 0f
                        }
                        "Module" -> insideModule = false
                    }
                }
            }
            event = parser.next()
        }

        if (moduleChannels.isEmpty()) return null

        // 确保 Module 0 有默认实例（patch=1），处理无显式 Instance 的老灯库
        if (!instances.containsKey(0)) {
            instances[0] = mutableListOf(1)
        }

        // 展开实例：global_dmx = instance_patch + coarse - 1
        val globalChannels = mutableListOf<FixtureChannel>()
        for ((modIdx, patches) in instances) {
            val modChs = moduleChannels[modIdx] ?: continue
            for (patch in patches) {
                for (ch in modChs) {
                    val globalNum = patch + ch.number - 1
                    globalChannels.add(ch.copy(
                        number = globalNum,
                        fineNumber = ch.fineNumber?.let { patch + it - 1 }
                    ))
                }
            }
        }
        if (globalChannels.isEmpty()) return null

        // fine 通道补位：MA2 把 16bit 通道的 fine 只写在 coarse 的 fine 属性里，
        // 若不给 fine 号建条目，推子页就会出现 “CH 15 / CH 17” 这种没名字的空洞。
        val fineChannels = mutableListOf<FixtureChannel>()
        for (ch in globalChannels) {
            if (ch.hasFine && ch.fineNumber != null) {
                val fn = ch.fineNumber
                fineChannels.add(FixtureChannel(
                    number = fn,
                    name = "${ch.name} Fine",
                    originalName = "${ch.originalName} Fine",
                    attribute = if (ch.attribute.isBlank()) "" else "${ch.attribute}_FINE",
                    defaultValue = ch.defaultValue,
                    highlightValue = ch.highlightValue,
                    hasFine = false,
                    fineNumber = null,
                    physFrom = 0f,
                    physTo = 0f
                ))
            }
        }

        // 同名同号通道去重（多实例/模块碰撞时保留第一个）
        val seen = mutableSetOf<Int>()
        val sortedCh = (globalChannels + fineChannels)
            .sortedBy { it.number }
            .filter { seen.add(it.number) }

        if (sortedCh.isEmpty()) return null

        val maxCh = sortedCh.maxOf { it.number }

        // 计算 PAN / TILT 物理行程
        val panCh = sortedCh.find { it.attribute.equals("PAN", ignoreCase = true) || it.originalName.equals("Pan", ignoreCase = true) }
        val tiltCh = sortedCh.find { it.attribute.equals("TILT", ignoreCase = true) || it.originalName.equals("Tilt", ignoreCase = true) }
        val panRange = if (panCh != null && panCh.physFrom != 0f && panCh.physTo != 0f)
            kotlin.math.abs(panCh.physFrom - panCh.physTo) else 0f
        val tiltRange = if (tiltCh != null && tiltCh.physFrom != 0f && tiltCh.physTo != 0f)
            kotlin.math.abs(tiltCh.physFrom - tiltCh.physTo) else 0f

        // 搜索 PT Speed 通道
        val ptSpeedNames = listOf("pt_speed", "ptspeed", "pt speed", "pan_tilt_speed",
            "pan tilt speed", "p/t speed", "水平垂直速度")
        val ptSpeedCh = sortedCh.find { ch ->
            val a = ch.attribute.lowercase().replace(" ", "_")
            if (ptSpeedNames.any { a.contains(it.replace(" ", "_")) }) true
            else {
                val n = ch.originalName.lowercase().replace(" ", "_")
                ptSpeedNames.any { n.contains(it.replace(" ", "_")) }
            }
        }?.number

        return FixtureDef(
            id = "${manufacturer}_${name}_${mode}".lowercase()
                .replace(Regex("[^a-z0-9_]"), "_")
                .replace(Regex("_+"), "_")
                .trim('_'),
            name = name,
            manufacturer = manufacturer,
            mode = mode,
            channelCount = maxCh,
            channels = sortedCh,
            panRange = panRange,
            tiltRange = tiltRange,
            ptSpeedCh = ptSpeedCh
        )
    }

    // =====================================================================
    //  Avolites Titan (.d4)
    // =====================================================================
    //
    // 结构：<Fixture Name Company>
    //         <Control> 属性字典：<Attribute ID Name Size="2"/>
    //         <Mode Name="19ch" Channels="19" [Hidden="True"]>
    //           <Include> <Attribute ID="Pan" ChannelOffset="14,15"/> … </Include>
    //           <Cells>   ← 多单元灯具（像素/LED 单元），可选
    //             <Master> <Attribute ID="Dimmer" ChannelOffset="1"/> … </Master>
    //             <Cell ChannelOffset="3" ModeLink="Rgbw"> … </Cell>
    //           </Cells>
    //         </Mode>
    //       </Fixture>
    //
    //  ⚠ 通道号只能来自 ChannelOffset（Master/Cell/Include），绝不是 <Control> 的顺序。

    private class D4Ref(val id: String, val name: String, val offsets: List<Int>)

    private class D4Cell(val offset: Int, val modeLink: String,
                         val refs: MutableList<D4Ref> = mutableListOf())

    private class D4Mode(val name: String, val declared: Int, val hidden: Boolean) {
        val include = mutableListOf<D4Ref>()
        val master = mutableListOf<D4Ref>()
        val cells = mutableListOf<D4Cell>()
        var panMax = 0f
        var tiltMax = 0f
    }

    private val D4_ATTR = mapOf(
        "Dimmer" to "dim", "Shutter" to "shutter", "Strobe" to "strobe",
        "Colour1" to "color1", "Colour2" to "color2", "Colour3" to "color3",
        "Cyan" to "colorcmy1", "Magenta" to "colorcmy2", "Yellow" to "colorcmy3",
        "CTC" to "ctc", "CTO" to "cto", "Colour_Macro" to "colormacros",
        "ColourMacro" to "colormacros", "Macro" to "colormacros", "MacroSpeed" to "colormacrorate",
        "Red" to "colorrgb1", "Green" to "colorrgb2", "Blue" to "colorrgb3",
        "White" to "colorrgb5", "Amber" to "colorrgb4", "UV" to "colorrgb6",
        "Iris" to "iris", "Gobo1" to "gobo1", "Gobo1Rot" to "gobo1_pos",
        "Gobo2" to "gobo2", "Gobo2Rot" to "gobo2_pos", "Gobo" to "gobo1",
        "Pan" to "pan", "Tilt" to "tilt", "Zoom" to "zoom", "Focus" to "focus",
        "ZoomRotation" to "zoomrotation", "Zoomroatation" to "zoomrotation",
        "Prism" to "prisma1", "PrismRot" to "prisma1_pos",
        "Prism1" to "prisma1", "Prism1_Rot" to "prisma1_pos",
        "Prism2" to "prisma2", "Prism2_Rot" to "prisma2_pos",
        "FixPrism" to "prisma1",
        "Frost" to "frost", "AnimationWheel" to "animationwheel",
        "Animation" to "animationwheel",
        "LampControl" to "lampcontrol", "Lamp_Control" to "lampcontrol",
        "Control" to "fixtureglobalreset",
        "PTSpeed" to "ptspeed", "PT_Speed" to "ptspeed",
        "Blade1A" to "blade1a", "Blade1B" to "blade1b",
        "Blade2A" to "blade2a", "Blade2B" to "blade2b",
        "Blade3A" to "blade3a", "Blade3B" to "blade3b",
        "Blade4A" to "blade4a", "Blade4B" to "blade4b",
        "ShaperRot" to "shaper_rot", "FrameRot" to "shaper_rot",
        "FRAMING_ROTATION" to "shaper_rot", "FramingRot" to "shaper_rot",
        // 部分老虎灯库把 8 片切割片直接叫 BLADE1..BLADE8（无 A/B 后缀），
        // 映射顺序与 BLADE1A..4B 保持一致，切割循环效果才能取到通道。
        "Blade1" to "blade1a", "Blade2" to "blade1b",
        "Blade3" to "blade2a", "Blade4" to "blade2b",
        "Blade5" to "blade3a", "Blade6" to "blade3b",
        "Blade7" to "blade4a", "Blade8" to "blade4b",
    )

    /** 大小写无关查找（灯库里 ID 既有 "Blade1A" 也有 "BLADE1"）。 */
    private val D4_ATTR_L: Map<String, String> = D4_ATTR.mapKeys { it.key.lowercase() }

    private fun d4Key(id: String): String =
        D4_ATTR[id] ?: D4_ATTR_L[id.lowercase()] ?: id.lowercase()

    /** 解析 .d4 中**所有**非隐藏模式（同一文件可能有 34CH / 39CH 两种模式）。 */
    fun parseD4All(xml: String): List<FixtureDef> {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var fixtureName = ""; var shortName = ""; var company = ""
            val ctrlSize = mutableMapOf<String, Int>()
            val ctrlName = mutableMapOf<String, String>()
            val modes = mutableListOf<D4Mode>()
            var mode: D4Mode? = null
            var cell: D4Cell? = null
            var ctx = 0            // 0=无 1=Include 2=Master 3=Cell
            var inControl = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "Fixture" -> {
                            fixtureName = parser.getAttributeValue(null, "Name") ?: ""
                            shortName = parser.getAttributeValue(null, "ShortName") ?: ""
                            company = parser.getAttributeValue(null, "Company") ?: ""
                        }
                        "Control" -> inControl = true
                        "Attribute" -> {
                            val id = parser.getAttributeValue(null, "ID") ?: ""
                            val nm = parser.getAttributeValue(null, "Name") ?: ""
                            val size = parser.getAttributeValue(null, "Size")?.toIntOrNull() ?: 1
                            if (inControl) {
                                ctrlSize[id.lowercase()] = size
                                ctrlName[id.lowercase()] = nm.ifEmpty { id }
                            } else {
                                val offs = parser.getAttributeValue(null, "ChannelOffset")
                                    ?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
                                val ref = D4Ref(id, nm.ifEmpty { id }, offs)
                                when (ctx) {
                                    1 -> mode?.include?.add(ref)
                                    2 -> mode?.master?.add(ref)
                                    3 -> cell?.refs?.add(ref)
                                }
                            }
                        }
                        "Mode" -> {
                            mode = D4Mode(
                                parser.getAttributeValue(null, "Name") ?: "",
                                parser.getAttributeValue(null, "Channels")?.toIntOrNull() ?: 0,
                                parser.getAttributeValue(null, "Hidden")?.equals("True", true) == true)
                        }
                        "Include" -> ctx = 1
                        "Master" -> ctx = 2
                        "Cells" -> ctx = 0
                        "Cell" -> {
                            cell = D4Cell(
                                parser.getAttributeValue(null, "ChannelOffset")?.toIntOrNull() ?: 1,
                                parser.getAttributeValue(null, "ModeLink") ?: "")
                            ctx = 3
                        }
                        "Focus" -> {
                            mode?.let {
                                it.panMax = parser.getAttributeValue(null, "PanMax")?.toFloatOrNull() ?: it.panMax
                                it.tiltMax = parser.getAttributeValue(null, "TiltMax")?.toFloatOrNull() ?: it.tiltMax
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "Control" -> inControl = false
                        "Include" -> ctx = 0
                        "Master" -> ctx = 0
                        "Cell" -> { cell?.let { c -> mode?.cells?.add(c) }; cell = null; ctx = 0 }
                        "Mode" -> { mode?.let { modes.add(it) }; mode = null; ctx = 0 }
                    }
                }
                event = parser.next()
            }

            val nm = fixtureName.ifEmpty { shortName }.ifEmpty { "Fixture" }
            val byName = modes.associateBy { it.name.lowercase() }
            val out = mutableListOf<FixtureDef>()

            for (m in modes) {
                if (m.hidden) continue          // Rgbw 这类隐藏子模式由 Cell 展开，不单独导出
                val chMap = sortedMapOf<Int, FixtureChannel>()

                fun put(coarse: Int, fine: Int?, id: String, dispName: String) {
                    if (coarse < 1 || coarse > DmxProtocol.MAX_CHANNELS) return
                    val key = d4Key(id)
                    // Include/Cell 里通常不带 Name，回退到 <Control> 字典里的官方属性名
                    val display = dispName.ifEmpty { ctrlName[id.lowercase()] ?: id }
                    val zh = translate(display, key, fixtureName)
                    chMap.putIfAbsent(coarse, FixtureChannel(
                        coarse, zh, display, key, 0, 0, fine != null, fine, 0f, 0f))
                    if (fine != null && fine in 1..DmxProtocol.MAX_CHANNELS) {
                        chMap.putIfAbsent(fine, FixtureChannel(
                            fine, "${translate(display, "${key}_fine", fixtureName)}微调",
                            "$display Fine", "${key}_fine", 0, 0, false, null, 0f, 0f))
                    }
                }

                fun refFine(r: D4Ref): Int? {
                    val size = ctrlSize[r.id.lowercase()] ?: (if (r.offsets.size >= 2) 2 else 1)
                    if (size < 2) return null
                    return r.offsets.getOrNull(1) ?: (r.offsets.firstOrNull()?.plus(1))
                }

                if (m.cells.isNotEmpty()) {
                    // Master 是主控部分，Cell 是展开到偏移处的子模式（像素/单元）
                    for (r in m.master) {
                        val o = r.offsets.firstOrNull() ?: continue
                        put(o, refFine(r), r.id, r.name)
                    }
                    for (c in m.cells) {
                        val sub = byName[c.modeLink.lowercase()] ?: continue
                        for (r in sub.include) {
                            val o = (r.offsets.firstOrNull() ?: continue) + c.offset - 1
                            val fine = refFine(r)?.plus(c.offset - 1)
                            put(o, fine, r.id, r.name)
                        }
                    }
                } else {
                    for (r in m.include) {
                        val o = r.offsets.firstOrNull() ?: continue
                        put(o, refFine(r), r.id, r.name)
                    }
                }
                if (chMap.isEmpty()) continue

                val channels = chMap.values.toList()
                val modeName = m.name.ifEmpty { "${chMap.lastKey()}ch" }
                val ptSpeed = channels.find { it.attribute.equals("ptspeed", true) }?.number
                out.add(FixtureDef(
                    id = "${company.ifEmpty { "d4" }}_${nm}_${modeName}".lowercase()
                        .replace(Regex("[^a-z0-9_]"), "_").replace(Regex("_+"), "_").trim('_'),
                    name = nm, manufacturer = company, mode = modeName,
                    channelCount = chMap.lastKey(),
                    channels = channels,
                    panRange = if (m.panMax > 0) m.panMax else 0f,
                    tiltRange = if (m.tiltMax > 0) m.tiltMax else 0f,
                    ptSpeedCh = ptSpeed
                ))
            }
            out
        } catch (e: Exception) {
            e.printStackTrace(); emptyList()
        }
    }

    /** 兼容旧接口：只取第一个模式。 */
    fun parseD4(xml: String): FixtureDef? = parseD4All(xml).firstOrNull()

    // =====================================================================
    //  AVOLITES Pearl (.R20)
    // =====================================================================
    // 文本格式：DEVICE / NAME / TYPE 1 <ch> M / MIRROR / DMX ... END
    // DMX 行: bank bank2 type offset level curveN attrType 1 "name" on highlight lowlight
    // 第 4 列 offset 就是通道号；bank=0 的行是 16bit 通道的 fine 段，同样按 offset 落位。
    private val R20_ATTR = mapOf(
        'H' to "dim", 'E' to "pan", 'F' to "tilt", 'O' to "shutter",
        'A' to "color1", 'N' to "color2",
        'B' to "colorcmy1", 'C' to "colorcmy2", 'D' to "colorcmy3",
        'G' to "iris", 'I' to "gobo1", 'J' to "gobo2",
        'K' to "gobo1_pos", 'M' to "gobo2_pos",
        'L' to "focus", 'P' to "prisma1", 'Q' to "zoom",
        'R' to "prisma1_pos", 'S' to "frost",
        'T' to "colorrgb1", 'U' to "colorrgb2", 'V' to "colorrgb3",
        'W' to "colorrgb5", 'X' to "colorrgb4",
    )

    fun parseR20(text: String): FixtureDef? {
        return try {
            var device = ""; var company = ""; var name = ""
            var panRange = 0f; var tiltRange = 0f
            var declared = 0
            var inDmx = false
            val chMap = sortedMapOf<Int, FixtureChannel>()
            for (line in text.lineSequence()) {
                val l = line.trim()
                if (l.isEmpty() || l.startsWith(";")) continue
                when {
                    l.startsWith("DEVICE") -> device = l.substringAfter("DEVICE").trim()
                    l.startsWith("NAME") -> {
                        val m = Regex("\"([^\"]*)\"\\s*\"([^\"]*)\"").find(l)
                        if (m != null) { company = m.groupValues[1]; name = m.groupValues[2] }
                    }
                    l.startsWith("TYPE") -> {
                        // TYPE <type> <channels> M
                        val p = l.split(Regex("\\s+"))
                        declared = p.getOrNull(2)?.toIntOrNull() ?: declared
                    }
                    l.startsWith("MIRROR") -> {
                        val p = l.split(Regex("\\s+"))
                        panRange = p.getOrNull(2)?.toFloatOrNull() ?: 0f
                        tiltRange = p.getOrNull(3)?.toFloatOrNull() ?: 0f
                    }
                    l.startsWith("DMX") -> inDmx = true
                    l.startsWith("END") -> inDmx = false
                    inDmx -> {
                        // bank bank2 type offset level curveN attrType 1 "name" ...
                        val m = Regex("""^(\d+)\s+(\d+)\s+(\S+)\s+(\d+)\s+(\d+)\s+(\S+)\s+(\S)\s+1\s+"([^"]*)""").find(l)
                        if (m != null) {
                            val bank = m.groupValues[1].toInt()
                            val offset = m.groupValues[4].toInt()
                            val attrType = m.groupValues[7].firstOrNull()
                            val chName = m.groupValues[8]
                            val key = R20_ATTR[attrType] ?: ""
                            // bank == 0 的行是 16bit 通道的 fine 段
                            val isFine = bank == 0
                            val zh = if (isFine) {
                                if (key.isNotEmpty()) translate(chName, "${key}_fine", device)
                                else "$chName 精调"
                            } else if (key.isNotEmpty()) translate(chName, key, device) else chName
                            chMap.putIfAbsent(offset, FixtureChannel(
                                offset, zh, chName,
                                if (isFine && key.isNotEmpty()) "${key}_fine" else key,
                                0, 0, false, null, 0f, 0f))
                        }
                    }
                }
            }
            if (chMap.isEmpty()) return null
            val nm = name.ifEmpty { device }.ifEmpty { "Fixture" }
            val mode = "r20"
            val maxCh = maxOf(chMap.lastKey(), declared)
            FixtureDef(
                id = "${(company.ifEmpty { "r20" })}_${nm}_${mode}".lowercase()
                    .replace(Regex("[^a-z0-9_]"), "_").replace(Regex("_+"), "_").trim('_'),
                name = nm, manufacturer = company, mode = mode,
                channelCount = maxCh,
                channels = chMap.values.toList(),
                panRange = panRange, tiltRange = tiltRange, ptSpeedCh = null)
        } catch (e: Exception) { e.printStackTrace(); null }
    }
}

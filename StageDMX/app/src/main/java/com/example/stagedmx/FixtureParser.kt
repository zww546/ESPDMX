package com.example.stagedmx

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * MA Lighting grandMA2 XML 灯库解析器。
 * 只提取通道映射（名称/默认值/高亮值），忽略几何/物理细节。
 * 通道名自动翻译为中文。
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
    )

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
        // 2) attribute_name 组合键
        if (attrKey.isNotEmpty()) {
            ZH_MAP["${attrKey}_$key"]?.let { return it }
        }
        // 3) 名字兜底
        return ZH_MAP[key] ?: name
    }

    /** 从 MA2 XML 解析灯具定义。失败返回 null。 */
    fun parseMa2Xml(xml: String): FixtureDef? {
        return try {
            parseInternal(xml)
        } catch (e: Exception) {
            Log.e("FixtureParser", "parse error", e)
            null
        }
    }

    private data class ModuleChannels(
        val channels: MutableList<FixtureChannel> = mutableListOf()
    )

    private fun parseInternal(xml: String): FixtureDef? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var name: String? = null
            var manufacturer = ""
            var mode = ""

            // 按模块索引收集通道（coarse 是模块内相对编号）
            val moduleChannels = mutableMapOf<Int, MutableList<FixtureChannel>>()
            var currentModule = 0
            // 实例映射：module_index -> list of patch offsets（1-based DMX 起始地址）
            val instances = mutableMapOf<Int, MutableList<Int>>()

            var insideFixture = false
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

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "FixtureType" -> {
                                insideFixture = true
                                name = parser.getAttributeValue(null, "name")
                                mode = parser.getAttributeValue(null, "mode") ?: ""
                            }
                            "manufacturer" -> {
                                if (insideFixture) {
                                    parser.next()
                                    manufacturer = parser.text?.trim() ?: manufacturer
                                }
                            }
                            "Module" -> {
                                if (insideFixture) {
                                    insideModule = true
                                    currentModule = parser.getAttributeValue(null, "index")?.toIntOrNull() ?: 0
                                    moduleChannels.getOrPut(currentModule) { mutableListOf() }
                                }
                            }
                            "Instance" -> {
                                if (insideFixture) {
                                    val mi = parser.getAttributeValue(null, "module_index")?.toIntOrNull() ?: 0
                                    // patch=起始DMX通道号(1-based)，默认 1
                                    val patch = parser.getAttributeValue(null, "patch")?.toIntOrNull() ?: 1
                                    instances.getOrPut(mi) { mutableListOf() }.add(patch)
                                }
                            }
                            "ChannelType" -> {
                                if (insideModule) {
                                    insideChannel = true
                                    chAttribute = parser.getAttributeValue(null, "attribute") ?: ""
                                    // coarse = 模块内通道编号(1-based)
                                    val coarse = parser.getAttributeValue(null, "coarse")
                                    coarseDmx = coarse?.toIntOrNull() ?: 0
                                    chDefault = parser.getAttributeValue(null, "default")
                                        ?.toIntOrNull() ?: 0
                                    chHighlight = parser.getAttributeValue(null, "highlight_value")
                                        ?.toIntOrNull() ?: 255
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
                                    moduleChannels[currentModule]!!.add(FixtureChannel(
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
                            "FixtureType" -> insideFixture = false
                        }
                    }
                }
                event = parser.next()
            }

            if (name == null || moduleChannels.isEmpty()) return null

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

            // 同名同号通道去重（多实例/模块碰撞时保留第一个）
            val seen = mutableSetOf<Int>()
            val sortedCh = globalChannels
                .sortedBy { it.number }
                .filter { seen.add(it.number) }

            if (sortedCh.isEmpty()) return null

            val maxCh = sortedCh.maxOf {
                if (it.hasFine && it.fineNumber != null) maxOf(it.number, it.fineNumber!!)
                else it.number
            }

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

            FixtureDef(
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
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ================= Avolites Titan (.d4) 解析 =================
    // .d4 本质是 Avolites Titan XML：<Fixture><Control><Attribute ID Size>...
    // 每个 Attribute = 一个通道（Size=2 表示 coarse+fine 两个 DMX 通道）。
    private val D4_ATTR = mapOf(
        "Dimmer" to "dim", "Shutter" to "shutter", "Strobe" to "strobe",
        "Colour1" to "color1", "Colour2" to "color2", "Colour3" to "color3",
        "Cyan" to "colorcmy1", "Magenta" to "colorcmy2", "Yellow" to "colorcmy3",
        "CTC" to "ctc", "Colour_Macro" to "colormacros",
        "Iris" to "iris", "Gobo1" to "gobo1", "Gobo1Rot" to "gobo1_pos",
        "Gobo2" to "gobo2", "Gobo2Rot" to "gobo2_pos",
        "Pan" to "pan", "Tilt" to "tilt", "Zoom" to "zoom", "Focus" to "focus",
        "Prism" to "prisma1", "PrismRot" to "prisma1_pos",
        "Frost" to "frost", "AnimationWheel" to "animationwheel",
        "LampControl" to "lampcontrol", "Control" to "control",
        "PTSpeed" to "ptspeed", "Blade1A" to "blade1a", "Blade1B" to "blade1b",
        "Blade2A" to "blade2a", "Blade2B" to "blade2b",
        "Blade3A" to "blade3a", "Blade3B" to "blade3b",
        "Blade4A" to "blade4a", "Blade4B" to "blade4b",
    )

    fun parseD4(xml: String): FixtureDef? {
        return try {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        var fixtureName = ""; var shortName = ""; var company = ""
        val channels = mutableListOf<FixtureChannel>()
        var chNumber = 0
        var attrId = ""; var attrName = ""; var attrSize = 1
        var inControl = false   // 只统计 <Control> 下的 Attribute 定义，
                                // 忽略 <Mode><Include> 里的 Attribute 引用（否则会把多个 Mode 重复计入）
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
                    "Attribute" -> if (inControl) {
                        attrId = parser.getAttributeValue(null, "ID") ?: ""
                        attrName = parser.getAttributeValue(null, "Name") ?: ""
                        attrSize = parser.getAttributeValue(null, "Size")?.toIntOrNull() ?: 1
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "Control" -> inControl = false
                    "Attribute" -> if (inControl && attrId.isNotEmpty()) {
                        val key = D4_ATTR[attrId] ?: attrId.lowercase()
                        val display = attrName.ifEmpty { attrId }
                        chNumber++
                        channels.add(FixtureChannel(
                            chNumber, translate(display, key, fixtureName), display, key, 0, 0,
                            false, null, 0f, 0f))
                        if (attrSize >= 2) {
                            chNumber++
                            val fineKey = key + "_fine"
                            channels.add(FixtureChannel(
                                chNumber, "${translate(display, key, fixtureName)}微调", "${display} Fine", fineKey, 0, 0,
                                true, null, 0f, 0f))
                        }
                    }
                }
            }
            event = parser.next()
        }
        if (channels.isEmpty()) return null
        val nm = fixtureName.ifEmpty { shortName }.ifEmpty { "Fixture" }
        val mode = "d4"
        FixtureDef(
            id = "${(company.ifEmpty { "d4" }) }_${nm}_${mode}".lowercase()
                .replace(Regex("[^a-z0-9_]"), "_").replace(Regex("_+"), "_").trim('_'),
            name = nm, manufacturer = company, mode = mode,
            channelCount = chNumber, channels = channels, panRange = 0f, tiltRange = 0f, ptSpeedCh = null)
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    // ================= AVOLITES Pearl (.R20) 解析 =================
    // 文本格式：DEVICE / NAME / TYPE 1 <ch> M / MIRROR / DMX ... END
    // DMX 行: bank bank2 type offset level curveN attrType 1 "name" on highlight lowlight
    private val R20_ATTR = mapOf(
        'H' to "dim", 'E' to "pan", 'F' to "tilt", 'O' to "shutter",
        'A' to "color1", 'N' to "color2", 'B' to "colorcmy1", 'C' to "colorcmy2", 'D' to "colorcmy3",
        'G' to "iris", 'I' to "gobo1", 'J' to "gobo2", 'K' to "gobo1_pos", 'M' to "gobo2_pos",
        'L' to "focus", 'P' to "prisma1", 'Q' to "zoom", 'R' to "prisma1_pos", 'S' to "frost",
    )

    fun parseR20(text: String): FixtureDef? {
        return try {
        var device = ""; var company = ""; var name = ""
        var panRange = 0f; var tiltRange = 0f
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
                        val offset = m.groupValues[4].toInt()
                        val attrType = m.groupValues[7].firstOrNull()
                        val chName = m.groupValues[8]
                        val key = R20_ATTR[attrType] ?: ""
                        val zh = if (key.isNotEmpty()) translate(chName, key, device) else chName
                        chMap[offset] = FixtureChannel(offset, zh, chName, key, 0, 0, false, null, 0f, 0f)
                    }
                }
            }
        }
        if (chMap.isEmpty()) return null
        val nm = name.ifEmpty { device }.ifEmpty { "Fixture" }
        val mode = "r20"
        FixtureDef(
            id = "${(company.ifEmpty { "r20" })}_${nm}_${mode}".lowercase()
                .replace(Regex("[^a-z0-9_]"), "_").replace(Regex("_+"), "_").trim('_'),
            name = nm, manufacturer = company, mode = mode,
            channelCount = chMap.size,
            channels = chMap.values.toList(), panRange = panRange, tiltRange = tiltRange, ptSpeedCh = null)
        } catch (e: Exception) { e.printStackTrace(); null }
    }
}

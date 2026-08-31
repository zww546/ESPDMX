package com.example.stagedmx

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 灯库编辑器 — 创建/修改灯型 + 导出 MA2 XML ZIP。
 */
class FixtureEditor(private val ctx: Context, private val store: FixtureStore) {

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    /** 内置属性选项（value → 中文说明）。 */
    val attrOptions = listOf(
        "DIM" to "调光", "SHUTTER" to "频闪",
        "PAN" to "水平", "TILT" to "垂直",
        "COLOR1" to "色盘", "COLORRGB1" to "红", "COLORRGB2" to "绿",
        "COLORRGB3" to "蓝", "COLORRGB5" to "白",
        "COLORCMY1" to "青", "COLORCMY2" to "品红", "COLORCMY3" to "黄",
        "CTO" to "色温", "CTC" to "色温",
        "GOBO1" to "图案盘1", "GOBO2" to "图案盘2",
        "PRISMA1" to "棱镜1", "PRISMA2" to "棱镜2",
        "ZOOM" to "放大", "FOCUS" to "调焦", "FROST" to "雾化", "IRIS" to "光圈",
        "PTSPEED" to "PT速度", "LAMPCONTROL" to "灯泡控制",
        "FIXTUREGLOBALRESET" to "全局复位",
        "SCROLLER" to "色片", "ANIMATIONWHEEL" to "动画轮",
        "BLADE1A" to "切割1", "BLADE1B" to "切割2",
        "BLADE2A" to "切割3", "BLADE2B" to "切割4",
        "BLADE3A" to "切割5", "BLADE3B" to "切割6",
        "BLADE4A" to "切割7", "BLADE4B" to "切割8",
    )

    data class ChData(
        val number: Int = 1,
        val name: String = "",
        val attribute: String = "",
        val defaultValue: Int = 0,
        val highlightValue: Int = 0,
        val hasFine: Boolean = false,
        val physFrom: Float = 0f,
        val physTo: Float = 0f
    )

    val channels = mutableListOf<ChData>()

    // 统一输入框样式 — 程序化背景彻底绕过主题绿色干扰
    private fun inputField(w: Int, wgt: Float): EditText = EditText(ctx).apply {
        val lp = if (wgt > 0) LinearLayout.LayoutParams(0, dp(36), wgt)
                 else LinearLayout.LayoutParams(dp(w), dp(36))
        lp.setMargins(dp(2), 0, dp(2), 0)
        layoutParams = lp
        background = GradientDrawable().apply {
            setColor(ctx.getColor(R.color.surface2))
            cornerRadius = dp(10).toFloat()
        }
        setTextColor(ctx.getColor(R.color.text))
        setHintTextColor(ctx.getColor(R.color.textDim))
        textSize = 12f
        maxLines = 1
        setPadding(dp(6), 0, dp(6), 0)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    fun renderChannels(container: LinearLayout, onChanged: () -> Unit) {
        container.removeAllViews()

        for ((idx, ch) in channels.withIndex()) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
                setPadding(dp(4), dp(3), dp(4), dp(3))
            }

            // 序号
            row.addView(TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(36))
                text = "${ch.number}"
                textSize = 12f
                setTextColor(ctx.getColor(R.color.textDim))
                gravity = Gravity.CENTER
            })

            // 通道名
            row.addView(inputField(0, 1f).apply {
                setText(ch.name)
                hint = "通道名"
                setOnFocusChangeListener { _, _ ->
                    channels[idx] = channels[idx].copy(name = text.toString().trim())
                    onChanged()
                }
            })

            // 属性 → 点击选择
            val tvAttr = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(80), dp(36)).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                background = GradientDrawable().apply {
                    setColor(ctx.getColor(R.color.surface2))
                    cornerRadius = dp(10).toFloat()
                }
                gravity = Gravity.CENTER
                textSize = 11f
                maxLines = 1
                setPadding(dp(4), 0, dp(4), 0)
                text = if (ch.attribute.isEmpty()) "选择" else ch.attribute
                setTextColor(if (ch.attribute.isEmpty())
                    ctx.getColor(R.color.textDim) else ctx.getColor(R.color.accent))
                setOnClickListener { showAttrPicker(idx, this, onChanged) }
            }
            row.addView(tvAttr)

            // 默认值
            row.addView(inputField(40, 0f).apply {
                setText("${ch.defaultValue}")
                gravity = Gravity.CENTER
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setOnFocusChangeListener { _, _ ->
                    channels[idx] = channels[idx].copy(defaultValue = text.toString().toIntOrNull() ?: 0)
                    onChanged()
                }
            })

            // 高亮值
            row.addView(inputField(40, 0f).apply {
                setText("${ch.highlightValue}")
                gravity = Gravity.CENTER
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setOnFocusChangeListener { _, _ ->
                    channels[idx] = channels[idx].copy(highlightValue = text.toString().toIntOrNull() ?: 0)
                    onChanged()
                }
            })

            // Fine 开关
            row.addView(CheckBox(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                buttonTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.accent))
                isChecked = ch.hasFine
                setOnCheckedChangeListener { _, checked ->
                    channels[idx] = channels[idx].copy(hasFine = checked)
                    onChanged()
                }
            })

            // 删除
            row.addView(TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginStart = dp(2)
                }
                text = "✕"
                textSize = 13f
                setTextColor(ctx.getColor(R.color.textDim))
                gravity = Gravity.CENTER
                setOnClickListener {
                    channels.removeAt(idx)
                    onChanged()
                }
            })

            container.addView(row)
        }
    }

    /** 弹出属性选择对话框。 */
    private fun showAttrPicker(idx: Int, tv: TextView, onChanged: () -> Unit) {
        val names = attrOptions.map { "${it.first}  ${it.second}" }.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("选择属性")
            .setItems(names) { _, which ->
                val selected = attrOptions[which].first
                channels[idx] = channels[idx].copy(attribute = selected)
                tv.text = selected
                tv.setTextColor(ctx.getColor(R.color.accent))
                onChanged()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 加载已有灯型到编辑器。 */
    fun loadFixture(def: FixtureDef) {
        channels.clear()
        val sorted = def.channels.sortedBy { it.number }
        sorted.forEach { ch ->
            channels.add(ChData(
                number = ch.number,
                name = ch.originalName,
                attribute = ch.attribute,
                defaultValue = ch.defaultValue,
                highlightValue = ch.highlightValue,
                hasFine = ch.hasFine,
                physFrom = ch.physFrom,
                physTo = ch.physTo
            ))
        }
    }

    /** 从编辑器数据构建 FixtureDef。 */
    fun buildFixture(name: String, manufacturer: String, mode: String,
                     panRange: Float, tiltRange: Float): FixtureDef {
        val chs = channels.mapIndexed { _, d ->
            FixtureChannel(
                number = d.number,
                name = d.name.ifEmpty { "CH${d.number}" },
                originalName = d.name.ifEmpty { "CH${d.number}" },
                attribute = d.attribute,
                defaultValue = d.defaultValue,
                highlightValue = d.highlightValue,
                hasFine = d.hasFine,
                fineNumber = if (d.hasFine) d.number + 1 else null,
                physFrom = d.physFrom,
                physTo = d.physTo
            )
        }
        val maxCh = channels.maxOfOrNull {
            if (it.hasFine) maxOf(it.number, it.number + 1) else it.number
        } ?: channels.size
        val id = "${manufacturer}_${name}_${mode}".lowercase()
            .replace(Regex("[^a-z0-9_]"), "_").replace(Regex("_+"), "_").trim('_')

        val ptSp = chs.find {
            it.attribute.equals("PTSPEED", ignoreCase = true) ||
            it.attribute.equals("PT_SPEED", ignoreCase = true)
        }?.number

        return FixtureDef(
            id = id, name = name, manufacturer = manufacturer, mode = mode,
            channelCount = maxCh, channels = chs,
            panRange = panRange, tiltRange = tiltRange, ptSpeedCh = ptSp
        )
    }

    /** 生成 MA2 兼容 XML。 */
    fun buildMa2Xml(def: FixtureDef): String {
        val sb = StringBuilder()
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        sb.append("""<?xml version="1.0" encoding="utf-8"?>
<MA xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xmlns="http://schemas.malighting.de/grandma2/xml/MA"
    xsi:schemaLocation="http://schemas.malighting.de/grandma2/xml/MA http://schemas.malighting.de/grandma2/xml/3.3.4/MA.xsd"
    major_vers="3" minor_vers="3" stream_vers="4">
  <Info datetime="${date}T00:00:00" showfile="StageDMX" />
  <FixtureType name="${escXml(def.name)}" mode="${escXml(def.mode)}">
    <short_name>${escXml(def.name)}</short_name>
    <manufacturer>${escXml(def.manufacturer)}</manufacturer>
    <short_manufacturer>${escXml(def.manufacturer.take(8))}</short_manufacturer>
    <Modules index="0">
      <Module index="0" class="Headmover" beamtype="Wash">
""")
        for ((idx, ch) in def.channels.withIndex()) {
            val attr = ch.attribute.ifEmpty { "CH${ch.number}" }
            val defVal = "%.3f".format(ch.defaultValue / 255.0 * 100)
            val hlVal = "%.3f".format(ch.highlightValue / 255.0 * 100)
            val pf = if (ch.physFrom != 0f) " physfrom=\"${ch.physFrom}\"" else ""
            val pt = if (ch.physTo != 0f) " physto=\"${ch.physTo}\"" else ""
            val fine = if (ch.hasFine) " fine=\"${ch.number + 1}\"" else ""
            sb.append("""        <ChannelType index="$idx" attribute="${escXml(attr)}" coarse="${ch.number}" default="$defVal" highlight_value="$hlVal"$fine>
          <ChannelFunction index="0" from="0" to="100" min_dmx_24="0" max_dmx_24="16777215"$pf$pt
            subattribute="${escXml(attr)}" subattribute_user_name="${escXml(ch.originalName.ifEmpty { "CH${ch.number}" })}"
            attribute="${escXml(attr)}" attribute_user_name="${escXml(ch.originalName.ifEmpty { ch.name.ifEmpty { "CH${ch.number}" } })}" />
        </ChannelType>
""")
        }
        sb.append("""      </Module>
    </Modules>
  </FixtureType>
</MA>
""")
        return sb.toString()
    }

    /** 导出为 ZIP 并通过 Share sheet 发送。 */
    fun exportZip(def: FixtureDef, contentResolver: android.content.ContentResolver) {
        val xml = buildMa2Xml(def)
        val shortMfr = def.manufacturer.take(8).replace(Regex("[^a-zA-Z0-9]"), "")
        val xmlName = "${shortMfr.lowercase()}@${def.name.lowercase().replace(' ', '-')}@${def.mode.lowercase().replace(' ', '-')}.xml"
        val zipDir = "${def.manufacturer} ${def.name}"
        val innerPath = "$zipDir/MA灯库/$xmlName"

        val cacheDir = File(ctx.cacheDir, "fixture_export")
        cacheDir.mkdirs()
        val zipFile = File(cacheDir, "${def.id}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry("$zipDir/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("$zipDir/MA灯库/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(innerPath))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "导出灯库 ${def.name}"))
    }

    /** 保存到内部存储。 */
    fun saveToStore(def: FixtureDef, rawXml: ByteArray) {
        val json = FixtureStore.fixtureToJson(def)
        val dir = File(ctx.filesDir, "fixtures").also { it.mkdirs() }
        File(dir, "${def.id}.json").writeText(json.toString(2))
        File(dir, "${def.id}.xml").writeBytes(rawXml)
    }

    private fun escXml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}

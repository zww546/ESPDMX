#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阶段 2：把布局/菜单里的中文 android:text/hint/title 迁移到 strings.xml。

做三件事：
  1. 为每条中文生成稳定的资源 key（s_ + 英文 slug）
  2. 写出 values/strings.xml（中文）与 values-en/strings.xml（英文）
  3. 把布局里的字面量换成 @string/key

翻译来源：优先复用 Lang.kt 里已有的字典（上一轮资产），其余用本文件 NEW_EN 补齐。

用法:
  python tools/i18n_migrate_layout.py --dry-run   # 只看会改什么
  python tools/i18n_migrate_layout.py             # 实际写入
"""
import argparse
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

d = os.path.dirname(os.path.abspath(__file__))
ROOT = None
for _ in range(6):
    if os.path.isdir(os.path.join(d, "StageDMX")):
        ROOT = d
        break
    d = os.path.dirname(d)
if ROOT is None:
    raise SystemExit("找不到仓库根目录")

RES = os.path.join(ROOT, "StageDMX", "app", "src", "main", "res")
LANG_KT = os.path.join(ROOT, "StageDMX", "app", "src", "main", "java",
                       "com", "example", "stagedmx", "Lang.kt")
XML_ATTR = re.compile(r'(android:(?:text|hint|title)=")([^"]*)(")')
CJK = re.compile(r"[\u4e00-\u9fff]")

# 布局里还缺的 58 条（Lang.kt 字典没有的）
NEW_EN = {
    "+ 通道": "+ Channels",
    "0 通道": "0 Ch",
    "A 通道": "Band A",
    "App灯库": "App Library",
    "B 通道": "Band B",
    "ESP32 设备灯库": "ESP32 Device Library",
    "U盘模式": "USB Mode",
    "← 返回设置": "← Back to Settings",
    "⬆ 上级": "⬆ Up",
    "一键应用": "Apply",
    "上次设备：无": "Last device: none",
    "作用范围：单台": "Scope: single",
    "保存当前": "Save current",
    "全部关闭": "Stop all",
    "包络": "Envelope",
    "厂家": "Vendor",
    "取消全选": "Deselect all",
    "名称": "Name",
    "导入灯库": "Import fixtures",
    "导出 ZIP": "Export ZIP",
    "属性": "Attribute",
    "已进入多选：点行勾选，可「全选」后删除": "Multi-select: tap rows, then Select all to delete",
    "已选 0": "0 selected",
    "幅度: 128": "Amp: 128",
    "开启": "On",
    "当前灯具": "Current fixture",
    "扩散: 0°": "Spread: 0°",
    "批量": "Batch",
    "播放": "Play",
    "效果预设": "Effect presets",
    "数量（台）": "Count",
    "新建": "New",
    "方向": "Direction",
    "无": "None",
    "未启动": "Stopped",
    "模式": "Mode",
    "模拟": "Simulate",
    "步时间(秒)": "Step time (s)",
    "波形": "Waveform",
    "清空步": "Clear steps",
    "灯型": "Fixture type",
    "灯库编辑器": "Fixture editor",
    "点=预览  长按=删除步": "Tap = preview, long-press = delete step",
    "点按下载到 App": "Tap to download to App",
    "相位: 0°": "Phase: 0°",
    "编辑已有灯库": "Edit existing library",
    "自动连接": "Auto connect",
    "行程": "Range",
    "记录一步": "Record step",
    "起始地址（1-512）": "Start address (1-512)",
    "还没有扫描到 RDM 设备\\n\\n点下面的「扫描设备」开始\\n（扫描期间该通道的 DMX 输出会短暂暂停）":
        "No RDM devices yet\\n\\nTap Scan below to start\\n(DMX output pauses briefly)",
    "通道 A / B（A = 宇宙1 口，B = 宇宙2 口）":
        "Band A / B (A = universe 1, B = universe 2)",
    "通道列表": "Channel list",
    "通道名": "Channel name",
    "速度: 128": "Speed: 128",
    "高亮": "Highlight",
    "默认": "Default",
    "＋ 新建文件夹": "+ New folder",
}


def load_lang_dict():
    src = open(LANG_KT, encoding="utf-8").read()
    body = src.split("private val EN: Map<String, String> = mapOf(", 1)[1]
    body = body.split("\n    )", 1)[0]
    pat = re.compile(r'"((?:[^"\\]|\\.)*)"\s+to\s+"((?:[^"\\]|\\.)*)"')
    return {a: b for a, b in pat.findall(body)}


def slugify(en):
    s = re.sub(r"[^0-9a-zA-Z]+", "_", en).strip("_").lower()
    return (s[:40] or "s")


def xml_escape(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    zh2en = dict(load_lang_dict())
    missing = []
    for k, v in NEW_EN.items():
        if k not in zh2en:
            zh2en[k] = v

    # 扫描布局，收集所有需要迁移的字面量
    targets = []          # (path, 原文, key)
    key_of = {}
    used_keys = set()
    for sub in ("layout", "menu"):
        dd = os.path.join(RES, sub)
        for name in sorted(os.listdir(dd)):
            if not name.endswith(".xml"):
                continue
            path = os.path.join(dd, name)
            raw = open(path, encoding="utf-8").read()
            for m in XML_ATTR.finditer(raw):
                text = m.group(2)
                if not CJK.search(text):
                    continue
                if text not in zh2en:
                    missing.append(text)
                    continue
                if text not in key_of:
                    base = "s_" + slugify(zh2en[text])
                    k = base
                    i = 2
                    while k in used_keys:
                        k = f"{base}_{i}"
                        i += 1
                    used_keys.add(k)
                    key_of[text] = k
                targets.append((path, text, key_of[text]))

    if missing:
        print("⚠ 以下词条没有翻译，已跳过：")
        for s in sorted(set(missing)):
            print("   " + s)
        print()

    print(f"将迁移 {len(targets)} 处，涉及 {len(key_of)} 条唯一词条")
    for s, k in list(key_of.items())[:8]:
        print(f"   {k:42s} {s}  ->  {zh2en[s]}")
    print("   ...")

    if args.dry_run:
        return

    # ---- 写 values/strings.xml（中文，默认）----
    zh_path = os.path.join(RES, "values", "strings.xml")
    zh_lines = ['<resources>', '    <string name="app_name">StageDMX</string>', '']
    zh_lines.append('    <!-- ===== 界面文字（阶段 2：布局/菜单）===== -->')
    for s, k in sorted(key_of.items(), key=lambda kv: kv[1]):
        zh_lines.append(f'    <string name="{k}">{xml_escape(s)}</string>')
    zh_lines.append('</resources>')
    open(zh_path, "w", encoding="utf-8", newline="\n").write("\n".join(zh_lines) + "\n")

    # ---- 写 values-en/strings.xml ----
    en_dir = os.path.join(RES, "values-en")
    os.makedirs(en_dir, exist_ok=True)
    en_lines = ['<resources>', '    <string name="app_name">StageDMX</string>', '']
    en_lines.append('    <!-- ===== UI strings (phase 2: layouts/menu) ===== -->')
    for s, k in sorted(key_of.items(), key=lambda kv: kv[1]):
        en_lines.append(f'    <string name="{k}">{xml_escape(zh2en[s])}</string>')
    en_lines.append('</resources>')
    open(os.path.join(en_dir, "strings.xml"), "w", encoding="utf-8",
         newline="\n").write("\n".join(en_lines) + "\n")

    # ---- 改写布局 ----
    changed = 0
    for path in sorted({t[0] for t in targets}):
        raw = open(path, encoding="utf-8").read()

        def repl(m):
            nonlocal changed
            text = m.group(2)
            if text in key_of:
                changed += 1
                return f'{m.group(1)}@string/{key_of[text]}{m.group(3)}'
            return m.group(0)

        new = XML_ATTR.sub(repl, raw)
        if new != raw:
            open(path, "w", encoding="utf-8", newline="").write(new)

    print(f"\n已写出 {zh_path}")
    print(f"已写出 {os.path.join(en_dir, 'strings.xml')}")
    print(f"已改写布局属性 {changed} 处")


if __name__ == "__main__":
    main()

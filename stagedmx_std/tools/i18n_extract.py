#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
i18n 提取工具：列出所有需要翻译的界面文字，用于迁移到 strings.xml。

为什么要单独写脚本而不是用 PowerShell 一行命令：
  PowerShell 的控制台编码会把中文输出成乱码，且正则跨属性匹配会污染结果。
  Python 的文件读写与 stdout 都是标准 UTF-8，结果可靠、可复现。

用法:
  python tools/i18n_extract.py              # 汇总统计
  python tools/i18n_extract.py --list       # 列出全部词条（含出处）
  python tools/i18n_extract.py --layout     # 只列布局/菜单
  python tools/i18n_extract.py --kotlin     # 只列 Kotlin
  python tools/i18n_extract.py --json out.json
"""
import argparse
import json
import os
import re
import sys

def find_root():
    """从脚本位置向上找到含 StageDMX/ 的仓库根目录。

    这样脚本放在 stagedmx_std/tools/ 下也能用，不依赖相对层数写死。
    """
    d = os.path.dirname(os.path.abspath(__file__))
    for _ in range(6):
        if os.path.isdir(os.path.join(d, "StageDMX")):
            return d
        d = os.path.dirname(d)
    raise SystemExit("找不到含 StageDMX/ 的仓库根目录")


ROOT = find_root()
APP = os.path.join(ROOT, "StageDMX", "app", "src", "main")
JAVA = os.path.join(APP, "java", "com", "example", "stagedmx")
RES = os.path.join(APP, "res")

CJK = re.compile(r"[\u4e00-\u9fff]")
# 布局：只取 android:text / hint / title 的完整属性值（引号配对，不跨属性）
XML_ATTR = re.compile(r'android:(text|hint|title)="([^"]*)"')
# Kotlin 字符串字面量（不处理三引号与转义引号，本工程未使用）
KT_STR = re.compile(r'"((?:[^"\\]|\\.)*)"')
# 不算界面文字的行
SKIP_LINE = re.compile(r"^\s*(//|\*|/\*)|Log\.[diwev]\(|ESP_LOG|import ")


def has_cjk(s):
    return bool(CJK.search(s))


def scan_layouts():
    out = []
    for sub in ("layout", "menu"):
        d = os.path.join(RES, sub)
        if not os.path.isdir(d):
            continue
        for name in sorted(os.listdir(d)):
            if not name.endswith(".xml"):
                continue
            path = os.path.join(d, name)
            with open(path, encoding="utf-8") as f:
                for i, line in enumerate(f, 1):
                    for m in XML_ATTR.finditer(line):
                        text = m.group(2)
                        if has_cjk(text):
                            out.append({"file": f"{sub}/{name}", "line": i,
                                        "attr": m.group(1), "text": text})
    return out


def scan_kotlin():
    out = []
    for name in sorted(os.listdir(JAVA)):
        if not name.endswith(".kt"):
            continue
        path = os.path.join(JAVA, name)
        with open(path, encoding="utf-8") as f:
            for i, line in enumerate(f, 1):
                if SKIP_LINE.search(line):
                    continue
                for m in KT_STR.finditer(line):
                    text = m.group(1)
                    if has_cjk(text):
                        out.append({"file": name, "line": i, "text": text})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--layout", action="store_true")
    ap.add_argument("--kotlin", action="store_true")
    ap.add_argument("--json")
    args = ap.parse_args()

    lay, kt = scan_layouts(), scan_kotlin()
    only_lay, only_kt = args.layout, args.kotlin
    if only_lay:
        kt = []
    if only_kt:
        lay = []

    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump({"layout": lay, "kotlin": kt}, f, ensure_ascii=False, indent=1)
        print(f"已写出 {args.json}")

    if args.list:
        for tag, items in (("布局/菜单", lay), ("Kotlin", kt)):
            if not items:
                continue
            print(f"\n===== {tag}（{len(items)} 处）=====")
            for it in items:
                attr = f"[{it.get('attr')}] " if it.get("attr") else ""
                print(f"{it['file']}:{it['line']}  {attr}{it['text']}")
        return

    # 汇总：Kotlin 与布局合并去重
    combined = {it["text"] for it in lay} | {it["text"] for it in kt}
    print(f"布局/菜单   : {len(lay)} 处，去重 {len({i['text'] for i in lay})} 条")
    print(f"Kotlin      : {len(kt)} 处，去重 {len({i['text'] for i in kt})} 条")
    print(f"合计需翻译  : 去重 {len(combined)} 条")
    # 带插值的（${...} 或 $var）单独统计：这些没法做精确匹配，
    # 迁 strings.xml 时要转成带占位符的资源（%1$s / %1$d）
    interp = [s for s in combined if "$" in s]
    print(f"其中含插值  : {len(interp)} 条（迁移时需转成 %1$s/%1$d 占位符）")


if __name__ == "__main__":
    main()

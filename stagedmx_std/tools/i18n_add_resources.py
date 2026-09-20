#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
补齐 strings.xml 里缺失的界面词条。

## 为什么这条路比"迁移调用点"划算
[Lang.apply] 遍历 View 树时是**按文字内容**匹配的（中↔英映射由资源自动生成）。
所以只要词条进了 strings.xml，**界面上的文字就会自动被翻译，不用改任何调用点**。
批 1 之所以要改调用点，只因为 toast / 对话框不在 View 树里。

于是剩余工作从"改造 900 处调用点"变成"补 ~400 条词条" —— 安全得多，
而且漏一条只是那一条不翻译，不会编译失败、不会崩。

## 排除规则（很重要）
`"调光" to "DIM"`、`0x0101 -> "固定光斑"` 这类是**数据表**，属于灯库通道翻译的
数据，不是 App 界面语言。迁进资源会让中英切换后**查不到数据**，必须排除。

用法:
    python tools/i18n_add_resources.py --dump      # 导出待补词条
    python tools/i18n_add_resources.py             # 按翻译文件补齐资源
"""
import argparse
import json
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

JAVA = os.path.join(ROOT, "StageDMX", "app", "src", "main", "java",
                    "com", "example", "stagedmx")
RES = os.path.join(ROOT, "StageDMX", "app", "src", "main", "res")
TOOLS = os.path.join(ROOT, "stagedmx_std", "tools")

CJK = re.compile(r"[\u4e00-\u9fff]")
SKIP_LINE = re.compile(r"^\s*(//|\*|/\*)|Log\.[diwev]\(|ESP_LOG|import ")
DONE_CALL = re.compile(r"Lang\.t\(|@string/|R\.string\.")
# 数据表：字面量紧跟 to/->  ，或前面是 0x0101 -> / 数字 ->
DATA_AFTER = re.compile(r'\s*(?:to|->)\s*"')
DATA_BEFORE = re.compile(r'(?:0x[0-9A-Fa-f]+|\d+)\s*->\s*$')
# 不像界面文字的行（路径、键名、正则、tag）
NOT_UI = re.compile(r'substringAfter|\.json|\.xml|\.zip|\.d4|\.r20|'
                    r'File\(|dir\b|path\b|TAG|regex|Pattern|'
                    r'MA灯库|/MA|assets/|Log\.|android\.util\.Log')


def scan_literal(text, start):
    """取完整字符串字面量（处理 ${...} 嵌套）→ (end, 内容, 插值是否简单)。"""
    i = start + 1
    depth = 0
    nested = False
    while i < len(text):
        c = text[i]
        if c == "\\":
            i += 2
            continue
        if depth == 0:
            if c == '"':
                return i + 1, text[start + 1:i], not nested
            if c == "$" and i + 1 < len(text) and text[i + 1] == "{":
                depth = 1
                i += 2
                continue
        else:
            if c == '"':
                nested = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    i += 1
                    continue
        i += 1
    return len(text), text[start + 1:], False


def load_values(tag):
    p = os.path.join(RES, tag, "strings.xml")
    if not os.path.exists(p):
        return {}
    return {m.group(1): m.group(2) for m in re.finditer(
        r'<string name="([^"]+)">(.*?)</string>', open(p, encoding="utf-8").read(), re.S)}


def scan_strings(text):
    """逐字符扫描 Kotlin 源码，产出 (start, end, 内容, 插值是否简单)。

    ⚠ 为什么不能用"找引号"的土办法：块注释 `/* … */` 里的引号、
      字符常量、以及 `${...}` 里嵌的字符串都会把配对搞乱，
      结果是跨几十行代码的"巨型字符串"（实测踩过：导出了 564 条，第一条是整段代码）。

    这里维护一个小状态机：CODE / LINE_COMMENT / BLOCK_COMMENT / STRING。
    """
    i = 0
    n = len(text)
    out = []
    while i < n:
        c = text[i]
        # 行注释
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j + 1
            continue
        # 块注释（支持嵌套）
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            depth = 1
            i += 2
            while i < n and depth:
                if text.startswith("/*", i):
                    depth += 1
                    i += 2
                elif text.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
            continue
        # 字符串
        if c == '"':
            # 三引号原始字符串
            if text.startswith('"""', i):
                j = text.find('"""', i + 3)
                i = n if j < 0 else j + 3
                continue
            end, lit, simple = scan_literal(text, i)
            out.append((i, end, lit, simple))
            i = end
            continue
        i += 1
    return out


def _parser_strings():
    """FixtureParser.kt 里的中文 —— 那是"MA attribute ↔ 中文名"的数据表。

    判据：某个中文词条如果**同时**出现在 FixtureParser 和别处，它就是属性名数据，
    不是界面文字（属性名由既有的"通道名翻译"开关负责，不该进 App 语言系统）。
    """
    p = os.path.join(JAVA, "FixtureParser.kt")
    if not os.path.exists(p):
        return set()
    text = open(p, encoding="utf-8").read()
    return {lit for (_s, _e, lit, _sim) in scan_strings(text) if CJK.search(lit)}


def collect():
    """返回 {中文原文: 出现位置} —— 所有像界面文字的中文字面量。"""
    zh_res = load_values("values")
    have = set(zh_res.values())
    attr = _parser_strings()
    found = {}
    for name in sorted(os.listdir(JAVA)):
        # FixtureParser.kt 是"MA attribute ↔ 中文名"的数据表（灯库通道翻译功能的数据），
        # 不属于 App 界面语言 —— 迁进资源会让那个已有开关失效，必须排除
        if not name.endswith(".kt") or name in ("Lang.kt", "FixtureParser.kt"):
            continue
        text = open(os.path.join(JAVA, name), encoding="utf-8").read()
        for (_s, end, lit, _simple) in scan_strings(text):
            if not CJK.search(lit) or lit in have or lit.startswith("@"):
                continue
            line_start = text.rfind("\n", 0, _s) + 1
            line_end = text.find("\n", _s)
            line = text[line_start:line_end if line_end > 0 else len(text)]
            if DONE_CALL.search(line):
                continue
            if DATA_AFTER.match(text[end:]) or DATA_BEFORE.search(line):
                continue
            if NOT_UI.search(line):
                continue
            if lit in attr:      # 属性名数据表，交给"通道名翻译"开关
                continue
            found.setdefault(lit, f"{name}:{text[:_s].count(chr(10)) + 1}")
    return found, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump", action="store_true")
    args = ap.parse_args()

    found, _ = collect()
    print(f"缺失的界面词条: {len(found)} 条")

    if args.dump:
        p = os.path.join(TOOLS, "i18n_need_translate.json")
        with open(p, "w", encoding="utf-8") as f:
            json.dump(found, f, ensure_ascii=False, indent=1)
        print(f"已导出 {p}")
        for i, (k, v) in enumerate(sorted(found.items()), 1):
            print(f"{i:3d}\t{k}")
        return

    # ---- 按翻译文件补齐资源 ----
    tr_path = os.path.join(TOOLS, "i18n_added_en.json")
    if not os.path.exists(tr_path):
        raise SystemExit(f"缺少翻译文件 {tr_path}（先跑 --dump 再翻译）")
    tr = json.load(open(tr_path, encoding="utf-8"))

    used = set(load_values("values"))
    add_zh, add_en = {}, {}
    miss = []
    for zh, _where in sorted(found.items()):
        en = tr.get(zh)
        if en is None:
            miss.append(zh)
            continue
        key = "t_" + re.sub(r"[^0-9a-zA-Z]+", "_", en).strip("_").lower()[:44]
        base, i = key, 2
        while key in used:
            key = f"{base}_{i}"
            i += 1
        used.add(key)
        add_zh[key] = zh
        add_en[key] = en

    def esc(s):
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    for tag, mp in (("values", add_zh), ("values-en", add_en)):
        p = os.path.join(RES, tag, "strings.xml")
        src = open(p, encoding="utf-8").read()
        add = "".join(f'    <string name="{k}">{esc(v)}</string>\n'
                      for k, v in sorted(mp.items()))
        src = src.replace("</resources>", add + "</resources>")
        open(p, "w", encoding="utf-8", newline="\n").write(src)

    print(f"已补 {len(add_zh)} 条资源；未翻译 {len(miss)} 条")
    for m in miss[:20]:
        print("   缺翻译:", m)


if __name__ == "__main__":
    main()

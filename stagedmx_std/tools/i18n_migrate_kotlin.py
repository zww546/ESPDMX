#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
阶段 3：把 Kotlin 里的界面中文迁移到 strings.xml。

## 为什么必须用白名单而不是"全部替换"
工程里的中文并非都是界面文字，还有**逻辑用的键**，例如：
    prefs.getString("上次设备", ...)
    mapOf("未启动" to 0, ...)
无脑翻译会把它们换成语言相关内容 —— 中英切换后**查不到数据**，
属于上线才炸、现场极难定位的 bug。所以只改确定要显示给用户的上下文。

## 处理流程
  1. 按白名单上下文找出界面中文字面量
  2. 用**字符串字面量扫描器**取完整字面量（正确处理 ${...} 嵌套花括号/引号）
  3. 插值简单的：转成 %1$s 占位符；插值里嵌 if/字符串的：标记人工处理
  4. 键名由**英文**派生（所以要读 i18n_phase3_en.json 的人工翻译）
  5. 落盘 values/values-en，并按**字符位置**精确替换调用点

用法:
    python tools/i18n_migrate_kotlin.py --dry-run
    python tools/i18n_migrate_kotlin.py
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
SKIP = re.compile(r"^\s*(//|\*|/\*)|Log\.[diwev]\(|ESP_LOG|import ")

# 白名单上下文：正则匹配到**开引号之前**为止
CTX = [
    ("toast",     re.compile(r'\btoast\(\s*')),
    ("toast2",    re.compile(r'\bToast\.makeText\([^,()]+,\s*')),
    ("text",      re.compile(r'\.text\s*=\s*')),
    ("setText",   re.compile(r'\.setText\(\s*')),
    ("title",     re.compile(r'\.setTitle\(\s*')),
    ("message",   re.compile(r'\.setMessage\(\s*')),
    ("hint",      re.compile(r'\.hint\s*=\s*')),
    ("desc",      re.compile(r'contentDescription\s*=\s*')),
    ("button",    re.compile(r'set(?:Positive|Negative|Neutral)Button\(\s*')),
    ("langcall",  re.compile(r'\bLang\.t\(\s*')),
    # ---- 批次 2 新增 ----
    ("append",    re.compile(r'\.append\(\s*')),           # buildString 拼界面文字
    ("whenbr",    re.compile(r'->\s*')),                   # when 分支返回值
    ("listof",    re.compile(r'\b(?:listOf|mutableListOf|arrayListOf|arrayOf)\(\s*')),
    ("add",       re.compile(r'\.add\(\s*')),              # 往列表里塞界面文字
    ("assign",    re.compile(r'=\s*')),                    # 赋值（由 DATA 规则兜底拦截）
]

# ⚠ 数据表排除：这些是**逻辑数据**，不是 App 界面语言，绝不能迁。
#   典型：属性名映射 `"调光" to "DIM"`、类别名 `0x0101 -> "固定光斑"`。
#   迁了会导致中英切换后查不到数据（上线才炸、现场极难定位）。
#
#   ⚠ 必须只看字面量**紧邻**的前后文，不能拿整行匹配：
#     否则 whenbr 上下文里的 `->` 会把所有分支返回值都误判成数据表。
DATA_AFTER = re.compile(r'\s*(?:to|->)\s*"')
DATA_BEFORE = re.compile(
    r'(?:0x[0-9A-Fa-f]+|\d+)\s*->\s*$'
    r'|(?:getString|putString|putInt|putBoolean|getInt|getBoolean)\(\s*$'
    r'|\bKEY_[A-Z_]*\s*=?\s*$')

INTERP = re.compile(r"\$\{([^}]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)")


def scan_literal(text, start):
    """扫出一个完整字符串字面量。返回 (结束位置, 内容, 插值是否简单)。

    不能简单用正则 \\$\\{([^}]*)\\}：`${if (x) "A" else "B"}` 会在第一个 `}`
    就截断，产生脏数据。这里用深度计数 + 嵌套引号标记。
    """
    assert text[start] == '"'
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


def interp_args(lit):
    """按出现顺序取出插值表达式。"""
    return [(a or b).strip() for a, b in INTERP.findall(lit)]


def to_android(tmpl):
    """${expr}/$var → %1$s/%2$s（String 占位符接受任何类型，不会崩）。"""
    n = [0]

    def rep(_m):
        n[0] += 1
        return "%" + str(n[0]) + "$s"

    return INTERP.sub(rep, tmpl)


def slugify(s):
    t = re.sub(r"[^0-9a-zA-Z]+", "_", s).strip("_").lower()
    return t[:48] or "s"


def load_existing():
    """读回已有资源：key->值，以及中文值->key（阶段 2 建的复用）。"""
    zh, en = {}, {}
    for tag, mp in (("values", zh), ("values-en", en)):
        p = os.path.join(RES, tag, "strings.xml")
        if not os.path.exists(p):
            continue
        for m in re.finditer(r'<string name="([^"]+)">(.*?)</string>',
                             open(p, encoding="utf-8").read(), re.S):
            mp[m.group(1)] = m.group(2)
    return zh, en, {v: k for k, v in zh.items()}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    en_map = json.load(open(os.path.join(TOOLS, "i18n_phase3_en.json"),
                            encoding="utf-8"))
    zh_res, en_res, res_rev = load_existing()

    hits = []        # (file, line, start, end, zh_lit, key, args_expr)
    manual = []
    skipped_data = []
    new_zh, new_en = {}, {}
    used = set(zh_res)
    seen_lit = {}    # zh_lit -> key

    for name in sorted(os.listdir(JAVA)):
        if not name.endswith(".kt") or name == "Lang.kt":
            continue
        lines = open(os.path.join(JAVA, name), encoding="utf-8").read().split("\n")
        offset = 0
        for i, line in enumerate(lines):
            if not SKIP.search(line):
                for _kind, pat in CTX:
                    m = pat.search(line)
                    if not m or m.end() >= len(line) or line[m.end()] != '"':
                        continue
                    end, lit, simple = scan_literal(line, m.end())
                    if not CJK.search(lit) or lit.startswith("@"):
                        continue
                    # 数据表/逻辑键：一律不迁
                    if DATA_AFTER.match(line[end:]) or DATA_BEFORE.search(line[:m.end()]):
                        skipped_data.append((name, i + 1, lit))
                        continue
                    if not simple:
                        manual.append((name, i + 1, lit))
                        continue
                    if lit in res_rev:
                        key = res_rev[lit]
                    elif lit in seen_lit:
                        key = seen_lit[lit]
                    else:
                        en_lit = en_map.get(lit)
                        if en_lit is None:
                            manual.append((name, i + 1, lit))
                            continue
                        key = "k_" + slugify(to_android(en_lit))
                        base, k = key, 2
                        while key in used:
                            key = f"{base}_{k}"
                            k += 1
                        used.add(key)
                        new_zh[key] = to_android(lit)
                        new_en[key] = to_android(en_lit)
                        seen_lit[lit] = key
                    hits.append((name, i + 1, offset + m.end() + 1,
                                 offset + end, lit, key, interp_args(lit)))
            offset += len(line) + 1

    print(f"可迁移: {len(hits)} 处 | 新增资源: {len(new_zh)} 条 | "
          f"人工: {len(manual)} 处 | 数据表已跳过: {len(skipped_data)} 处")
    for m_ in manual:
        print(f"   ! {m_[0]}:{m_[1]}  {m_[2]}")
    for h in hits[:6]:
        a = ("  参数: " + ", ".join(h[6])) if h[6] else ""
        print(f"   {h[0]}:{h[1]}  {h[4]}  ->  R.string.{h[5]}{a}")

    if args.dry_run:
        return

    def esc(s):
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    for tag, mp in (("values", new_zh), ("values-en", new_en)):
        p = os.path.join(RES, tag, "strings.xml")
        src = open(p, encoding="utf-8").read()
        add = "".join(f'    <string name="{k}">{esc(v)}</string>\n'
                      for k, v in sorted(mp.items()))
        src = src.replace("</resources>", add + "</resources>")
        open(p, "w", encoding="utf-8", newline="\n").write(src)

    changed = 0
    for name in sorted({h[0] for h in hits}):
        path = os.path.join(JAVA, name)
        raw = open(path, encoding="utf-8").read()
        # 从后往前替换，前面的位置不受影响
        for (_f, _ln, s, e, _lit, key, aexpr) in sorted(
                [h for h in hits if h[0] == name], key=lambda x: x[2], reverse=True):
            args = "".join(", " + a for a in aexpr)
            raw = raw[:s - 1] + f"Lang.t(R.string.{key}{args})" + raw[e:]
            changed += 1
        open(path, "w", encoding="utf-8", newline="").write(raw)

    print(f"\n已写出资源 {len(new_zh)} 条；替换调用点 {changed} 处")


if __name__ == "__main__":
    main()

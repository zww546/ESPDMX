#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
i18n 迁移规划：把 Lang.kt 里已有的 zh->en 字典与待迁词条做交叉，算出还缺多少翻译。

Lang.kt 里那 130 多条字典是上一轮的资产，迁移到 strings.xml 时可以直接复用，
不必重翻。这个脚本就是用来量化"能复用多少、还缺多少"。
"""
import json
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

ROOT = None
d = os.path.dirname(os.path.abspath(__file__))
for _ in range(6):
    if os.path.isdir(os.path.join(d, "StageDMX")):
        ROOT = d
        break
    d = os.path.dirname(d)
if ROOT is None:
    raise SystemExit("找不到仓库根目录")


def load_lang_dict():
    """从 Lang.kt 抽出 zh->en 字典。"""
    path = os.path.join(ROOT, "StageDMX", "app", "src", "main", "java",
                        "com", "example", "stagedmx", "Lang.kt")
    src = open(path, encoding="utf-8").read()
    body = src.split("private val EN: Map<String, String> = mapOf(", 1)[1]
    body = body.split("\n    )", 1)[0]
    # 匹配 "key" to "value"，允许 key/value 内部有转义引号
    pat = re.compile(r'"((?:[^"\\]|\\.)*)"\s+to\s+"((?:[^"\\]|\\.)*)"')
    return {a: b for a, b in pat.findall(body)}


def main():
    d = load_lang_dict()
    inv = json.load(open(os.path.join(ROOT, "stagedmx_std", "tools",
                                      "i18n_inventory.json"), encoding="utf-8"))
    lay = sorted({i["text"] for i in inv["layout"]})
    kt = sorted({i["text"] for i in inv["kotlin"]})

    print(f"Lang.kt 已有字典 : {len(d)} 条")
    for name, items in (("布局", lay), ("Kotlin", kt)):
        cov = [s for s in items if s in d]
        miss = [s for s in items if s not in d]
        print(f"{name:8s}待迁 {len(items):4d} 条 | 字典可复用 {len(cov):4d} | 还需翻译 {len(miss):4d}")

    miss_lay = [s for s in lay if s not in d]
    print(f"\n===== 布局还缺的 {len(miss_lay)} 条 =====")
    for s in miss_lay:
        print("  " + s)

    miss_kt = [s for s in kt if s not in d]
    print(f"\n===== Kotlin 还缺的 {len(miss_kt)} 条（前 60）=====")
    for s in miss_kt[:60]:
        print("  " + s)

    # 导出缺口清单，供后续补译
    out = os.path.join(ROOT, "stagedmx_std", "tools", "i18n_missing.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"layout": miss_lay, "kotlin": miss_kt}, f,
                  ensure_ascii=False, indent=1)
    print(f"\n缺口清单已写出: {out}")


if __name__ == "__main__":
    main()

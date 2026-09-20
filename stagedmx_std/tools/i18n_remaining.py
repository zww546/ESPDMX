#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
分析剩余未迁移的 Kotlin 中文分别处在什么上下文里。

阶段 3 第一轮只覆盖了 toast/.text/setTitle 等白名单上下文。要知道下一轮该扩哪些，
先按"字面量前面那一段代码"归类统计 —— 这比逐条看快得多。
"""
import collections
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
CJK = re.compile(r"[\u4e00-\u9fff]")
SKIP = re.compile(r"^\s*(//|\*|/\*)|Log\.[diwev]\(|ESP_LOG|import ")
# 已迁移的（不用再管）
DONE = re.compile(r"Lang\.t\(|@string/")

buckets = collections.Counter()
samples = collections.defaultdict(list)

for name in sorted(os.listdir(JAVA)):
    if not name.endswith(".kt"):
        continue
    for i, line in enumerate(open(os.path.join(JAVA, name), encoding="utf-8"), 1):
        if SKIP.search(line) or DONE.search(line):
            continue
        for m in re.finditer(r'"', line):
            seg = line[m.start():]
            if len(seg) < 2 or seg[1] == '"':
                continue
            # 取引号前最多 30 字符作为"上下文指纹"
            pre = line[max(0, m.start() - 30):m.start()].strip()
            key = re.sub(r"[A-Za-z_][A-Za-z0-9_.]*", "X", pre)[-22:]
            # 只统计真的含中文的字面量
            end = line.find('"', m.start() + 1)
            if end < 0:
                continue
            lit = line[m.start() + 1:end]
            if not CJK.search(lit):
                continue
            buckets[key] += 1
            if len(samples[key]) < 2:
                samples[key].append(f"{name}:{i}  {lit[:40]}")

print(f"共 {sum(buckets.values())} 处剩余，按上下文指纹归类（Top 20）：\n")
for k, v in buckets.most_common(20):
    print(f"{v:4d}  …{k!r}")
    for s in samples[k]:
        print(f"        {s}")

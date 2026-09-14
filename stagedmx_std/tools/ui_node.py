"""查看 uiautomator dump 里某个文本节点的完整属性（排查输入框清不干净之类的问题）。"""
import re
import sys

x = open("_ui.xml", encoding="utf-8").read()
want = sys.argv[1] if len(sys.argv) > 1 else ""
for m in re.finditer(r"<node[^>]*?/?>", x):
    s = m.group(0)
    t = re.search(r'\btext="([^"]*)"', s)
    if t and want and want in t.group(1):
        print(s)
        print("-" * 70)

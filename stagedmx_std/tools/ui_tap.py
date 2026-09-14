"""通过 adb 查看/点击 App 界面元素（UI 自动化验证用）。

用法：
    python stagedmx_std/tools/ui_tap.py list                  # 列出当前界面上可点的元素
    python stagedmx_std/tools/ui_tap.py tap "灯具"            # 按文字点击（模糊匹配）
    python stagedmx_std/tools/ui_tap.py shot out.png          # 截图（自动 pull）
    python stagedmx_std/tools/ui_tap.py text                  # 打印当前界面所有文字
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ADB = "adb"


def sh(*args):
    return subprocess.run([ADB] + list(args), capture_output=True, text=True,
                          encoding="utf-8", errors="replace")


def dump():
    # 强制刷新：uiautomator dump 有时会因窗口刚变化而失败/返回旧文件，
    # 先删掉设备上的旧文件，确保拿到的是当前界面。
    sh("shell", "rm", "-f", "/sdcard/_ui.xml")
    sh("shell", "uiautomator", "dump", "/sdcard/_ui.xml")
    sh("pull", "/sdcard/_ui.xml", "_ui.xml")
    with open("_ui.xml", encoding="utf-8") as f:
        return f.read()


def nodes(xml):
    root = ET.fromstring(xml)
    out = []

    def walk(n):
        b = n.get("bounds") or ""
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            label = n.get("text") or n.get("content-desc") or ""
            out.append(dict(label=label, cx=(x1 + x2) // 2, cy=(y1 + y2) // 2,
                            cls=n.get("class") or "", clickable=n.get("clickable") == "true"))
        for c in n:
            walk(c)

    walk(root)
    return out


def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__)
        return 1
    cmd = a[0]
    xml = dump()
    ns = nodes(xml)

    if cmd == "list":
        for n in ns:
            if n["label"] and (n["clickable"] or "Layout" not in n["cls"]):
                print("%-20s (%4d,%4d) %s" % (n["label"][:20], n["cx"], n["cy"],
                                              "可点" if n["clickable"] else ""))
    elif cmd == "text":
        for n in ns:
            if n["label"]:
                print(n["label"])
    elif cmd == "tap":
        want = a[1]
        # 优先精确匹配，其次才模糊：否则 "效果" 会匹配到含"效果"的说明文字
        exact = [n for n in ns if n["label"] == want]
        hit = exact or sorted([n for n in ns if want in n["label"]], key=lambda n: len(n["label"]))
        if not hit:
            print("没找到包含 %r 的元素" % want)
            return 1
        n = hit[0]
        print("tap %r -> (%d,%d) %s" % (n["label"], n["cx"], n["cy"],
                                        "精确" if exact else "模糊"))
        sh("shell", "input", "tap", str(n["cx"]), str(n["cy"]))
    elif cmd == "shot":
        out = a[1] if len(a) > 1 else "shot.png"
        sh("shell", "screencap", "-p", "/sdcard/_shot.png")
        sh("pull", "/sdcard/_shot.png", out)
        print("saved", out)
    else:
        print(__doc__)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

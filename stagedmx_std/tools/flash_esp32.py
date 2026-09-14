#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
StageDMX 一键烧录脚本（ESP32-S3）。

功能：
  1. 枚举本机串口，按“最可能是 ESP32”的顺序排序（ESP32-S3 原生 USB-Serial-JTAG >
     CP210x > CH34x > 其它），自动跳过蓝牙虚拟串口；
  2. 逐个用 esptool 探测，找到 ESP32-S3 就烧录 bootloader + 分区表 + 应用；
  3. 也可以用 -p COM13 指定端口，或用 --list 只看端口不烧录。

用法：
    python flash_esp32.py            # 自动探测并烧录
    python flash_esp32.py -p COM13   # 指定端口
    python flash_esp32.py --list     # 只列出串口
"""
import argparse
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))     # stagedmx_std/tools -> 仓库根

# 烧录地址（与 build/flasher_args.json 一致）
FLASH_FILES = [
    ("0x0", "bootloader.bin"),
    ("0x8000", "partition-table.bin"),
    ("0x10000", "stagedmx.bin"),
]
FLASH_MODE, FLASH_SIZE, FLASH_FREQ = "dio", "16MB", "80m"


def find_bin(name):
    """找固件 bin：在候选目录里取**修改时间最新**的那份。

    为什么不是"按固定优先级取第一个"：release_assets/ 放的是发布用副本，很容易比
    build/ 里的新构建产物更旧 —— 按固定优先级就会**默默烧进过期镜像**，症状是
    "代码明明改了却看不出效果"（本工具确实这样烧错过几次）。
    """
    cands = [
        os.path.join(HERE, name),
        os.path.join(REPO, "stagedmx_std", "build", name),
        os.path.join(REPO, "stagedmx_std", "build", "bootloader", name),
        os.path.join(REPO, "stagedmx_std", "build", "partition_table", name),
        os.path.join(REPO, "release_assets", name),
    ]
    existing = [c for c in cands if os.path.exists(c)]
    if not existing:
        return None
    return max(existing, key=os.path.getmtime)

# 按“像 ESP32 的程度”排序
PREFERRED = [
    ("303A", "1001", 0),   # ESP32-S3 原生 USB Serial/JTAG
    ("303A", None, 1),     # 其它 Espressif 原生 USB
    ("10C4", "EA60", 2),   # Silicon Labs CP210x
    ("1A86", "7523", 3),   # CH340
    ("1A86", "55D3", 4),   # CH343/CH9102
    ("0403", None, 5),     # FTDI
]
SKIP_HWID = ("BTHENUM", "ROOT\\")   # 蓝牙虚拟串口等


def list_ports():
    from serial.tools import list_ports as lp
    out = []
    for p in lp.comports():
        vid = "%04X" % p.vid if p.vid is not None else ""
        pid = "%04X" % p.pid if p.pid is not None else ""
        out.append(dict(port=p.device, desc=p.description or "", vid=vid, pid=pid,
                        hwid=p.hwid or ""))
    return out


def rank(port):
    for i, (vid, pid, score) in enumerate(PREFERRED):
        if port["vid"] == vid and (pid is None or port["pid"] == pid):
            return score
    return 90


def candidates(ports, only=None):
    res = []
    for p in ports:
        if any(k in p["hwid"].upper() for k in SKIP_HWID):
            continue
        if only and p["port"].upper() != only.upper():
            continue
        res.append(p)
    res.sort(key=lambda p: (rank(p), p["port"]))
    return res


def esptool(argv, timeout=180):
    cmd = [sys.executable, "-m", "esptool"] + argv
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.returncode, (r.stdout or "") + (r.stderr or "")
    except subprocess.TimeoutExpired:
        return 124, "timeout"


def probe(port):
    """返回 (是否 ESP32-S3, 描述文本, 原始输出)。"""
    rc, out = esptool(["--chip", "esp32s3", "--port", port, "--before", "default-reset",
                       "--after", "hard-reset", "flash-id"], timeout=60)
    # esptool 4.x: "Chip is ESP32-S3" / 5.x: "Chip type:  ESP32-S3 (QFN56)"
    ok = rc == 0 and ("ESP32-S3" in out) and ("Connected to ESP32-S3" in out or "Chip is ESP32-S3" in out
                                             or "Chip type" in out and "ESP32-S3" in out)
    info = ""
    for line in out.splitlines():
        if any(k in line for k in ("Chip is", "Chip type", "Crystal", "MAC:", "Manufacturer",
                                   "Detected flash size", "Features")):
            info += "    " + line.strip() + "\n"
    return ok, info, out


def flash(port):
    args = ["--chip", "esp32s3", "--port", port, "-b", "460800",
            "--before", "default-reset", "--after", "hard-reset",
            "write-flash",
            "--flash-mode", FLASH_MODE, "--flash-size", FLASH_SIZE,
            "--flash-freq", FLASH_FREQ]
    for addr, fn in FLASH_FILES:
        path = find_bin(fn)
        if not path:
            print("!! 缺少固件文件 %s —— 先执行 `idf.py build`（或从 release_assets 取）" % fn)
            return 1
        args += [addr, path]
    print(">> 烧录: %s" % " ".join(args[:8] + ["..."]))
    r = subprocess.run([sys.executable, "-m", "esptool"] + args)
    return r.returncode


def monitor(port, seconds=8, baud=115200, capture=False):
    """读一段串口日志。capture=True 时不打印、只返回文本（供验收分析）。"""
    try:
        import serial
    except ImportError:
        print("!! 需要 pyserial")
        return ""
    if not capture:
        print("\n=== 串口日志 %s @%d (%ds) ===" % (port, baud, seconds))
    import time
    # 刚烧完时 USB-Serial/JTAG 正在重新枚举，立刻打开会报
    # "ClearCommError failed (PermissionError(13))" —— 必须等一下并重试。
    ser = None
    for attempt in range(20):
        try:
            ser = serial.Serial(port, baud, timeout=0.3)
            break
        except Exception as e:
            if attempt == 0:
                print("  等待串口就绪（刚烧录完 USB 会重新枚举）...")
            time.sleep(0.5)
    if ser is None:
        print("!! 串口始终打不开 —— 确认控制台口没被别的程序占用")
        return ""
    try:
        with ser:
            ser.dtr = False
            ser.rts = False
            end = time.time() + seconds
            buf = b""
            while time.time() < end:
                data = ser.read(4096)
                if data:
                    buf += data
                    if not capture:
                        sys.stdout.write(data.decode("utf-8", "replace"))
                        sys.stdout.flush()
            if not buf and not capture:
                print("  （没有输出：可能板子没在跑，或控制台不在这个串口）")
            return buf.decode("utf-8", "replace")
    except Exception as e:
        print("  读取失败: %s" % e)
        return ""


# ---------------- v6 验收：烧录后按目标里的验收项自动核对 ----------------

def _intr_cores(txt):
    """解析 esp_intr_dump 输出 → {cpu: [中断源,...]}"""
    import re
    cores, cur = {}, None
    for line in txt.splitlines():
        m = re.match(r"CPU (\d) interrupt status", line)
        if m:
            cur = int(m.group(1)); cores.setdefault(cur, [])
            continue
        if cur is not None and "Used:" in line:
            cores[cur].append(line.split("Used:")[1].strip())
    return cores


def verify(port, seconds=20):
    txt = monitor(port, seconds, capture=True)
    if not txt:
        print("!! 没读到串口输出 —— 确认控制台在控制台串口（USB-Serial/JTAG）上")
        return 1

    import re
    results = []

    # 1) 两口驱动都在 core 1 安装（决定中断落在哪一核）
    results.append(("两个口驱动都在 CPU1 安装",
                    "drivers installed: 2/2 on CPU1" in txt))

    # 2) 中断表：UART1 + UART2 都在 CPU1，且都不在 CPU0
    cores = _intr_cores(txt)
    c0, c1 = cores.get(0, []), cores.get(1, [])
    results.append(("UART1 中断在 CPU1", "UART1" in c1))
    results.append(("UART2 中断在 CPU1", "UART2" in c1))
    results.append(("CPU0 上已无 UART1/UART2", ("UART1" not in c0) and ("UART2" not in c0)))

    # 3) 渲染管线起来
    results.append(("渲染管线已启动且为 1024 通道",
                    ("render pipeline started" in txt) and ("1024 channels" in txt)))

    # 4) 两口各自帧率 / 无失败
    fps = {}
    for m in re.finditer(r"U(\d) frm=(\d+) fps=(\d+) ok=(\d+) fail=(\d+)", txt):
        u, frm, f, ok, bad = (int(m.group(i)) for i in range(1, 6))
        fps[u] = (frm, f, ok, bad)
    for u in (1, 2):
        if u in fps:
            frm, f, ok, bad = fps[u]
            results.append(("U%d 帧率 ≥ 35fps（实测 %d）" % (u, f), f >= 35))
            results.append(("U%d 发送无失败（fail=%d）" % (u, bad), bad == 0))
            results.append(("U%d 最近一帧发送成功" % u, ok == 1))
        else:
            results.append(("U%d 有遥测输出" % u, False))

    # 5) 无异常
    results.append(("无 Guru Meditation / assert",
                    ("Guru Meditation" not in txt) and ("assert failed" not in txt)))

    print("\n================ v6 验收结果 ================")
    bad = 0
    for name, ok in results:
        print("  %s %s" % ("✅" if ok else "❌", name))
        if not ok:
            bad += 1
    print("============================================")
    print("  共 %d 项，通过 %d 项%s" % (len(results), len(results) - bad,
                                       "" if bad == 0 else "，**失败 %d 项**" % bad))
    if bad:
        print("\n---- 串口原始输出 ----")
        print(txt)
    return 0 if bad == 0 else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-p", "--port", help="指定串口，如 COM13")
    ap.add_argument("--list", action="store_true", help="只列出串口")
    ap.add_argument("--yes", action="store_true", help="自动选择第一个可用的 ESP32-S3")
    ap.add_argument("--monitor", type=int, default=0, metavar="SEC",
                    help="烧录后监听串口日志 SEC 秒（如 --monitor 8）")
    ap.add_argument("--check", type=int, default=0, metavar="SEC",
                    help="烧录后按 v6 验收项自动核对（建议 20，需等 5s 遥测间隔出数）")
    a = ap.parse_args()

    ports = list_ports()
    print("=== 本机串口 ===")
    if not ports:
        print("  （没有发现任何串口）")
    for p in candidates(ports, a.port):
        print("  %-6s VID:PID=%s:%s  %s" % (p["port"], p["vid"] or "----", p["pid"] or "----", p["desc"]))
    if a.list:
        return 0
    if not ports:
        print("\n没有可用串口：请插好 ESP32-S3、装好驱动，并确认没占用串口（U盘模式请先退出）。")
        return 2

    for p in candidates(ports, a.port):
        port = p["port"]
        print("\n=== 探测 %s (%s) ===" % (port, p["desc"]))
        ok, info, raw = probe(port)
        if ok:
            print("  找到 ESP32-S3：\n%s" % info.rstrip())
            print("\n>> 开始烧录到 %s ..." % port)
            rc = flash(port)
            print("\n" + ("✅ 烧录完成，设备已重启。" if rc == 0 else "❌ 烧录失败 (rc=%d)" % rc))
            if a.monitor > 0:
                monitor(port, a.monitor)
            if a.check > 0:
                return verify(port, a.check)
            return 0 if rc == 0 else 1
        else:
            tail = [l for l in raw.splitlines() if l.strip()][-1:] or [""]
            print("  不是可用目标：%s" % tail[0].strip())

    print("\n没有探测到 ESP32-S3。请确认：板子已插好并上电、驱动正常、串口没被别的程序占用"
          "（若之前进过 U 盘模式，先退出或断电重插）。")
    return 2


if __name__ == "__main__":
    sys.exit(main())

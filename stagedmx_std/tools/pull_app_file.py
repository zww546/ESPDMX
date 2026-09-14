"""从 App 私有目录拉取文件（避开 PowerShell 重定向对二进制/编码的破坏）。

    python stagedmx_std/tools/pull_app_file.py files/fixtures/omarte_ares_s4_20_channels.json out.json
    python stagedmx_std/tools/pull_app_file.py --list files/fixtures
"""
import subprocess
import sys

PKG = "com.example.stagedmx"


def main():
    a = sys.argv[1:]
    if not a:
        print(__doc__)
        return 1
    if a[0] == "--list":
        path = a[1] if len(a) > 1 else "files"
        r = subprocess.run(["adb", "exec-out", "run-as", PKG, "ls", path],
                           capture_output=True)
        sys.stdout.write(r.stdout.decode("utf-8", "replace"))
        return 0
    src = a[0]
    dst = a[1] if len(a) > 1 else "out.bin"
    r = subprocess.run(["adb", "exec-out", "run-as", PKG, "cat", src], capture_output=True)
    if r.returncode != 0 or not r.stdout:
        print("读取失败:", r.stderr.decode("utf-8", "replace")[:300])
        return 1
    with open(dst, "wb") as f:
        f.write(r.stdout)
    print("saved %d bytes -> %s" % (len(r.stdout), dst))
    return 0


if __name__ == "__main__":
    sys.exit(main())

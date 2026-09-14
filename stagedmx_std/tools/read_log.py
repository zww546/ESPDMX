import sys, time, serial

port = sys.argv[1] if len(sys.argv) > 1 else "COM13"
secs = int(sys.argv[2]) if len(sys.argv) > 2 else 22
out = sys.argv[3] if len(sys.argv) > 3 else r"E:\Desktop\ESPDMX_refactor\release_assets\_boot.log"

s = serial.Serial(port, 115200, timeout=0.3)   # 打开会让 ESP32 复位 → 正好抓到启动日志
s.dtr = False
s.rts = False
time.sleep(0.2)
s.reset_input_buffer()

t0 = time.time()
buf = b""
while time.time() - t0 < secs:
    d = s.read(8192)
    if d:
        buf += d
s.close()

with open(out, "wb") as f:
    f.write(buf)
print("captured %d bytes -> %s" % (len(buf), out))

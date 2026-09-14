#!/usr/bin/env bash
# 宿主端测试：把真实的 fx.c / program.c / dmx_state.c 编译进来跑算法与协议逻辑验证
# （不需要 ESP32 硬件）。
#
#   cd stagedmx_std && bash test_host/run.sh
#
# 依赖：gcc（WSL / Linux / macOS 均可）
set -e
cd "$(dirname "$0")/.."
TMP="${TMPDIR:-/tmp}"

build_and_run() {
    local src="$1" out="$TMP/$(basename "$1" .c)"
    gcc -Wall -O1 -Imain -Itest_host/stubs \
        "$src" main/fx.c main/fx_proto.c main/program.c main/dmx_state.c \
        test_host/stubs/stubs.c -o "$out"
    echo "================ $(basename "$src") ================"
    "$out"
    echo
}

# 纯逻辑测试（不依赖固件源码，单独编译）
plain_test() {
    local src="$1" out="$TMP/$(basename "$1" .c)"
    gcc -Wall -O1 "$src" -o "$out"
    echo "================ $(basename "$1") ================"
    "$out"
    echo
}

fail=0
build_and_run test_host/test_fx.c     || fail=1
build_and_run test_host/test_render.c || fail=1
build_and_run test_host/test_proto.c  || fail=1
plain_test    test_host/test_patch.c  || fail=1

exit $fail

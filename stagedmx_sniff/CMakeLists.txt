cmake_minimum_required(VERSION 3.16)
# DMX 分析仪：把第二块 ESP32-S3 当成总线监听器
#
# 复用主工程的 esp_dmx 组件（EXTRA_COMPONENT_DIRS 指过去），
# 不复制一份 —— 组件升级时两边自动一致。
set(EXTRA_COMPONENT_DIRS
    "${CMAKE_CURRENT_LIST_DIR}/../stagedmx_std/components"
)

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(stagedmx_sniff)

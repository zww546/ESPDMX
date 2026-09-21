cmake_minimum_required(VERSION 3.16)
# StageDMX fixture simulator: use a second ESP32-S3 as a DMX fixture on the bus.
#
# It reuses the esp_dmx component from the main project (via EXTRA_COMPONENT_DIRS)
# instead of copying it, so both projects stay in sync when the component is
# upgraded.
set(EXTRA_COMPONENT_DIRS
    "${CMAKE_CURRENT_LIST_DIR}/../stagedmx_std/components"
)

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(stagedmx_sniff)

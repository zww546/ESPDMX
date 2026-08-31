idf_component_register(
    SRCS "app_main.c"
         "dmx_state.c"
         "dmx.c"
         "ble_dmx.c"
         "program.c"
         "fx.c"
         "usb_msc.c"
         "file_xfer.c"
    INCLUDE_DIRS "."
    REQUIRES
        driver
        esp_timer
        nvs_flash
        bt
        esp_dmx
        esp_tinyusb
        fatfs
        wear_levelling
        esp_partition
)

idf_component_register(
    SRCS "bus_test.c"
    INCLUDE_DIRS "."
    REQUIRES driver esp_dmx esp_timer nvs_flash
)


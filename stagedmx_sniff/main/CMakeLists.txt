idf_component_register(
    SRCS "sniff.c"
    INCLUDE_DIRS "."
    REQUIRES driver esp_dmx esp_timer
)

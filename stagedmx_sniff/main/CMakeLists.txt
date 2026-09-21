idf_component_register(
    SRCS "fixture_sim.c"
    INCLUDE_DIRS "."
    REQUIRES driver esp_dmx esp_timer nvs_flash
)

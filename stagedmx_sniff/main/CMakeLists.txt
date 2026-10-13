idf_component_register(
    SRCS "fixture_sim.c" "sim_rgb.c"
    INCLUDE_DIRS "."
    REQUIRES driver esp_dmx esp_timer nvs_flash
)

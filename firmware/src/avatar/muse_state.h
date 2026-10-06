/*
 * Minimal stand-in for the ESP32 SDK's muse_state.h: only the avatar modes
 * that muse_pixel.c needs.
 */
#pragma once

typedef enum {
    MUSE_MODE_BOOT = 0,
    MUSE_MODE_IDLE,
    MUSE_MODE_LISTENING,
    MUSE_MODE_THINKING,
    MUSE_MODE_SPEAKING,
    MUSE_MODE_ERROR,
    MUSE_MODE_OFF,
    MUSE_MODE_COUNT,
} muse_mode_t;

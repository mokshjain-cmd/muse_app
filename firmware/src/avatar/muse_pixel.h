/*
 * Interface of the Muse pixel avatar renderer (muse-gadget-sdk/esp32,
 * components/muse/muse_pixel.h): a 64x64 procedural pixel-art avatar with an
 * animation per mode, scaled into an RGB565 buffer.
 */
#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "muse_state.h"

#define MUSE_PX_W 64
#define MUSE_PX_H 64

typedef struct {
    muse_mode_t mode;
    float t;         /* seconds since boot */
    float mode_t;    /* seconds in current mode */
    float level;     /* 0..1 live audio level */
    float happy;     /* 0..1 pet reaction */
} muse_pose_t;

uint32_t muse_pixel_accent(muse_mode_t mode);
void muse_pixel_render(const muse_pose_t *pose);
void muse_pixel_set_size(int px);
void muse_pixel_scale(uint16_t *dst, int stride_px, int x0, int x1, int y0, int y1);

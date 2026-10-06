/*
 * Phone Bridge - shared declarations.
 *
 * The board is a BLE peripheral with two GATT services:
 *  - Bridge service (Nordic UART UUIDs): our phone app writes newline-terminated
 *    JSON lines to RX and gets lines back on TX (notify).
 *  - Muse setup service: the Muse app pairs with it. The board does no crypto;
 *    it relays the raw packets to our phone app ("mrx"/"mtx" lines), which runs
 *    the Muse Gadget pairing protocol and then talks to Muse on the board's behalf.
 */
#ifndef APP_H
#define APP_H

#include <rtthread.h>
#include <stdint.h>
#include <stdbool.h>

#define APP_FW_VERSION      "0.2.0"
#define APP_LINE_MAX        1024
#define SETUP_PACKET_MAX    512

/* Events posted to the main (LVGL) thread mailbox. */
enum app_event
{
    APP_EVT_BLE_POWER_ON = 1,
    APP_EVT_LINK_UP,        /* phone app connected and subscribed to TX */
    APP_EVT_LINK_DOWN,      /* phone app gone */
    APP_EVT_SETUP_UP,       /* Muse app subscribed to the setup service */
    APP_EVT_SETUP_DOWN,     /* Muse app gone */
    APP_EVT_KEY1,           /* KEY1 click */
    APP_EVT_KEY2,           /* KEY2 click */
    APP_EVT_TALK_DOWN,      /* push-to-talk pressed (KEY1 held or screen button) */
    APP_EVT_TALK_UP,        /* push-to-talk released */
    APP_EVT_TALK_TIMEOUT,   /* 15 s limit reached */
    APP_EVT_SPEAKER_READY,  /* enough audio buffered to start playing */
    APP_EVT_SPEAKER_DONE,   /* played everything after audio_end */
    APP_EVT_READVERTISE,    /* a device connected; keep advertising for the other one */
};

extern rt_mailbox_t g_app_mb;

/* ble_link.c */
void ble_link_start(void);                         /* main thread, after BLE power on */
bool ble_link_is_ready(void);
bool ble_link_pop_line(char *buf, rt_size_t size); /* non-blocking */
bool ble_link_send_line(const char *line);         /* appends '\n', splits by MTU */
const char *ble_link_name(void);                   /* "MuseGadgetXXXXXX" */
const char *ble_link_addr(void);                   /* "aa:bb:cc:dd:ee:ff" */
void ble_link_forward_setup_packets(void);         /* Muse app -> phone, main thread */
bool ble_link_setup_send_b64(const char *b64);     /* phone -> Muse app */
bool ble_link_pairing_adv(bool on);                /* advertise for the Muse app while connected */
int ble_link_audio_payload(void);                  /* bytes per audio packet */
bool ble_link_audio_send(const uint8_t *data, uint16_t len);

/* audio.c */
void audio_init(void);
bool audio_mic_start(void);
void audio_mic_stop(void);
bool audio_mic_active(void);
void audio_speaker_begin(void);
void audio_speaker_end(void);
void audio_speaker_stop(void);
void audio_speaker_ready(void);
void audio_speaker_feed(const uint8_t *pkt, uint16_t len);   /* BLE task */
uint16_t audio_speaker_level(void);
void audio_set_volume(int level);                  /* 0..15 */

/* ui.c */
void ui_create(void);
void ui_set_link(bool connected);
void ui_handle_msg(const char *type, void *json);  /* json is a cJSON* */
bool ui_confirm_key(int key);                      /* true if a Yes/No question consumed it */
void ui_set_avatar(const char *state);             /* idle, listening, thinking, speaking, error */
void ui_tick(void);                                /* call from LVGL loop */

/* base64 helpers (ble_link.c) */
rt_size_t b64_encode(const uint8_t *in, rt_size_t len, char *out, rt_size_t out_size);
int b64_decode(const char *in, uint8_t *out, rt_size_t out_size);

#endif

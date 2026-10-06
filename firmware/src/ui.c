/*
 * Phone Bridge - LVGL UI. Only touch LVGL from the main thread.
 *
 * Messages from the phone app (one JSON object per line):
 *   {"t":"time","epoch":1759650000}                 local time, seconds (tz applied)
 *   {"t":"weather","temp":"24°C","desc":"Cloudy","place":"Pune"}
 *   {"t":"avatar","state":"idle|listening|thinking|speaking|error"}
 *   {"t":"caption","title":"Muse","text":"...","append":false}   live reply text
 *   {"t":"msg","title":"Muse","body":"...","style":"caption|card|alert"}
 *   {"t":"notify","text":"Package delivered"}       banner + LED for 5 s
 *   {"t":"confirm","id":"a1","question":"Turn off the TV?","title":"Muse asks","options":["Allow","Always","Deny"]}
 *        options is optional (default No/Yes, answers "yes"/"no"); with options the answer is the index "0".."2".
 *   {"t":"popup","title":"Quill","text":"...","timeout_s":30}      a message from Muse, OK to close
 *   {"t":"confirm_cancel","id":"a1"}
 *   {"t":"step","id":"s1","text":"Sending email to Cypher","state":"run|ok|fail"}
 *   {"t":"steps_clear"}
 *   {"t":"muse","state":"connected|connecting|pairing|not_paired|offline"}
 *
 * Messages to the phone app (sent from here or main.c):
 *   {"t":"talk","on":true|false}   (KEY1 held = talk, KEY2 = stop; sent from main.c)
 *   {"t":"confirm_answer","id":"a1","answer":"yes|no"}
 */
#include <rtthread.h>
#include <rtdevice.h>
#include <board.h>
#include <string.h>
#include <stdio.h>
#include "lvgl.h"
#include "cJSON.h"
#include "app.h"
#ifdef HAVE_MUSE_PIXEL
#include "muse_pixel.h"
#endif

#define LOG_TAG "ui"
#include "log.h"

#define BANNER_MS    5000
#define CAPTION_MAX  1200
#define STEPS_MAX    4
#define AVATAR_PX    240

#define COLOR_BG      lv_color_hex(0x0B0D12)
#define COLOR_CARD    lv_color_hex(0x171A22)
#define COLOR_TEXT    lv_color_hex(0xF2F4F8)
#define COLOR_MUTED   lv_color_hex(0x8A93A6)
#define COLOR_ACCENT  lv_color_hex(0x4C8DFF)
#define COLOR_MUSE    lv_color_hex(0xA06BFF)
#define COLOR_OK      lv_color_hex(0x2ECC71)
#define COLOR_WARN    lv_color_hex(0xF5A623)
#define COLOR_OFF     lv_color_hex(0xE74C3C)

enum avatar_state { AV_IDLE, AV_LISTENING, AV_THINKING, AV_SPEAKING, AV_ERROR };

static lv_obj_t *s_dot, *s_link_lbl, *s_muse_dot, *s_muse_lbl;
static lv_obj_t *s_time_lbl, *s_weather_lbl;
static lv_obj_t *s_face, *s_ring, *s_eye[2], *s_mouth, *s_dots;
static lv_obj_t *s_cap_card, *s_cap_title, *s_cap_body, *s_steps_lbl;
static lv_obj_t *s_page_avatar, *s_page_text, *s_preview, *s_hint;
static lv_obj_t *s_banner, *s_banner_lbl;
static lv_obj_t *s_confirm, *s_confirm_q, *s_confirm_title, *s_confirm_row;
static lv_obj_t *s_popup, *s_popup_title, *s_popup_body;
static rt_tick_t s_popup_until;
static int s_confirm_nopts;      /* 0 = classic Yes/No */
static char s_confirm_id[40];
static char s_caption[CAPTION_MAX];
static rt_tick_t s_banner_until;
static enum avatar_state s_av = AV_IDLE;
static rt_tick_t s_av_since;

/* Steps Muse is taking for the current request (shown under the caption). */
typedef struct
{
    char id[24];
    char text[72];
    char state;      /* 'r' running, 'o' ok, 'f' failed */
} step_t;
static step_t s_steps[STEPS_MAX];
static int s_nsteps;

#ifdef HAVE_MUSE_PIXEL
static lv_obj_t *s_canvas;
static lv_color_t *s_canvas_buf;
static float s_happy;
#endif

static int64_t  s_epoch_base;   /* local epoch seconds at sync */
static rt_tick_t s_tick_base;
static bool     s_time_valid;
static int32_t  s_last_shown = -1;

/* One period of a sine, scaled to +-100, 16 steps. */
static const int8_t SINE16[16] = {0, 38, 71, 92, 100, 92, 71, 38, 0, -38, -71, -92, -100, -92, -71, -38};

/* ---------- helpers ---------- */

static lv_obj_t *make_card(lv_obj_t *parent)
{
    lv_obj_t *card = lv_obj_create(parent);
    lv_obj_set_width(card, lv_pct(100));
    lv_obj_set_height(card, LV_SIZE_CONTENT);
    lv_obj_set_style_bg_color(card, COLOR_CARD, 0);
    lv_obj_set_style_border_width(card, 0, 0);
    lv_obj_set_style_radius(card, 18, 0);
    lv_obj_set_style_pad_all(card, 12, 0);
    lv_obj_set_style_pad_row(card, 4, 0);
    lv_obj_set_flex_flow(card, LV_FLEX_FLOW_COLUMN);
    lv_obj_clear_flag(card, LV_OBJ_FLAG_SCROLLABLE);
    return card;
}

static lv_obj_t *make_label(lv_obj_t *parent, const lv_font_t *font, lv_color_t color, const char *text)
{
    lv_obj_t *l = lv_label_create(parent);
    lv_obj_set_style_text_font(l, font, 0);
    lv_obj_set_style_text_color(l, color, 0);
    lv_label_set_text(l, text);
    return l;
}

static lv_obj_t *make_box(lv_obj_t *parent, int w, int h, lv_color_t color, int radius)
{
    lv_obj_t *o = lv_obj_create(parent);
    lv_obj_remove_style_all(o);
    lv_obj_set_size(o, w, h);
    lv_obj_set_style_radius(o, radius, 0);
    lv_obj_set_style_bg_opa(o, LV_OPA_COVER, 0);
    lv_obj_set_style_bg_color(o, color, 0);
    lv_obj_clear_flag(o, LV_OBJ_FLAG_CLICKABLE);
    return o;
}

static lv_obj_t *make_button(lv_obj_t *parent, const char *text, lv_color_t color, lv_event_cb_t cb, void *user)
{
    lv_obj_t *b = lv_btn_create(parent);
    lv_obj_set_height(b, lv_pct(100));
    lv_obj_set_flex_grow(b, 1);
    lv_obj_set_style_radius(b, 14, 0);
    lv_obj_set_style_bg_color(b, color, 0);
    lv_obj_add_event_cb(b, cb, LV_EVENT_ALL, user);
    lv_obj_t *l = make_label(b, &lv_font_montserrat_16, lv_color_white(), text);
    lv_obj_center(l);
    return l;
}

static void led(bool on)
{
#ifdef BSP_USING_LED1
#ifdef BSP_LED1_ACTIVE_HIGH
    rt_pin_write(BSP_LED1_PIN, on ? PIN_HIGH : PIN_LOW);
#else
    rt_pin_write(BSP_LED1_PIN, on ? PIN_LOW : PIN_HIGH);
#endif
#endif
}

/* Days since 1970-01-01 -> y/m/d (Howard Hinnant's civil_from_days). */
static void civil_from_days(int64_t z, int *y, int *m, int *d)
{
    z += 719468;
    int64_t era = (z >= 0 ? z : z - 146096) / 146097;
    unsigned doe = (unsigned)(z - era * 146097);
    unsigned yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    unsigned mp = (5 * doy + 2) / 153;
    *d = (int)(doy - (153 * mp + 2) / 5 + 1);
    *m = (int)(mp < 10 ? mp + 3 : mp - 9);
    *y = (int)(yoe + era * 400 + (*m <= 2));
}

/* ---------- confirm dialog ---------- */

static void confirm_answer(const char *answer)
{
    if (!s_confirm_id[0])
        return;
    char line[96];
    rt_snprintf(line, sizeof(line), "{\"t\":\"confirm_answer\",\"id\":\"%s\",\"answer\":\"%s\"}", s_confirm_id, answer);
    ble_link_send_line(line);
    s_confirm_id[0] = '\0';
    lv_obj_add_flag(s_confirm, LV_OBJ_FLAG_HIDDEN);
    lv_obj_scroll_to_y(lv_scr_act(), 0, LV_ANIM_ON);   /* back to the avatar */
}

/* user data: 0 = No, 1 = Yes for the classic dialog; option index + 10 otherwise */
static void confirm_cb(lv_event_t *e)
{
    if (lv_event_get_code(e) != LV_EVENT_CLICKED)
        return;
    int v = (int)(intptr_t)lv_event_get_user_data(e);
    if (v >= 10)
    {
        char idx[4];
        rt_snprintf(idx, sizeof(idx), "%d", v - 10);
        confirm_answer(idx);
    }
    else
    {
        confirm_answer(v ? "yes" : "no");
    }
}

static void popup_close(void)
{
    s_popup_until = 0;
    lv_obj_add_flag(s_popup, LV_OBJ_FLAG_HIDDEN);
}

static void popup_cb(lv_event_t *e)
{
    if (lv_event_get_code(e) == LV_EVENT_CLICKED)
        popup_close();
}

bool ui_confirm_key(int key)
{
    if (!s_confirm_id[0])
    {
        if (s_popup_until)      /* a key closes an open popup */
        {
            popup_close();
            return true;
        }
        return false;
    }
    if (s_confirm_nopts)        /* KEY1 = first option (Allow), KEY2 = last (Deny) */
        confirm_answer(key == 1 ? "0" : (s_confirm_nopts == 3 ? "2" : "1"));
    else
        confirm_answer(key == 1 ? "yes" : "no");   /* KEY1 = Yes, KEY2 = No */
    return true;
}

/* Rebuilds the dialog's buttons: either No/Yes or up to three custom options. */
static void confirm_buttons(cJSON *options)
{
    lv_obj_clean(s_confirm_row);
    s_confirm_nopts = 0;
    if (cJSON_IsArray(options) && cJSON_GetArraySize(options) > 0)
    {
        int n = cJSON_GetArraySize(options);
        if (n > 3) n = 3;
        for (int i = 0; i < n; i++)
        {
            cJSON *it = cJSON_GetArrayItem(options, i);
            const char *label = cJSON_IsString(it) ? it->valuestring : "?";
            lv_color_t c = (i == n - 1 && n > 1) ? COLOR_OFF : (i == 0 ? COLOR_OK : COLOR_ACCENT);
            lv_obj_t *l = make_button(s_confirm_row, label, c, confirm_cb, (void *)(intptr_t)(10 + i));
            lv_obj_set_style_text_font(l, &lv_font_montserrat_16, 0);
        }
        s_confirm_nopts = n;
    }
    else
    {
        make_button(s_confirm_row, "No", COLOR_OFF, confirm_cb, NULL);
        make_button(s_confirm_row, "Yes", COLOR_OK, confirm_cb, (void *)1);
    }
}

/* ---------- avatar ---------- */

void ui_set_avatar(const char *state)
{
    enum avatar_state st = AV_IDLE;
    if (strcmp(state, "listening") == 0) st = AV_LISTENING;
    else if (strcmp(state, "thinking") == 0) st = AV_THINKING;
    else if (strcmp(state, "speaking") == 0) st = AV_SPEAKING;
    else if (strcmp(state, "error") == 0) st = AV_ERROR;
    if (st == s_av)
        return;
    if (st == AV_LISTENING)
        lv_obj_scroll_to_y(lv_scr_act(), 0, LV_ANIM_ON);   /* talking: show the avatar */
    s_av = st;
    s_av_since = rt_tick_get();
    lv_color_t eye = st == AV_ERROR ? COLOR_OFF : COLOR_TEXT;
    lv_obj_set_style_bg_color(s_eye[0], eye, 0);
    lv_obj_set_style_bg_color(s_eye[1], eye, 0);
    lv_obj_set_style_bg_color(s_mouth, st == AV_LISTENING ? COLOR_ACCENT : COLOR_MUSE, 0);
#ifdef HAVE_MUSE_PIXEL
    if (s_canvas)
        return;
#endif
    if (st == AV_LISTENING) lv_obj_clear_flag(s_ring, LV_OBJ_FLAG_HIDDEN);
    else lv_obj_add_flag(s_ring, LV_OBJ_FLAG_HIDDEN);
    if (st == AV_THINKING) lv_obj_clear_flag(s_dots, LV_OBJ_FLAG_HIDDEN);
    else lv_obj_add_flag(s_dots, LV_OBJ_FLAG_HIDDEN);
}

#ifdef HAVE_MUSE_PIXEL
static const muse_mode_t PX_MODE[] = {MUSE_MODE_IDLE, MUSE_MODE_LISTENING, MUSE_MODE_THINKING, MUSE_MODE_SPEAKING, MUSE_MODE_ERROR};

static void avatar_pet_cb(lv_event_t *e)
{
    if (lv_event_get_code(e) == LV_EVENT_CLICKED)
        s_happy = 1.0f;
}

static void pixel_animate(void)
{
    static rt_tick_t last;
    rt_tick_t now = rt_tick_get();
    if (now - last < rt_tick_from_millisecond(50))   /* ~20 fps */
        return;
    float dt = (float)(now - last) / RT_TICK_PER_SECOND;
    last = now;
    s_happy = s_happy > dt * 0.5f ? s_happy - dt * 0.5f : 0.0f;

    muse_pose_t pose;
    pose.mode = PX_MODE[s_av];
    pose.t = (float)now / RT_TICK_PER_SECOND;
    pose.mode_t = (float)(now - s_av_since) / RT_TICK_PER_SECOND;
    pose.level = s_av == AV_SPEAKING ? (float)audio_speaker_level() / 12000.0f : 0.0f;
    if (pose.level > 1.0f) pose.level = 1.0f;
    pose.happy = s_happy;
    muse_pixel_render(&pose);
    muse_pixel_scale((uint16_t *)s_canvas_buf, AVATAR_PX, 0, AVATAR_PX - 1, 0, AVATAR_PX - 1);
    lv_obj_invalidate(s_canvas);
}
#endif

static void avatar_animate(void)
{
#ifdef HAVE_MUSE_PIXEL
    if (s_canvas)
    {
        pixel_animate();
        return;
    }
#endif
    uint32_t ms = (rt_tick_get() - s_av_since) * 1000 / RT_TICK_PER_SECOND;
    int eye_h = 52, eye_dx = 0, eye_dy = 0, mouth_w = 54, mouth_h = 10;

    switch (s_av)
    {
    case AV_IDLE:
        if (ms % 4200 < 140) eye_h = 6;                       /* blink */
        break;
    case AV_LISTENING:
        eye_h = 58;
        mouth_w = 22; mouth_h = 22;
        lv_obj_set_style_border_opa(s_ring, (lv_opa_t)(120 + SINE16[(ms / 60) % 16]), 0);
        break;
    case AV_THINKING:
        eye_h = 40;
        eye_dx = SINE16[(ms / 90) % 16] / 8;
        eye_dy = -8;
        mouth_w = 28; mouth_h = 6;
        {
            static const char *dots[] = {".", "..", "..."};
            lv_label_set_text(s_dots, dots[(ms / 400) % 3]);
        }
        break;
    case AV_SPEAKING:
    {
        uint16_t lvl = audio_speaker_level();
        mouth_w = 60;
        mouth_h = lvl ? 8 + lvl / 900 : 10 + (SINE16[(ms / 70) % 16] + 100) / 8;
        if (mouth_h > 40) mouth_h = 40;
        if (ms % 4200 < 140) eye_h = 6;
        break;
    }
    case AV_ERROR:
        eye_h = 10;
        mouth_w = 40; mouth_h = 6;
        break;
    }
    for (int i = 0; i < 2; i++)
    {
        lv_obj_set_height(s_eye[i], eye_h);
        lv_obj_align(s_eye[i], LV_ALIGN_CENTER, (i ? 46 : -46) + eye_dx, -18 + eye_dy);
    }
    lv_obj_set_size(s_mouth, mouth_w, mouth_h);
    lv_obj_align(s_mouth, LV_ALIGN_CENTER, 0, 46);
}

/* ---------- build ---------- */

static lv_obj_t *make_page(lv_obj_t *parent)
{
    lv_obj_t *pg = lv_obj_create(parent);
    lv_obj_remove_style_all(pg);
    lv_obj_set_size(pg, lv_pct(100), lv_pct(100));
    lv_obj_set_style_pad_all(pg, 16, 0);
    lv_obj_set_style_pad_row(pg, 10, 0);
    lv_obj_set_flex_flow(pg, LV_FLEX_FLOW_COLUMN);
    return pg;
}

/*
 * Layout: the screen scrolls vertically and snaps between two full-screen pages.
 *   1. avatar page: status row, large avatar, a one-line preview of the reply
 *   2. text page (scrolls on its own): reply title, steps, full text, decision card
 * Banners and popups live on the top layer, above everything.
 */
void ui_create(void)
{
#ifdef BSP_USING_LED1
    rt_pin_mode(BSP_LED1_PIN, PIN_MODE_OUTPUT);
    led(false);
#endif
    lv_obj_t *scr = lv_scr_act();
    lv_obj_set_style_bg_color(scr, COLOR_BG, 0);
    lv_obj_set_style_pad_all(scr, 0, 0);
    lv_obj_set_style_pad_row(scr, 0, 0);
    lv_obj_set_flex_flow(scr, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_scroll_dir(scr, LV_DIR_VER);
    lv_obj_set_scroll_snap_y(scr, LV_SCROLL_SNAP_START);
    lv_obj_set_scrollbar_mode(scr, LV_SCROLLBAR_MODE_OFF);

    /* ---- page 1: avatar */
    s_page_avatar = make_page(scr);
    lv_obj_clear_flag(s_page_avatar, LV_OBJ_FLAG_SCROLLABLE);

    lv_obj_t *row = lv_obj_create(s_page_avatar);
    lv_obj_remove_style_all(row);
    lv_obj_set_size(row, lv_pct(100), LV_SIZE_CONTENT);
    lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(row, LV_FLEX_ALIGN_START, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_obj_set_style_pad_column(row, 6, 0);
    s_dot = make_box(row, 10, 10, COLOR_OFF, LV_RADIUS_CIRCLE);
    s_link_lbl = make_label(row, &lv_font_montserrat_16, COLOR_MUTED, "");
    lv_obj_t *spacer = make_box(row, 4, 1, COLOR_BG, 0);
    lv_obj_set_style_bg_opa(spacer, LV_OPA_TRANSP, 0);
    s_muse_dot = make_box(row, 10, 10, COLOR_MUTED, LV_RADIUS_CIRCLE);
    s_muse_lbl = make_label(row, &lv_font_montserrat_16, COLOR_MUTED, "Muse");
    lv_obj_t *grow = make_box(row, 1, 1, COLOR_BG, 0);
    lv_obj_set_style_bg_opa(grow, LV_OPA_TRANSP, 0);
    lv_obj_set_flex_grow(grow, 1);
    s_time_lbl = make_label(row, &lv_font_montserrat_20, COLOR_TEXT, "--:--");

    s_face = lv_obj_create(s_page_avatar);
    lv_obj_remove_style_all(s_face);
    lv_obj_set_width(s_face, lv_pct(100));
    lv_obj_set_flex_grow(s_face, 1);
    lv_obj_clear_flag(s_face, LV_OBJ_FLAG_SCROLLABLE);
    s_ring = lv_obj_create(s_face);
    lv_obj_remove_style_all(s_ring);
    lv_obj_set_size(s_ring, 220, 180);
    lv_obj_center(s_ring);
    lv_obj_set_style_radius(s_ring, 90, 0);
    lv_obj_set_style_border_width(s_ring, 4, 0);
    lv_obj_set_style_border_color(s_ring, COLOR_ACCENT, 0);
    lv_obj_add_flag(s_ring, LV_OBJ_FLAG_HIDDEN);
#ifdef HAVE_MUSE_PIXEL
    s_canvas_buf = rt_malloc(AVATAR_PX * AVATAR_PX * sizeof(lv_color_t));
    if (s_canvas_buf)
    {
        muse_pixel_set_size(AVATAR_PX);
        s_canvas = lv_canvas_create(s_face);
        lv_canvas_set_buffer(s_canvas, s_canvas_buf, AVATAR_PX, AVATAR_PX, LV_IMG_CF_TRUE_COLOR);
        lv_obj_center(s_canvas);
        lv_obj_add_flag(s_canvas, LV_OBJ_FLAG_CLICKABLE);
        lv_obj_add_event_cb(s_canvas, avatar_pet_cb, LV_EVENT_CLICKED, NULL);
    }
#endif
    s_eye[0] = make_box(s_face, 36, 52, COLOR_TEXT, 18);
    s_eye[1] = make_box(s_face, 36, 52, COLOR_TEXT, 18);
    s_mouth = make_box(s_face, 54, 10, COLOR_MUSE, 8);
    s_dots = make_label(s_face, &lv_font_montserrat_28, COLOR_MUSE, "...");
    lv_obj_align(s_dots, LV_ALIGN_TOP_RIGHT, -30, 0);
    lv_obj_add_flag(s_dots, LV_OBJ_FLAG_HIDDEN);
#ifdef HAVE_MUSE_PIXEL
    if (s_canvas)   /* the pixel avatar replaces the drawn face */
    {
        lv_obj_add_flag(s_eye[0], LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(s_eye[1], LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(s_mouth, LV_OBJ_FLAG_HIDDEN);
        lv_obj_add_flag(s_ring, LV_OBJ_FLAG_HIDDEN);
    }
#endif
    avatar_animate();

    s_preview = make_label(s_page_avatar, &lv_font_montserrat_20, COLOR_TEXT, "Hold KEY1 and speak");
    lv_obj_set_width(s_preview, lv_pct(100));
    lv_label_set_long_mode(s_preview, LV_LABEL_LONG_DOT);
    lv_obj_set_style_text_align(s_preview, LV_TEXT_ALIGN_CENTER, 0);
    s_hint = make_label(s_page_avatar, &lv_font_montserrat_16, COLOR_MUTED, LV_SYMBOL_DOWN "  swipe up for the full reply");
    lv_obj_set_width(s_hint, lv_pct(100));
    lv_obj_set_style_text_align(s_hint, LV_TEXT_ALIGN_CENTER, 0);
    s_weather_lbl = make_label(s_page_avatar, &lv_font_montserrat_16, COLOR_MUTED, "");
    lv_obj_set_width(s_weather_lbl, lv_pct(100));
    lv_obj_set_style_text_align(s_weather_lbl, LV_TEXT_ALIGN_CENTER, 0);

    /* ---- page 2: reply text, steps and decisions (scrolls on its own) */
    s_page_text = make_page(scr);
    lv_obj_set_scroll_dir(s_page_text, LV_DIR_VER);
    lv_obj_add_flag(s_page_text, LV_OBJ_FLAG_SCROLL_CHAIN_VER);
    s_cap_card = s_page_text;
    s_cap_title = make_label(s_page_text, &lv_font_montserrat_20, COLOR_MUSE, "Muse");
    s_steps_lbl = make_label(s_page_text, &lv_font_montserrat_16, COLOR_MUTED, "");
    lv_obj_set_width(s_steps_lbl, lv_pct(100));
    lv_obj_add_flag(s_steps_lbl, LV_OBJ_FLAG_HIDDEN);
    s_cap_body = make_label(s_page_text, &lv_font_montserrat_20, COLOR_TEXT, "Hold KEY1 and speak, or type in the app.");
    lv_obj_set_width(s_cap_body, lv_pct(100));
    lv_label_set_long_mode(s_cap_body, LV_LABEL_LONG_WRAP);

    /* decision card: appears below the text when Muse asks something */
    s_confirm = make_card(s_page_text);
    lv_obj_set_style_border_color(s_confirm, COLOR_MUSE, 0);
    lv_obj_set_style_border_width(s_confirm, 2, 0);
    lv_obj_set_style_pad_all(s_confirm, 14, 0);
    lv_obj_set_style_pad_row(s_confirm, 12, 0);
    s_confirm_title = make_label(s_confirm, &lv_font_montserrat_16, COLOR_MUSE, "Muse asks");
    s_confirm_q = make_label(s_confirm, &lv_font_montserrat_20, COLOR_TEXT, "");
    lv_obj_set_width(s_confirm_q, lv_pct(100));
    lv_label_set_long_mode(s_confirm_q, LV_LABEL_LONG_WRAP);
    s_confirm_row = lv_obj_create(s_confirm);
    lv_obj_remove_style_all(s_confirm_row);
    lv_obj_set_size(s_confirm_row, lv_pct(100), 60);
    lv_obj_set_flex_flow(s_confirm_row, LV_FLEX_FLOW_ROW);
    lv_obj_set_style_pad_column(s_confirm_row, 10, 0);
    confirm_buttons(NULL);
    make_label(s_confirm, &lv_font_montserrat_16, COLOR_MUTED, "KEY1 = Yes   KEY2 = No");
    lv_obj_add_flag(s_confirm, LV_OBJ_FLAG_HIDDEN);

    /* notification banner (top layer, hidden) */
    s_banner = lv_obj_create(lv_layer_top());
    lv_obj_set_size(s_banner, lv_pct(92), LV_SIZE_CONTENT);
    lv_obj_align(s_banner, LV_ALIGN_TOP_MID, 0, 12);
    lv_obj_set_style_bg_color(s_banner, COLOR_MUSE, 0);
    lv_obj_set_style_border_width(s_banner, 0, 0);
    lv_obj_set_style_radius(s_banner, 16, 0);
    lv_obj_set_style_pad_all(s_banner, 14, 0);
    s_banner_lbl = make_label(s_banner, &lv_font_montserrat_20, lv_color_white(), "");
    lv_obj_set_width(s_banner_lbl, lv_pct(100));
    lv_label_set_long_mode(s_banner_lbl, LV_LABEL_LONG_WRAP);
    lv_obj_add_flag(s_banner, LV_OBJ_FLAG_HIDDEN);

    /* popup: any message from Muse the user didn't ask for here (top layer, hidden) */
    s_popup = lv_obj_create(lv_layer_top());
    lv_obj_set_size(s_popup, lv_pct(94), lv_pct(80));
    lv_obj_center(s_popup);
    lv_obj_set_style_bg_color(s_popup, COLOR_CARD, 0);
    lv_obj_set_style_border_color(s_popup, COLOR_MUSE, 0);
    lv_obj_set_style_border_width(s_popup, 3, 0);
    lv_obj_set_style_radius(s_popup, 22, 0);
    lv_obj_set_style_pad_all(s_popup, 18, 0);
    lv_obj_set_style_pad_row(s_popup, 10, 0);
    lv_obj_set_flex_flow(s_popup, LV_FLEX_FLOW_COLUMN);
    s_popup_title = make_label(s_popup, &lv_font_montserrat_20, COLOR_MUSE, "Muse");
    s_popup_body = make_label(s_popup, &lv_font_montserrat_20, COLOR_TEXT, "");
    lv_obj_set_width(s_popup_body, lv_pct(100));
    lv_label_set_long_mode(s_popup_body, LV_LABEL_LONG_WRAP);
    lv_obj_set_flex_grow(s_popup_body, 1);
    lv_obj_t *prow = lv_obj_create(s_popup);
    lv_obj_remove_style_all(prow);
    lv_obj_set_size(prow, lv_pct(100), 56);
    lv_obj_set_flex_flow(prow, LV_FLEX_FLOW_ROW);
    make_button(prow, "OK", COLOR_MUSE, popup_cb, NULL);
    lv_obj_add_flag(s_popup, LV_OBJ_FLAG_HIDDEN);
    lv_obj_move_foreground(s_banner);   /* banners stay visible above an open popup */

    ui_set_link(false);
}

void ui_set_link(bool connected)
{
    lv_obj_set_style_bg_color(s_dot, connected ? COLOR_OK : COLOR_OFF, 0);
    lv_label_set_text(s_link_lbl, connected ? "Phone" : "No phone");
    if (!connected)
    {
        lv_obj_set_style_bg_color(s_muse_dot, COLOR_MUTED, 0);
        ui_set_avatar("idle");
        if (s_confirm_id[0])
        {
            s_confirm_id[0] = '\0';
            lv_obj_add_flag(s_confirm, LV_OBJ_FLAG_HIDDEN);
        }
    }
}

/* ---------- incoming messages ---------- */

static const char *json_str(cJSON *obj, const char *key, const char *def)
{
    cJSON *it = cJSON_GetObjectItemCaseSensitive(obj, key);
    return cJSON_IsString(it) ? it->valuestring : def;
}

static void set_caption(const char *title, const char *text, bool append, lv_color_t color)
{
    if (title)
    {
        lv_obj_set_style_text_color(s_cap_title, color, 0);
        lv_label_set_text(s_cap_title, title);
    }
    if (!append)
        s_caption[0] = '\0';
    rt_size_t used = strlen(s_caption);
    rt_strncpy(s_caption + used, text, sizeof(s_caption) - used - 1);
    s_caption[sizeof(s_caption) - 1] = '\0';
    lv_label_set_text(s_cap_body, s_caption);
    if (!append)
        lv_obj_scroll_to_y(s_page_text, 0, LV_ANIM_OFF);
    /* one-line preview of the newest text on the avatar page */
    rt_size_t len = strlen(s_caption);
    const char *tail = len > 90 ? s_caption + len - 90 : s_caption;
    lv_label_set_text(s_preview, tail);
}

static void render_steps(void)
{
    static char buf[STEPS_MAX * 80];
    buf[0] = '\0';
    for (int i = 0; i < s_nsteps; i++)
    {
        const char *icon = s_steps[i].state == 'o' ? LV_SYMBOL_OK : (s_steps[i].state == 'f' ? LV_SYMBOL_CLOSE : LV_SYMBOL_REFRESH);
        rt_size_t n = strlen(buf);
        rt_snprintf(buf + n, sizeof(buf) - n, "%s%s %s", i ? "\n" : "", icon, s_steps[i].text);
    }
    lv_label_set_text(s_steps_lbl, buf);
    if (s_nsteps) lv_obj_clear_flag(s_steps_lbl, LV_OBJ_FLAG_HIDDEN);
    else lv_obj_add_flag(s_steps_lbl, LV_OBJ_FLAG_HIDDEN);
}

static void set_step(const char *id, const char *text, const char *state)
{
    int i;
    for (i = 0; i < s_nsteps && strcmp(s_steps[i].id, id) != 0; i++)
        ;
    if (i == s_nsteps)
    {
        if (s_nsteps == STEPS_MAX)     /* drop the oldest */
        {
            memmove(&s_steps[0], &s_steps[1], sizeof(step_t) * (STEPS_MAX - 1));
            i = STEPS_MAX - 1;
        }
        else
        {
            s_nsteps++;
        }
        rt_strncpy(s_steps[i].id, id, sizeof(s_steps[i].id) - 1);
        s_steps[i].id[sizeof(s_steps[i].id) - 1] = '\0';
    }
    if (text && text[0])
    {
        rt_strncpy(s_steps[i].text, text, sizeof(s_steps[i].text) - 1);
        s_steps[i].text[sizeof(s_steps[i].text) - 1] = '\0';
    }
    s_steps[i].state = strcmp(state, "ok") == 0 ? 'o' : (strcmp(state, "fail") == 0 ? 'f' : 'r');
    render_steps();
}

void ui_handle_msg(const char *t, void *json)
{
    cJSON *root = (cJSON *)json;

    if (strcmp(t, "time") == 0)
    {
        cJSON *ep = cJSON_GetObjectItemCaseSensitive(root, "epoch");
        if (cJSON_IsNumber(ep))
        {
            s_epoch_base = (int64_t)ep->valuedouble;
            s_tick_base = rt_tick_get();
            s_time_valid = true;
            s_last_shown = -1;
        }
    }
    else if (strcmp(t, "weather") == 0)
    {
        char buf[96];
        rt_snprintf(buf, sizeof(buf), "%s  %s, %s", json_str(root, "temp", "--"), json_str(root, "desc", ""), json_str(root, "place", ""));
        lv_label_set_text(s_weather_lbl, buf);
    }
    else if (strcmp(t, "avatar") == 0)
    {
        ui_set_avatar(json_str(root, "state", "idle"));
    }
    else if (strcmp(t, "caption") == 0)
    {
        cJSON *ap = cJSON_GetObjectItemCaseSensitive(root, "append");
        cJSON *title = cJSON_GetObjectItemCaseSensitive(root, "title");
        set_caption(cJSON_IsString(title) ? title->valuestring : NULL, json_str(root, "text", ""), cJSON_IsTrue(ap), COLOR_MUSE);
    }
    else if (strcmp(t, "step") == 0)
    {
        set_step(json_str(root, "id", "?"), json_str(root, "text", ""), json_str(root, "state", "run"));
    }
    else if (strcmp(t, "steps_clear") == 0)
    {
        s_nsteps = 0;
        render_steps();
    }
    else if (strcmp(t, "msg") == 0)
    {
        const char *style = json_str(root, "style", "caption");
        lv_color_t c = strcmp(style, "alert") == 0 ? COLOR_OFF : (strcmp(style, "card") == 0 ? COLOR_MUSE : COLOR_ACCENT);
        set_caption(json_str(root, "title", "Message"), json_str(root, "body", ""), false, c);
    }
    else if (strcmp(t, "notify") == 0)
    {
        lv_label_set_text(s_banner_lbl, json_str(root, "text", ""));
        lv_obj_clear_flag(s_banner, LV_OBJ_FLAG_HIDDEN);
        s_banner_until = rt_tick_get() + rt_tick_from_millisecond(BANNER_MS);
        led(true);
    }
    else if (strcmp(t, "confirm") == 0)
    {
        rt_strncpy(s_confirm_id, json_str(root, "id", "x"), sizeof(s_confirm_id) - 1);
        lv_label_set_text(s_confirm_q, json_str(root, "question", ""));
        lv_label_set_text(s_confirm_title, json_str(root, "title", "Muse asks"));
        confirm_buttons(cJSON_GetObjectItemCaseSensitive(root, "options"));
        lv_obj_clear_flag(s_confirm, LV_OBJ_FLAG_HIDDEN);
        lv_obj_update_layout(s_page_text);
        lv_obj_scroll_to_view_recursive(s_confirm, LV_ANIM_ON);   /* bring the decision on screen */
        lv_label_set_text(s_preview, "Muse needs your answer " LV_SYMBOL_DOWN);
    }
    else if (strcmp(t, "confirm_cancel") == 0)
    {
        if (strcmp(json_str(root, "id", ""), s_confirm_id) == 0)
        {
            s_confirm_id[0] = '\0';
            lv_obj_add_flag(s_confirm, LV_OBJ_FLAG_HIDDEN);
        }
    }
    else if (strcmp(t, "popup") == 0)
    {
        cJSON *ts = cJSON_GetObjectItemCaseSensitive(root, "timeout_s");
        int secs = cJSON_IsNumber(ts) ? (int)ts->valuedouble : 30;
        lv_label_set_text(s_popup_title, json_str(root, "title", "Muse"));
        lv_label_set_text(s_popup_body, json_str(root, "text", ""));
        lv_obj_clear_flag(s_popup, LV_OBJ_FLAG_HIDDEN);
        s_popup_until = rt_tick_get() + rt_tick_from_millisecond(secs * 1000);
    }
    else if (strcmp(t, "muse") == 0)
    {
        const char *state = json_str(root, "state", "");
        lv_color_t c = COLOR_MUTED;
        const char *text = "Muse";
        if (strcmp(state, "connected") == 0) { c = COLOR_OK; text = "Muse"; }
        else if (strcmp(state, "connecting") == 0) { c = COLOR_WARN; text = "Muse..."; }
        else if (strcmp(state, "pairing") == 0) { c = COLOR_MUSE; text = "Pairing"; }
        else if (strcmp(state, "not_paired") == 0) { c = COLOR_OFF; text = "Not paired"; }
        else if (strcmp(state, "offline") == 0) { c = COLOR_OFF; text = "Offline"; }
        lv_obj_set_style_bg_color(s_muse_dot, c, 0);
        lv_label_set_text(s_muse_lbl, text);
    }
    else if (strcmp(t, "status") == 0 || strcmp(t, "buttons") == 0)
    {
        /* no on-screen buttons or status line any more */
    }
    else
    {
        LOG_W("unknown type '%s'", t);
    }
}

/* ---------- animation, clock & timers ---------- */

void ui_tick(void)
{
    avatar_animate();

    if (s_popup_until && (rt_int32_t)(rt_tick_get() - s_popup_until) >= 0)
        popup_close();

    if (s_banner_until && (rt_int32_t)(rt_tick_get() - s_banner_until) >= 0)
    {
        s_banner_until = 0;
        lv_obj_add_flag(s_banner, LV_OBJ_FLAG_HIDDEN);
        led(false);
    }

    if (!s_time_valid)
        return;

    int64_t now = s_epoch_base + (int64_t)((rt_tick_get() - s_tick_base) / RT_TICK_PER_SECOND);
    int32_t minute = (int32_t)(now / 60);
    if (minute == s_last_shown)
        return;
    s_last_shown = minute;

    static const char *wd[] = {"Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed"}; /* 1970-01-01 was Thu */
    int64_t days = now / 86400;
    int secs = (int)(now % 86400);
    int y, m, d;
    civil_from_days(days, &y, &m, &d);
    lv_label_set_text_fmt(s_time_lbl, "%s %d  %02d:%02d", wd[days % 7], d, secs / 3600, (secs / 60) % 60);
}

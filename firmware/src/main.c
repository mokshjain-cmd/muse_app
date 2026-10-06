/*
 * Phone Bridge firmware for SF32LB52 DevKit.
 *
 * The board has no Wi-Fi. It shows a UI and takes input; the phone app does
 * all networking (including the Muse connection) and talks to the board over
 * BLE (see ble_link.c). The board also relays Muse app pairing traffic.
 */
#include <rtthread.h>
#include <rtdevice.h>
#include <board.h>
#include <string.h>

#include "bf0_ble_gap.h"
#include "bf0_sibles.h"
#include "littlevgl2rtt.h"
#include "lv_ex_data.h"
#include "lvgl.h"
#include "button.h"
#include "cJSON.h"
#include "app.h"

#define LOG_TAG "main"
#include "log.h"

rt_mailbox_t g_app_mb;

/* Button callbacks run outside the main thread: just post an event.
 * KEY1 is push-to-talk (hold); KEY2 is a click. */
static void key_handler(int32_t pin, button_action_t action)
{
#ifdef BSP_USING_KEY1
    if (pin == BSP_KEY1_PIN)
    {
        if (action == BUTTON_PRESSED)
            rt_mb_send(g_app_mb, APP_EVT_TALK_DOWN);
        else if (action == BUTTON_RELEASED)
            rt_mb_send(g_app_mb, APP_EVT_TALK_UP);
    }
#endif
#ifdef BSP_USING_KEY2
    if (pin == BSP_KEY2_PIN && action == BUTTON_CLICKED)
        rt_mb_send(g_app_mb, APP_EVT_KEY2);
#endif
}

static void key_init(int32_t pin, bool active_high)
{
    button_cfg_t cfg =
    {
        .pin = pin,
        .mode = active_high ? PIN_MODE_INPUT_PULLDOWN : PIN_MODE_INPUT_PULLUP,
        .active_state = active_high ? BUTTON_ACTIVE_HIGH : BUTTON_ACTIVE_LOW,
        .button_handler = key_handler,
    };
    int32_t id = button_init(&cfg);
    if (id >= 0)
        button_enable(id);
}

static void keys_init(void)
{
#ifdef BSP_USING_KEY1
#ifdef BSP_KEY1_ACTIVE_HIGH
    key_init(BSP_KEY1_PIN, true);
#else
    key_init(BSP_KEY1_PIN, false);
#endif
#endif
#ifdef BSP_USING_KEY2
#ifdef BSP_KEY2_ACTIVE_HIGH
    key_init(BSP_KEY2_PIN, true);
#else
    key_init(BSP_KEY2_PIN, false);
#endif
#endif
}

static void send_hello(void)
{
    char line[160];
    rt_snprintf(line, sizeof(line), "{\"t\":\"hello\",\"fw\":\"" APP_FW_VERSION "\",\"name\":\"%s\",\"addr\":\"%s\"}",
                ble_link_name(), ble_link_addr());
    ble_link_send_line(line);
}

static void handle_event(rt_uint32_t evt)
{
    switch (evt)
    {
    case APP_EVT_BLE_POWER_ON:
        ble_link_start();
        break;
    case APP_EVT_LINK_UP:
        ui_set_link(true);
        send_hello();
        break;
    case APP_EVT_LINK_DOWN:
        audio_mic_stop();
        audio_speaker_stop();
        ui_set_link(false);
        break;
    case APP_EVT_READVERTISE:
        rt_thread_mdelay(300);
        ble_link_pairing_adv(true);
        break;
    case APP_EVT_SETUP_UP:
        ble_link_send_line("{\"t\":\"mconn\"}");
        break;
    case APP_EVT_SETUP_DOWN:
        ble_link_send_line("{\"t\":\"mdisc\"}");
        break;
    case APP_EVT_TALK_DOWN:
        if (ui_confirm_key(1))
            break;
        if (!ble_link_is_ready())
            break;
        ble_link_send_line("{\"t\":\"talk\",\"on\":true}");
        if (audio_mic_start())
            ui_set_avatar("listening");
        else
            ble_link_send_line("{\"t\":\"talk\",\"on\":false}");
        break;
    case APP_EVT_TALK_UP:
    case APP_EVT_TALK_TIMEOUT:
        if (!audio_mic_active())
            break;
        audio_mic_stop();
        ble_link_send_line("{\"t\":\"talk\",\"on\":false}");
        ui_set_avatar("thinking");
        break;
    case APP_EVT_SPEAKER_READY:
        audio_speaker_ready();
        break;
    case APP_EVT_SPEAKER_DONE:
        audio_speaker_stop();
        ble_link_send_line("{\"t\":\"audio_done\"}");
        break;
    case APP_EVT_KEY1:
        break;
    case APP_EVT_KEY2:
        if (!ui_confirm_key(2))
            ble_link_send_line("{\"t\":\"key\",\"id\":2}");
        break;
    default:
        break;
    }
}

/* One JSON line from the phone app: relay/pairing control here, the rest to the UI. */
static void handle_line(const char *line)
{
    cJSON *root = cJSON_Parse(line);
    if (!root)
    {
        LOG_W("bad json: %.60s", line);
        return;
    }
    cJSON *t = cJSON_GetObjectItemCaseSensitive(root, "t");
    const char *type = cJSON_IsString(t) ? t->valuestring : "";

    if (strcmp(type, "mtx") == 0)
    {
        cJSON *d = cJSON_GetObjectItemCaseSensitive(root, "d");
        if (!cJSON_IsString(d) || !ble_link_setup_send_b64(d->valuestring))
            LOG_W("setup packet to Muse app not sent");
    }
    else if (strcmp(type, "pair") == 0)
    {
        bool on = cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(root, "on"));
        char reply[48];
        bool ok = ble_link_pairing_adv(on);
        rt_snprintf(reply, sizeof(reply), "{\"t\":\"pair_adv\",\"on\":%s,\"ok\":%s}", on ? "true" : "false", ok ? "true" : "false");
        ble_link_send_line(reply);
    }
    else if (strcmp(type, "audio_start") == 0)
    {
        audio_speaker_begin();
    }
    else if (strcmp(type, "audio_end") == 0)
    {
        audio_speaker_end();
    }
    else if (strcmp(type, "volume") == 0)
    {
        cJSON *lv = cJSON_GetObjectItemCaseSensitive(root, "level");
        if (cJSON_IsNumber(lv))
            audio_set_volume((int)lv->valuedouble);
    }
    else if (strcmp(type, "audio_stop") == 0)
    {
        audio_speaker_stop();
    }
    else
    {
        ui_handle_msg(type, root);
    }
    cJSON_Delete(root);
}

int main(void)
{
    static char line[APP_LINE_MAX];

    g_app_mb = rt_mb_create("app", 16, RT_IPC_FLAG_FIFO);

    if (littlevgl2rtt_init("lcd") != RT_EOK)
    {
        LOG_E("LCD init failed");
        return -RT_ERROR;
    }
    lv_ex_data_pool_init();   /* required by the SiFli LVGL extensions (as in the SDK's LVGL examples) */
    ui_create();
    audio_init();
    keys_init();
    sifli_ble_enable();

    while (1)
    {
        rt_uint32_t evt;
        while (rt_mb_recv(g_app_mb, &evt, 0) == RT_EOK)
            handle_event(evt);

        while (ble_link_pop_line(line, sizeof(line)))
            handle_line(line);

        ble_link_forward_setup_packets();

        ui_tick();

        rt_uint32_t ms = lv_task_handler();
        rt_thread_mdelay(ms > 20 ? 20 : ms);
    }
    return RT_EOK;
}

#ifdef SF32LB52X_58
/* Only used when simulating 52x on a 58x (scons --simu); copied from example/ble/peripheral. */
uint16_t g_em_offset[HAL_LCPU_CONFIG_EM_BUF_MAX_NUM] =
{
    0x178, 0x178, 0x740, 0x7A0, 0x810, 0x880, 0xA00, 0xBB0, 0xD48,
    0x133C, 0x13A4, 0x19BC, 0x21BC, 0x21BC, 0x21BC, 0x21BC, 0x21BC, 0x21BC,
    0x21BC, 0x21BC, 0x263C, 0x265C, 0x2734, 0x2784, 0x28D4, 0x28E8, 0x28FC,
    0x29EC, 0x29FC, 0x2BBC, 0x2BD8, 0x3BE8, 0x5804, 0x5804, 0x5804
};

void lcpu_rom_config(void)
{
    hal_lcpu_bluetooth_em_config_t em_offset;
    memcpy((void *)em_offset.em_buf, (void *)g_em_offset, HAL_LCPU_CONFIG_EM_BUF_MAX_NUM * 2);
    em_offset.is_valid = 1;
    HAL_LCPU_CONFIG_set(HAL_LCPU_CONFIG_BT_EM_BUF, &em_offset, sizeof(hal_lcpu_bluetooth_em_config_t));

    hal_lcpu_bluetooth_act_configt_t act_cfg;
    act_cfg.ble_max_act = 6;
    act_cfg.ble_max_iso = 0;
    act_cfg.ble_max_ral = 3;
    act_cfg.bt_max_acl = 7;
    act_cfg.bt_max_sco = 0;
    act_cfg.bit_valid = CO_BIT(0) | CO_BIT(1) | CO_BIT(2) | CO_BIT(3) | CO_BIT(4);
    HAL_LCPU_CONFIG_set(HAL_LCPU_CONFIG_BT_ACT_CFG, &act_cfg, sizeof(hal_lcpu_bluetooth_act_configt_t));
}
#endif

/*
 * Phone Bridge - BLE transport.
 *
 * Bridge service (Nordic UART Service UUIDs, so nRF Connect etc. can test it):
 *   Service 6E400001-B5A3-F393-E0A9-E50E24DCCA9E
 *   RX      6E400002-...  phone app -> board  (write / write without response)
 *   TX      6E400003-...  board -> phone app  (notify)
 *   Framing: UTF-8 JSON, one message per line, terminated by '\n'.
 *
 * Muse setup service (same UUIDs as Meta's muse-gadget-sdk):
 *   Service 7fdd3d1c-38ea-46cf-8b46-314ecf5f240c
 *   RX      4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01  Muse app -> board (write)
 *   TX      d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c  board -> Muse app (notify)
 *   Packets are relayed verbatim to/from the phone app as base64:
 *     board -> phone {"t":"mrx","d":"<b64>"}     phone -> board {"t":"mtx","d":"<b64>"}
 *
 * The board advertises as MuseGadgetXXXXXX (last 3 address bytes), which is the
 * name the Muse app expects for a gadget whose node id is homelink-xxxxxx.
 */
#include <rtthread.h>
#include <rtdevice.h>
#include <board.h>
#include <string.h>
#include <stdlib.h>

#include "bf0_ble_gap.h"
#include "bf0_sibles.h"
#include "bf0_sibles_internal.h"
#include "bf0_sibles_advertising.h"
#include "ble_connection_manager.h"

#include "app.h"

#define LOG_TAG "ble_link"
#include "log.h"

#define SERIAL_UUID_16(x) {((uint8_t)(x&0xff)),((uint8_t)(x>>8))}

#define RX_QUEUE_DEPTH      8
#define SETUP_QUEUE_DEPTH   8
#define MAX_CONN            8

/* ---------- Bridge service ---------- */

enum bridge_att_list
{
    BRIDGE_SVC,
    BRIDGE_RX_CHAR,
    BRIDGE_RX_VALUE,
    BRIDGE_TX_CHAR,
    BRIDGE_TX_VALUE,
    BRIDGE_TX_CCCD,
    BRIDGE_AUDIO_CHAR,
    BRIDGE_AUDIO_VALUE,
    BRIDGE_AUDIO_CCCD,
    BRIDGE_ATT_NB
};

/* 128-bit UUIDs, little-endian byte order. */
#define BRIDGE_SVC_UUID { 0x9E, 0xCA, 0xDC, 0x24, 0x0E, 0xE5, 0xA9, 0xE0, \
                          0x93, 0xF3, 0xA3, 0xB5, 0x01, 0x00, 0x40, 0x6E }
#define BRIDGE_RX_UUID  { 0x9E, 0xCA, 0xDC, 0x24, 0x0E, 0xE5, 0xA9, 0xE0, \
                          0x93, 0xF3, 0xA3, 0xB5, 0x02, 0x00, 0x40, 0x6E }
#define BRIDGE_TX_UUID  { 0x9E, 0xCA, 0xDC, 0x24, 0x0E, 0xE5, 0xA9, 0xE0, \
                          0x93, 0xF3, 0xA3, 0xB5, 0x03, 0x00, 0x40, 0x6E }
#define BRIDGE_AUDIO_UUID { 0x9E, 0xCA, 0xDC, 0x24, 0x0E, 0xE5, 0xA9, 0xE0, \
                            0x93, 0xF3, 0xA3, 0xB5, 0x04, 0x00, 0x40, 0x6E }

static uint8_t g_bridge_svc_uuid[ATT_UUID_128_LEN] = BRIDGE_SVC_UUID;

BLE_GATT_SERVICE_DEFINE_128(bridge_att_db)
{
    BLE_GATT_SERVICE_DECLARE(BRIDGE_SVC, SERIAL_UUID_16_PRI_SERVICE, BLE_GATT_PERM_READ_ENABLE),

    BLE_GATT_CHAR_DECLARE(BRIDGE_RX_CHAR, SERIAL_UUID_16_CHARACTERISTIC, BLE_GATT_PERM_READ_ENABLE),
    BLE_GATT_CHAR_VALUE_DECLARE(BRIDGE_RX_VALUE, BRIDGE_RX_UUID,
                                BLE_GATT_PERM_WRITE_REQ_ENABLE | BLE_GATT_PERM_WRITE_COMMAND_ENABLE,
                                BLE_GATT_VALUE_PERM_UUID_128 | BLE_GATT_VALUE_PERM_RI_ENABLE,
                                APP_LINE_MAX),

    BLE_GATT_CHAR_DECLARE(BRIDGE_TX_CHAR, SERIAL_UUID_16_CHARACTERISTIC, BLE_GATT_PERM_READ_ENABLE),
    BLE_GATT_CHAR_VALUE_DECLARE(BRIDGE_TX_VALUE, BRIDGE_TX_UUID,
                                BLE_GATT_PERM_NOTIFY_ENABLE,
                                BLE_GATT_VALUE_PERM_UUID_128 | BLE_GATT_VALUE_PERM_RI_ENABLE,
                                APP_LINE_MAX),
    BLE_GATT_DESCRIPTOR_DECLARE(BRIDGE_TX_CCCD, SERIAL_UUID_16_CLIENT_CHAR_CFG,
                                BLE_GATT_PERM_READ_ENABLE | BLE_GATT_PERM_WRITE_REQ_ENABLE,
                                BLE_GATT_VALUE_PERM_RI_ENABLE, 2),

    BLE_GATT_CHAR_DECLARE(BRIDGE_AUDIO_CHAR, SERIAL_UUID_16_CHARACTERISTIC, BLE_GATT_PERM_READ_ENABLE),
    BLE_GATT_CHAR_VALUE_DECLARE(BRIDGE_AUDIO_VALUE, BRIDGE_AUDIO_UUID,
                                BLE_GATT_PERM_WRITE_COMMAND_ENABLE | BLE_GATT_PERM_NOTIFY_ENABLE,
                                BLE_GATT_VALUE_PERM_UUID_128 | BLE_GATT_VALUE_PERM_RI_ENABLE,
                                512),
    BLE_GATT_DESCRIPTOR_DECLARE(BRIDGE_AUDIO_CCCD, SERIAL_UUID_16_CLIENT_CHAR_CFG,
                                BLE_GATT_PERM_READ_ENABLE | BLE_GATT_PERM_WRITE_REQ_ENABLE,
                                BLE_GATT_VALUE_PERM_RI_ENABLE, 2),
};

/* ---------- Muse setup service ---------- */

enum setup_att_list
{
    SETUP_SVC,
    SETUP_RX_CHAR,
    SETUP_RX_VALUE,
    SETUP_TX_CHAR,
    SETUP_TX_VALUE,
    SETUP_TX_CCCD,
    SETUP_ATT_NB
};

/* 7fdd3d1c-38ea-46cf-8b46-314ecf5f240c */
#define SETUP_SVC_UUID { 0x0c, 0x24, 0x5f, 0xcf, 0x4e, 0x31, 0x46, 0x8b, \
                         0xcf, 0x46, 0xea, 0x38, 0x1c, 0x3d, 0xdd, 0x7f }
/* 4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01 */
#define SETUP_RX_UUID  { 0x01, 0x9b, 0x8f, 0x5e, 0x2d, 0x3c, 0xf0, 0xa1, \
                         0x6e, 0x4a, 0xa2, 0x28, 0x29, 0x30, 0x59, 0x4d }
/* d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c */
#define SETUP_TX_UUID  { 0x6c, 0x5b, 0x4a, 0x3f, 0x2e, 0x1d, 0x0a, 0x8f, \
                         0x9c, 0x4e, 0x2b, 0x7b, 0xca, 0xc4, 0x5d, 0xd7 }

static uint8_t g_setup_svc_uuid[ATT_UUID_128_LEN] = SETUP_SVC_UUID;

BLE_GATT_SERVICE_DEFINE_128(setup_att_db)
{
    BLE_GATT_SERVICE_DECLARE(SETUP_SVC, SERIAL_UUID_16_PRI_SERVICE, BLE_GATT_PERM_READ_ENABLE),

    BLE_GATT_CHAR_DECLARE(SETUP_RX_CHAR, SERIAL_UUID_16_CHARACTERISTIC, BLE_GATT_PERM_READ_ENABLE),
    BLE_GATT_CHAR_VALUE_DECLARE(SETUP_RX_VALUE, SETUP_RX_UUID,
                                BLE_GATT_PERM_WRITE_REQ_ENABLE | BLE_GATT_PERM_WRITE_COMMAND_ENABLE,
                                BLE_GATT_VALUE_PERM_UUID_128 | BLE_GATT_VALUE_PERM_RI_ENABLE,
                                SETUP_PACKET_MAX),

    BLE_GATT_CHAR_DECLARE(SETUP_TX_CHAR, SERIAL_UUID_16_CHARACTERISTIC, BLE_GATT_PERM_READ_ENABLE),
    BLE_GATT_CHAR_VALUE_DECLARE(SETUP_TX_VALUE, SETUP_TX_UUID,
                                BLE_GATT_PERM_READ_ENABLE | BLE_GATT_PERM_NOTIFY_ENABLE,
                                BLE_GATT_VALUE_PERM_UUID_128 | BLE_GATT_VALUE_PERM_RI_ENABLE,
                                SETUP_PACKET_MAX),
    BLE_GATT_DESCRIPTOR_DECLARE(SETUP_TX_CCCD, SERIAL_UUID_16_CLIENT_CHAR_CFG,
                                BLE_GATT_PERM_READ_ENABLE | BLE_GATT_PERM_WRITE_REQ_ENABLE,
                                BLE_GATT_VALUE_PERM_RI_ENABLE, 2),
};

/* ---------- state ---------- */

typedef struct
{
    uint16_t len;
    uint8_t data[SETUP_PACKET_MAX];
} setup_packet_t;

static struct
{
    sibles_hdl bridge_handle;
    sibles_hdl setup_handle;
    uint8_t conn_idx;           /* our phone app */
    uint8_t connected;
    uint8_t notify_on;
    uint16_t cccd;
    uint16_t audio_cccd;
    uint8_t setup_conn_idx;     /* the Muse app (may be the same link) */
    uint8_t setup_notify_on;
    uint16_t setup_cccd;
    uint16_t mtu[MAX_CONN];
    uint8_t conn_count;
    rt_mq_t rx_mq;
    rt_mq_t setup_mq;
    char rx_line[APP_LINE_MAX];
    rt_size_t rx_len;
    uint8_t rx_overflow;
    char name[24];
    char addr[18];
} s_link;

SIBLES_ADVERTISING_CONTEXT_DECLAR(g_bridge_adv_context);

static uint16_t conn_mtu(uint8_t conn_idx)
{
    uint16_t m = (conn_idx < MAX_CONN) ? s_link.mtu[conn_idx] : 0;
    return m ? m : 23;
}

/* ---------- base64 ---------- */

static const char B64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

rt_size_t b64_encode(const uint8_t *in, rt_size_t len, char *out, rt_size_t out_size)
{
    rt_size_t o = 0;
    if (out_size < ((len + 2) / 3) * 4 + 1)
        return 0;
    for (rt_size_t i = 0; i < len; i += 3)
    {
        uint32_t v = (uint32_t)in[i] << 16;
        if (i + 1 < len) v |= (uint32_t)in[i + 1] << 8;
        if (i + 2 < len) v |= in[i + 2];
        out[o++] = B64[(v >> 18) & 63];
        out[o++] = B64[(v >> 12) & 63];
        out[o++] = (i + 1 < len) ? B64[(v >> 6) & 63] : '=';
        out[o++] = (i + 2 < len) ? B64[v & 63] : '=';
    }
    out[o] = '\0';
    return o;
}

static int b64_val(char c)
{
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+' || c == '-') return 62;
    if (c == '/' || c == '_') return 63;
    return -1;
}

int b64_decode(const char *in, uint8_t *out, rt_size_t out_size)
{
    uint32_t acc = 0;
    int bits = 0;
    rt_size_t o = 0;
    for (; *in && *in != '='; in++)
    {
        int v = b64_val(*in);
        if (v < 0)
            return -1;
        acc = (acc << 6) | (uint32_t)v;
        bits += 6;
        if (bits >= 8)
        {
            bits -= 8;
            if (o >= out_size)
                return -1;
            out[o++] = (uint8_t)(acc >> bits);
        }
    }
    return (int)o;
}

/* ---------- RX: called in the BLE task, keep it short ---------- */

static void rx_feed(const uint8_t *data, uint16_t len)
{
    for (uint16_t i = 0; i < len; i++)
    {
        char c = (char)data[i];
        if (c == '\n')
        {
            if (!s_link.rx_overflow && s_link.rx_len > 0)
            {
                s_link.rx_line[s_link.rx_len] = '\0';
                if (rt_mq_send(s_link.rx_mq, s_link.rx_line, s_link.rx_len + 1) != RT_EOK)
                    LOG_W("rx queue full, line dropped");
            }
            s_link.rx_len = 0;
            s_link.rx_overflow = 0;
        }
        else if (c != '\r')
        {
            if (s_link.rx_len < APP_LINE_MAX - 1)
                s_link.rx_line[s_link.rx_len++] = c;
            else
                s_link.rx_overflow = 1;
        }
    }
}

static uint8_t *bridge_get_cbk(uint8_t conn_idx, uint8_t idx, uint16_t *len)
{
    *len = 0;
    if (idx == BRIDGE_TX_CCCD)
    {
        *len = 2;
        return (uint8_t *)&s_link.cccd;
    }
    if (idx == BRIDGE_AUDIO_CCCD)
    {
        *len = 2;
        return (uint8_t *)&s_link.audio_cccd;
    }
    return NULL;
}

static uint8_t bridge_set_cbk(uint8_t conn_idx, sibles_set_cbk_t *para)
{
    switch (para->idx)
    {
    case BRIDGE_RX_VALUE:
        rx_feed(para->value, para->len);
        break;

    case BRIDGE_TX_CCCD:
        s_link.cccd = para->value[0];
        s_link.notify_on = (para->value[0] & 0x01) ? 1 : 0;
        if (s_link.notify_on)
        {
            s_link.conn_idx = conn_idx;
            s_link.connected = 1;
            /* Voice needs ~10 KB/s each way: 7.5-15 ms interval, no latency, 5 s timeout. */
            ble_gap_update_conn_param(BLE_GAP_CREATE_UPDATE_CONN_PARA(conn_idx, 6, 12, 0, 500));
        }
        LOG_I("bridge notify %s (conn %d)", s_link.notify_on ? "on" : "off", conn_idx);
        rt_mb_send(g_app_mb, s_link.notify_on ? APP_EVT_LINK_UP : APP_EVT_LINK_DOWN);
        break;

    case BRIDGE_AUDIO_VALUE:
        audio_speaker_feed(para->value, para->len);
        break;

    case BRIDGE_AUDIO_CCCD:
        s_link.audio_cccd = para->value[0];
        break;

    default:
        break;
    }
    return 0;
}

static uint8_t s_empty;

static uint8_t *setup_get_cbk(uint8_t conn_idx, uint8_t idx, uint16_t *len)
{
    *len = 0;
    if (idx == SETUP_TX_CCCD)
    {
        *len = 2;
        return (uint8_t *)&s_link.setup_cccd;
    }
    return &s_empty;    /* TX is readable but carries nothing */
}

static uint8_t setup_set_cbk(uint8_t conn_idx, sibles_set_cbk_t *para)
{
    switch (para->idx)
    {
    case SETUP_RX_VALUE:
    {
        static setup_packet_t pkt;
        s_link.setup_conn_idx = conn_idx;
        pkt.len = (para->len > SETUP_PACKET_MAX) ? SETUP_PACKET_MAX : para->len;
        memcpy(pkt.data, para->value, pkt.len);
        if (rt_mq_send(s_link.setup_mq, &pkt, sizeof(pkt)) != RT_EOK)
            LOG_W("setup queue full, packet dropped");
        break;
    }
    case SETUP_TX_CCCD:
        s_link.setup_cccd = para->value[0];
        s_link.setup_notify_on = (para->value[0] & 0x01) ? 1 : 0;
        s_link.setup_conn_idx = conn_idx;
        LOG_I("Muse app notify %s (conn %d)", s_link.setup_notify_on ? "on" : "off", conn_idx);
        rt_mb_send(g_app_mb, s_link.setup_notify_on ? APP_EVT_SETUP_UP : APP_EVT_SETUP_DOWN);
        break;
    default:
        break;
    }
    return 0;
}

/* ---------- TX: call from the main thread, never from the BLE task ---------- */

bool ble_link_is_ready(void)
{
    return s_link.connected && s_link.notify_on;
}

static bool notify_chunk(sibles_hdl hdl, uint8_t idx, uint8_t conn, const uint8_t *data, uint16_t len)
{
    sibles_value_t value;
    value.hdl = hdl;
    value.idx = idx;
    value.len = len;
    value.value = (uint8_t *)data;

    for (int retry = 0; retry < 20; retry++)
    {
        if (sibles_write_value(conn, &value) == len)
            return true;
        rt_thread_mdelay(20);   /* TX queue full, wait */
    }
    return false;
}

bool ble_link_send_line(const char *line)
{
    if (!ble_link_is_ready())
        return false;

    rt_size_t len = strlen(line);
    uint16_t mtu = conn_mtu(s_link.conn_idx);
    uint16_t chunk = mtu - 3;
    char *buf = rt_malloc(len + 1);
    if (!buf)
        return false;
    memcpy(buf, line, len);
    buf[len++] = '\n';

    bool ok = true;
    for (rt_size_t off = 0; off < len && ok; off += chunk)
    {
        uint16_t n = (len - off > chunk) ? chunk : (uint16_t)(len - off);
        ok = ble_link_is_ready() &&
             notify_chunk(s_link.bridge_handle, BRIDGE_TX_VALUE, s_link.conn_idx, (uint8_t *)buf + off, n);
    }
    rt_free(buf);
    return ok;
}

int ble_link_audio_payload(void)
{
    return conn_mtu(s_link.conn_idx) - 3;
}

bool ble_link_audio_send(const uint8_t *data, uint16_t len)
{
    if (!ble_link_is_ready() || !(s_link.audio_cccd & 0x01))
        return false;
    return notify_chunk(s_link.bridge_handle, BRIDGE_AUDIO_VALUE, s_link.conn_idx, data, len);
}

bool ble_link_pop_line(char *buf, rt_size_t size)
{
    return s_link.rx_mq && rt_mq_recv(s_link.rx_mq, buf, size, 0) == RT_EOK;
}

/* Muse app -> phone app. */
void ble_link_forward_setup_packets(void)
{
    static setup_packet_t pkt;
    static char line[SETUP_PACKET_MAX * 4 / 3 + 32];
    while (s_link.setup_mq && rt_mq_recv(s_link.setup_mq, &pkt, sizeof(pkt), 0) == RT_EOK)
    {
        rt_size_t n = rt_snprintf(line, sizeof(line), "{\"t\":\"mrx\",\"d\":\"");
        rt_size_t enc = b64_encode(pkt.data, pkt.len, line + n, sizeof(line) - n - 4);
        if (enc == 0 && pkt.len > 0)
            continue;
        strcpy(line + n + enc, "\"}");
        if (!ble_link_send_line(line))
            LOG_W("setup packet not forwarded: phone app not connected");
    }
}

/* Phone app -> Muse app. */
bool ble_link_setup_send_b64(const char *b64)
{
    static uint8_t pkt[SETUP_PACKET_MAX];
    int n = b64_decode(b64, pkt, sizeof(pkt));
    if (n <= 0 || !s_link.setup_notify_on)
        return false;
    uint16_t max = conn_mtu(s_link.setup_conn_idx) - 3;
    if (n > max)
    {
        LOG_W("setup packet %d > MTU payload %d", n, max);
        return false;
    }
    return notify_chunk(s_link.setup_handle, SETUP_TX_VALUE, s_link.setup_conn_idx, pkt, (uint16_t)n);
}

/* ---------- Advertising & service registration ---------- */

static uint8_t bridge_adv_event(uint8_t event, void *context, void *data)
{
    if (event == SIBLES_ADV_EVT_ADV_STARTED)
    {
        sibles_adv_evt_startted_t *evt = (sibles_adv_evt_startted_t *)data;
        LOG_I("adv started %d", evt->status);
    }
    else if (event == SIBLES_ADV_EVT_ADV_STOPPED)
    {
        sibles_adv_evt_stopped_t *evt = (sibles_adv_evt_stopped_t *)data;
        LOG_I("adv stopped %d", evt->reason);
    }
    return 0;
}

static void bridge_adv_start(void)
{
    sibles_advertising_para_t para = {0};
    bd_addr_t addr;

    if (ble_get_public_address(&addr) == HL_ERR_NO_ERROR)
    {
        rt_snprintf(s_link.name, sizeof(s_link.name), "MuseGadget%02X%02X%02X", addr.addr[2], addr.addr[1], addr.addr[0]);
        rt_snprintf(s_link.addr, sizeof(s_link.addr), "%02x:%02x:%02x:%02x:%02x:%02x",
                    addr.addr[5], addr.addr[4], addr.addr[3], addr.addr[2], addr.addr[1], addr.addr[0]);
    }
    else
    {
        strcpy(s_link.name, "MuseGadget000000");
        strcpy(s_link.addr, "02:00:00:00:00:00");
    }

    ble_gap_dev_name_t *dev_name = malloc(sizeof(ble_gap_dev_name_t) + strlen(s_link.name));
    dev_name->len = strlen(s_link.name);
    memcpy(dev_name->name, s_link.name, dev_name->len);
    ble_gap_set_dev_name(dev_name);
    free(dev_name);

    /* The Muse app reads company 0xFFFF's first byte as a "paired" flag. */
    uint8_t manu_data[] = {0x00};

    para.own_addr_type = GAPM_STATIC_ADDR;
    para.config.adv_mode = SIBLES_ADV_CONNECT_MODE;
    para.config.mode_config.conn_config.duration = 0x0;     /* forever */
    para.config.mode_config.conn_config.interval = 0x30;    /* 30 ms */
    para.config.max_tx_pwr = 0x7F;
    para.config.is_auto_restart = 1;                         /* re-advertise after disconnect */

    para.adv_data.completed_uuid = rt_malloc(sizeof(sibles_adv_type_srv_uuid_t) + sizeof(sibles_adv_uuid_t));
    para.adv_data.completed_uuid->count = 1;
    para.adv_data.completed_uuid->uuid_list[0].uuid_len = ATT_UUID_128_LEN;
    memcpy(para.adv_data.completed_uuid->uuid_list[0].uuid.uuid_128, g_setup_svc_uuid, ATT_UUID_128_LEN);

    para.adv_data.manufacturer_data = rt_malloc(sizeof(sibles_adv_type_manufacturer_data_t) + sizeof(manu_data));
    para.adv_data.manufacturer_data->company_id = 0xFFFF;
    para.adv_data.manufacturer_data->data_len = sizeof(manu_data);
    memcpy(para.adv_data.manufacturer_data->additional_data, manu_data, sizeof(manu_data));

    para.rsp_data.completed_name = rt_malloc(strlen(s_link.name) + sizeof(sibles_adv_type_name_t));
    para.rsp_data.completed_name->name_len = strlen(s_link.name);
    memcpy(para.rsp_data.completed_name->name, s_link.name, strlen(s_link.name));

    para.evt_handler = bridge_adv_event;

    if (sibles_advertising_init(g_bridge_adv_context, &para) == SIBLES_ADV_NO_ERR)
        sibles_advertising_start(g_bridge_adv_context);

    rt_free(para.adv_data.completed_uuid);
    rt_free(para.adv_data.manufacturer_data);
    rt_free(para.rsp_data.completed_name);
    LOG_I("advertising as %s", s_link.name);
}

bool ble_link_pairing_adv(bool on)
{
    uint8_t ret = on ? sibles_advertising_start(g_bridge_adv_context)
                  : sibles_advertising_stop(g_bridge_adv_context);
    LOG_I("pairing adv %s -> %d", on ? "start" : "stop", ret);
    return ret == SIBLES_ADV_NO_ERR;
}

const char *ble_link_name(void)
{
    return s_link.name;
}

const char *ble_link_addr(void)
{
    return s_link.addr;
}

void ble_link_start(void)
{
    if (!s_link.rx_mq)
        s_link.rx_mq = rt_mq_create("ble_rx", APP_LINE_MAX, RX_QUEUE_DEPTH, RT_IPC_FLAG_FIFO);
    if (!s_link.setup_mq)
        s_link.setup_mq = rt_mq_create("setup_rx", sizeof(setup_packet_t), SETUP_QUEUE_DEPTH, RT_IPC_FLAG_FIFO);

    BLE_GATT_SERVICE_INIT_128(bridge_svc, bridge_att_db, BRIDGE_ATT_NB,
                              BLE_GATT_SERVICE_PERM_NOAUTH | BLE_GATT_SERVICE_PERM_UUID_128 | BLE_GATT_SERVICE_PERM_MULTI_LINK,
                              g_bridge_svc_uuid);
    s_link.bridge_handle = sibles_register_svc_128(&bridge_svc);
    if (s_link.bridge_handle)
        sibles_register_cbk(s_link.bridge_handle, bridge_get_cbk, bridge_set_cbk);

    BLE_GATT_SERVICE_INIT_128(setup_svc, setup_att_db, SETUP_ATT_NB,
                              BLE_GATT_SERVICE_PERM_NOAUTH | BLE_GATT_SERVICE_PERM_UUID_128 | BLE_GATT_SERVICE_PERM_MULTI_LINK,
                              g_setup_svc_uuid);
    s_link.setup_handle = sibles_register_svc_128(&setup_svc);
    if (s_link.setup_handle)
        sibles_register_cbk(s_link.setup_handle, setup_get_cbk, setup_set_cbk);

    bridge_adv_start();
}

/* ---------- GAP events (BLE task) ---------- */

static int bridge_event_handler(uint16_t event_id, uint8_t *data, uint16_t len, uint32_t context)
{
    switch (event_id)
    {
    case BLE_POWER_ON_IND:
        rt_mb_send(g_app_mb, APP_EVT_BLE_POWER_ON);
        break;

    case BLE_GAP_CONNECTED_IND:
    {
        ble_gap_connect_ind_t *ind = (ble_gap_connect_ind_t *)data;
        if (ind->conn_idx < MAX_CONN)
            s_link.mtu[ind->conn_idx] = 23;
        s_link.conn_count++;
        LOG_I("connected (conn %d, %d total)", ind->conn_idx, s_link.conn_count);
        /* Phone Bridge and the Muse app (possibly on another phone) both need to
         * connect, in either order, so keep advertising until two are connected. */
        if (s_link.conn_count < 2)
            rt_mb_send(g_app_mb, APP_EVT_READVERTISE);
        break;
    }
    case BLE_GAP_UPDATE_CONN_PARAM_IND:
    {
        ble_gap_update_conn_param_ind_t *ind = (ble_gap_update_conn_param_ind_t *)data;
        LOG_I("conn interval %d x 1.25 ms (conn %d)", ind->con_interval, ind->conn_idx);
        break;
    }
    case SIBLES_MTU_EXCHANGE_IND:
    {
        sibles_mtu_exchange_ind_t *ind = (sibles_mtu_exchange_ind_t *)data;
        if (ind->conn_idx < MAX_CONN)
            s_link.mtu[ind->conn_idx] = ind->mtu;
        LOG_I("MTU %d (conn %d)", ind->mtu, ind->conn_idx);
        break;
    }
    case BLE_GAP_DISCONNECTED_IND:
    {
        ble_gap_disconnected_ind_t *ind = (ble_gap_disconnected_ind_t *)data;
        if (s_link.conn_count)
            s_link.conn_count--;
        LOG_I("disconnected (conn %d, reason %d, %d left)", ind->conn_idx, ind->reason, s_link.conn_count);
        if (s_link.connected && ind->conn_idx == s_link.conn_idx)
        {
            s_link.connected = 0;
            s_link.notify_on = 0;
            s_link.cccd = 0;
            s_link.audio_cccd = 0;
            s_link.rx_len = 0;
            rt_mb_send(g_app_mb, APP_EVT_LINK_DOWN);
        }
        if (s_link.setup_notify_on && ind->conn_idx == s_link.setup_conn_idx)
        {
            s_link.setup_notify_on = 0;
            s_link.setup_cccd = 0;
            rt_mb_send(g_app_mb, APP_EVT_SETUP_DOWN);
        }
        break;
    }
    default:
        break;
    }
    return 0;
}
BLE_EVENT_REGISTER(bridge_event_handler, NULL);

/* ---------- Glue required by the SiFli BLE stack ---------- */

uint8_t ble_app_dis_enable(void)
{
    return 1;
}

/* Cap the ATT MTU. Android 14+ negotiates 517, and the Muse app writes
 * (MTU - 3) bytes uncapped while Android rejects writes over 512, so its Wi-Fi /
 * credentials step fails. Meta's Linux SDK caps it at 256 for the same reason. */
uint16_t ble_max_mtu_get(void)
{
    return 247;
}

#ifndef NVDS_AUTO_UPDATE_MAC_ADDRESS_ENABLE
ble_common_update_type_t ble_request_public_address(bd_addr_t *addr)
{
    int ret = bt_mac_addr_generate_via_uid_v2(addr);
    if (ret != 0)
    {
        LOG_W("generate mac address failed %d", ret);
        return BLE_UPDATE_NO_UPDATE;
    }
    return BLE_UPDATE_ONCE;
}
#endif

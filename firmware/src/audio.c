/*
 * Phone Bridge - voice: microphone up to the phone, speaker down from it.
 *
 * Audio travels on the bridge service's AUDIO characteristic as IMA ADPCM,
 * 16 kHz mono (4 bits/sample, 8 KB/s). Each BLE packet is self-contained:
 *   [kind u8][seq u8][predictor i16 LE][step index u8][ADPCM nibbles, low first]
 * kind 0x01 = mic (board -> phone), 0x02 = speaker (phone -> board).
 *
 * The mic runs while push-to-talk is held; the speaker plays what the phone
 * streams after {"t":"audio_start"} until {"t":"audio_end"}. Half duplex.
 */
#include <rtthread.h>
#include <rtdevice.h>
#include <string.h>
#include <stdlib.h>
#include "audio_server.h"
#include "app.h"

#define LOG_TAG "audio"
#include "log.h"

#define RATE            16000
#define HDR             5
#define KIND_MIC        0x01
#define KIND_SPK        0x02
#define MIC_RING        (16 * 1024)
#define SPK_RING        32760              /* ~1 s of 16 kHz PCM; rt_ringbuffer max is 32767 */
#define SPK_PREBUFFER   (RATE * 2 * 4 / 10) /* start playing after 400 ms */
#define SPK_REBUFFER    (RATE * 2 / 5)       /* after running dry, wait for 200 ms before resuming */
#define WRITE_CACHE     4096
#define READ_CACHE      2048
#define MIC_MAX_MS      60000              /* push-to-talk limit (Meta's ESP32 SDK notes Muse stopped at 15 s) */

/* ---------- IMA ADPCM ---------- */

static const int16_t STEP[89] =
{
    7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
    50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
    253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
    1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
    3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442,
    11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
};
static const int8_t INDEX_ADJ[16] = {-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8};

typedef struct
{
    int32_t predictor;
    int32_t index;
} adpcm_state_t;

static uint8_t adpcm_encode_sample(adpcm_state_t *s, int16_t sample)
{
    int32_t step = STEP[s->index];
    int32_t diff = sample - s->predictor;
    uint8_t code = 0;
    if (diff < 0) { code = 8; diff = -diff; }
    int32_t delta = step >> 3;
    if (diff >= step) { code |= 4; diff -= step; delta += step; }
    step >>= 1;
    if (diff >= step) { code |= 2; diff -= step; delta += step; }
    step >>= 1;
    if (diff >= step) { code |= 1; delta += step; }
    s->predictor += (code & 8) ? -delta : delta;
    if (s->predictor > 32767) s->predictor = 32767;
    if (s->predictor < -32768) s->predictor = -32768;
    s->index += INDEX_ADJ[code];
    if (s->index < 0) s->index = 0;
    if (s->index > 88) s->index = 88;
    return code;
}

static int16_t adpcm_decode_sample(adpcm_state_t *s, uint8_t code)
{
    int32_t step = STEP[s->index];
    int32_t delta = step >> 3;
    if (code & 4) delta += step;
    if (code & 2) delta += step >> 1;
    if (code & 1) delta += step >> 2;
    s->predictor += (code & 8) ? -delta : delta;
    if (s->predictor > 32767) s->predictor = 32767;
    if (s->predictor < -32768) s->predictor = -32768;
    s->index += INDEX_ADJ[code];
    if (s->index < 0) s->index = 0;
    if (s->index > 88) s->index = 88;
    return (int16_t)s->predictor;
}

/* ---------- microphone ---------- */

static audio_client_t s_mic;
static struct rt_ringbuffer *s_mic_ring;
static rt_sem_t s_mic_sem;
static volatile bool s_mic_on;
static rt_tick_t s_mic_started;
static uint8_t s_mic_seq;
static adpcm_state_t s_mic_state;

static int mic_callback(audio_server_callback_cmt_t cmd, void *user, uint32_t reserved)
{
    if (cmd == as_callback_cmd_data_coming && s_mic_on)
    {
        audio_server_coming_data_t *p = (audio_server_coming_data_t *)reserved;
        rt_ringbuffer_put(s_mic_ring, p->data, p->data_len);
        rt_sem_release(s_mic_sem);
    }
    return 0;
}

static void mic_thread(void *arg)
{
    static int16_t pcm[(247 - 3 - HDR) * 2];
    static uint8_t pkt[247];
    while (1)
    {
        rt_sem_take(s_mic_sem, RT_WAITING_FOREVER);
        while (s_mic_on)
        {
            int payload = ble_link_audio_payload() - HDR;
            if (payload <= 0)
                break;
            int samples = payload * 2;
            if (samples > (int)(sizeof(pcm) / 2))
                samples = sizeof(pcm) / 2;
            if (rt_ringbuffer_data_len(s_mic_ring) < samples * 2)
                break;
            rt_ringbuffer_get(s_mic_ring, (uint8_t *)pcm, samples * 2);

            pkt[0] = KIND_MIC;
            pkt[1] = s_mic_seq++;
            pkt[2] = (uint8_t)(s_mic_state.predictor & 0xFF);
            pkt[3] = (uint8_t)((s_mic_state.predictor >> 8) & 0xFF);
            pkt[4] = (uint8_t)s_mic_state.index;
            for (int i = 0; i < samples; i += 2)
            {
                uint8_t lo = adpcm_encode_sample(&s_mic_state, pcm[i]);
                uint8_t hi = adpcm_encode_sample(&s_mic_state, pcm[i + 1]);
                pkt[HDR + i / 2] = lo | (hi << 4);
            }
            if (!ble_link_audio_send(pkt, HDR + samples / 2))
                LOG_W("mic packet dropped");
        }
        if (s_mic_on && rt_tick_get() - s_mic_started > rt_tick_from_millisecond(MIC_MAX_MS))
            rt_mb_send(g_app_mb, APP_EVT_TALK_TIMEOUT);
    }
}

bool audio_mic_start(void)
{
    if (s_mic)
        return true;
    audio_speaker_stop();
    audio_parameter_t param = {0};
    param.read_bits_per_sample = 16;
    param.read_channnel_num = 1;
    param.read_samplerate = RATE;
    param.read_cache_size = READ_CACHE;
    param.write_bits_per_sample = 16;
    param.write_channnel_num = 1;
    param.write_samplerate = RATE;
    param.write_cache_size = WRITE_CACHE;
    rt_ringbuffer_reset(s_mic_ring);
    memset(&s_mic_state, 0, sizeof(s_mic_state));
    s_mic_seq = 0;
    s_mic_started = rt_tick_get();
    s_mic_on = true;
    s_mic = audio_open(AUDIO_TYPE_LOCAL_RECORD, AUDIO_RX, &param, mic_callback, NULL);
    if (!s_mic)
    {
        s_mic_on = false;
        LOG_E("mic open failed");
        return false;
    }
    LOG_I("mic on");
    return true;
}

void audio_mic_stop(void)
{
    if (!s_mic)
        return;
    audio_close(s_mic);
    s_mic = NULL;
    rt_sem_release(s_mic_sem);    /* flush what's left */
    rt_thread_mdelay(30);
    s_mic_on = false;
    LOG_I("mic off");
}

/* ---------- speaker ---------- */

static audio_client_t s_spk;
static struct rt_ringbuffer *s_spk_ring;
static volatile bool s_spk_streaming;    /* between audio_start and audio_end */
static volatile bool s_spk_ending;
static volatile uint16_t s_spk_level;
static volatile bool s_spk_ready_sent;
static volatile bool s_spk_rebuffering;
/* Playback diagnostics, logged when a clip ends. */
static volatile uint32_t s_spk_packets, s_spk_lost, s_spk_underruns, s_spk_overflows, s_spk_short;
static uint8_t s_volume = 10;           /* 0..15; full volume distorts the DevKit speaker */
static volatile int s_spk_last_seq = -1;
static uint8_t s_spk_buf[WRITE_CACHE / 2];

static int spk_callback(audio_server_callback_cmt_t cmd, void *user, uint32_t reserved)
{
    if ((cmd == as_callback_cmd_cache_half_empty || cmd == as_callback_cmd_cache_empty) && s_spk)
    {
        rt_size_t avail = rt_ringbuffer_data_len(s_spk_ring);
        if (avail == 0 && s_spk_ending)
        {
            rt_mb_send(g_app_mb, APP_EVT_SPEAKER_DONE);
            return 0;
        }
        /* Ran dry mid-stream: play silence until a little audio has built up again,
         * rather than chopping each late packet into its own burst. */
        if (!s_spk_ending && (s_spk_rebuffering ? avail < SPK_REBUFFER : avail < sizeof(s_spk_buf)))
        {
            if (!s_spk_rebuffering)
                s_spk_underruns++;
            s_spk_rebuffering = true;
            memset(s_spk_buf, 0, sizeof(s_spk_buf));
            audio_write(s_spk, s_spk_buf, sizeof(s_spk_buf));
            return 0;
        }
        s_spk_rebuffering = false;
        rt_size_t n = rt_ringbuffer_get(s_spk_ring, s_spk_buf, sizeof(s_spk_buf));
        if (n < sizeof(s_spk_buf))    /* the clip's tail */
        {
            memset(s_spk_buf + n, 0, sizeof(s_spk_buf) - n);
            n = sizeof(s_spk_buf);
        }
        if (audio_write(s_spk, s_spk_buf, n) < (int)n)
            s_spk_short++;
    }
    return 0;
}

static void speaker_open(void)
{
    audio_parameter_t param = {0};
    param.write_bits_per_sample = 16;
    param.write_channnel_num = 1;
    param.write_samplerate = RATE;
    param.write_cache_size = WRITE_CACHE;
    param.read_bits_per_sample = 16;
    param.read_channnel_num = 1;
    param.read_samplerate = RATE;
    param.read_cache_size = READ_CACHE;
    audio_server_set_private_volume(AUDIO_TYPE_LOCAL_MUSIC, s_volume);
    s_spk = audio_open(AUDIO_TYPE_LOCAL_MUSIC, AUDIO_TX, &param, spk_callback, NULL);
    if (!s_spk)
        LOG_E("speaker open failed");
    else
        LOG_I("speaker on (volume %d)", s_volume);
}

void audio_speaker_begin(void)
{
    audio_mic_stop();
    rt_ringbuffer_reset(s_spk_ring);
    s_spk_ending = false;
    s_spk_ready_sent = false;
    s_spk_rebuffering = false;
    s_spk_packets = s_spk_lost = s_spk_underruns = s_spk_overflows = s_spk_short = 0;
    s_spk_last_seq = -1;
    s_spk_streaming = true;
}

void audio_speaker_end(void)
{
    s_spk_ending = true;
    if (!s_spk)
    {
        if (rt_ringbuffer_data_len(s_spk_ring) > 0)
            speaker_open();   /* short clip that never reached the prebuffer */
        else
            rt_mb_send(g_app_mb, APP_EVT_SPEAKER_DONE);
    }
}

void audio_speaker_stop(void)
{
    if (s_spk_packets)
        LOG_I("speaker clip: %u packets, %u lost, %u underruns, %u overflows, %u short writes",
              (unsigned)s_spk_packets, (unsigned)s_spk_lost, (unsigned)s_spk_underruns, (unsigned)s_spk_overflows,
              (unsigned)s_spk_short);
    s_spk_packets = 0;
    s_spk_streaming = false;
    s_spk_ending = false;
    if (s_spk)
    {
        audio_close(s_spk);
        s_spk = NULL;
        LOG_I("speaker off");
    }
    rt_ringbuffer_reset(s_spk_ring);
    s_spk_level = 0;
}

/* BLE task: decode one speaker packet into the ring. */
void audio_speaker_feed(const uint8_t *pkt, uint16_t len)
{
    static int16_t pcm[(512 - HDR) * 2];
    if (len <= HDR || pkt[0] != KIND_SPK || !s_spk_streaming)
        return;
    s_spk_packets++;
    if (s_spk_last_seq >= 0)
        s_spk_lost += (uint8_t)(pkt[1] - (uint8_t)s_spk_last_seq - 1);
    s_spk_last_seq = pkt[1];
    adpcm_state_t st;
    st.predictor = (int16_t)(pkt[2] | (pkt[3] << 8));
    st.index = pkt[4] > 88 ? 88 : pkt[4];
    int n = 0;
    uint32_t peak = 0;
    for (int i = HDR; i < len && n + 1 < (int)(sizeof(pcm) / 2); i++)
    {
        pcm[n++] = adpcm_decode_sample(&st, pkt[i] & 0x0F);
        pcm[n++] = adpcm_decode_sample(&st, pkt[i] >> 4);
        uint32_t a = (uint32_t)(pcm[n - 1] < 0 ? -pcm[n - 1] : pcm[n - 1]);
        if (a > peak) peak = a;
    }
    s_spk_level = (uint16_t)(peak > 65535 ? 65535 : peak);
    if (rt_ringbuffer_put(s_spk_ring, (uint8_t *)pcm, n * 2) < (rt_size_t)(n * 2))
        s_spk_overflows++;
    if (!s_spk && !s_spk_ready_sent && rt_ringbuffer_data_len(s_spk_ring) >= SPK_PREBUFFER)
    {
        s_spk_ready_sent = true;
        rt_mb_send(g_app_mb, APP_EVT_SPEAKER_READY);
    }
}

/* Main thread: open the speaker once enough audio is buffered. */
void audio_speaker_ready(void)
{
    if (!s_spk && s_spk_streaming)
        speaker_open();
}

void audio_set_volume(int level)
{
    s_volume = (uint8_t)(level < 0 ? 0 : (level > 15 ? 15 : level));
    audio_server_set_private_volume(AUDIO_TYPE_LOCAL_MUSIC, s_volume);
    LOG_I("volume %d", s_volume);
}

/* Plays a tone generated on the board (no phone, no Bluetooth) to judge the speaker itself. */
static void tone_thread(void *arg)
{
    int hz = (int)(intptr_t)arg;
    static int16_t pcm[480];
    int phase = 0, period = RATE / hz;
    audio_speaker_begin();
    for (int k = 0; k < RATE * 2 / 480; k++)            /* 2 seconds */
    {
        for (int i = 0; i < 480; i++, phase++)
        {
            /* triangle wave at ~40% amplitude: clean, no lookup table needed */
            int t = phase % period;
            int v = t < period / 2 ? (t * 4 * 13000 / period) - 13000 : 13000 - ((t - period / 2) * 4 * 13000 / period);
            pcm[i] = (int16_t)v;
        }
        while (rt_ringbuffer_space_len(s_spk_ring) < (int)sizeof(pcm))
            rt_thread_mdelay(10);
        rt_ringbuffer_put(s_spk_ring, (uint8_t *)pcm, sizeof(pcm));
        if (!s_spk && rt_ringbuffer_data_len(s_spk_ring) >= SPK_PREBUFFER && !s_spk_ready_sent)
        {
            s_spk_ready_sent = true;
            rt_mb_send(g_app_mb, APP_EVT_SPEAKER_READY);
        }
    }
    audio_speaker_end();
}

static int spk_test(int argc, char **argv)
{
    int hz = argc > 1 ? atoi(argv[1]) : 440;
    if (hz < 100 || hz > 4000) hz = 440;
    rt_thread_t t = rt_thread_create("tone", tone_thread, (void *)(intptr_t)hz, 1024, RT_THREAD_PRIORITY_MIDDLE, 10);
    if (t) rt_thread_startup(t);
    return 0;
}
MSH_CMD_EXPORT(spk_test, play a 2 s test tone: spk_test [hz]);

static int spk_vol(int argc, char **argv)
{
    if (argc > 1) audio_set_volume(atoi(argv[1]));
    rt_kprintf("volume %d\n", s_volume);
    return 0;
}
MSH_CMD_EXPORT(spk_vol, speaker volume 0-15: spk_vol [n]);

uint16_t audio_speaker_level(void)
{
    return s_spk ? s_spk_level : 0;
}

bool audio_mic_active(void)
{
    return s_mic != NULL;
}

void audio_init(void)
{
    s_mic_ring = rt_ringbuffer_create(MIC_RING);
    s_spk_ring = rt_ringbuffer_create(SPK_RING);
    s_mic_sem = rt_sem_create("mic", 0, RT_IPC_FLAG_FIFO);
    rt_thread_t t = rt_thread_create("mic", mic_thread, NULL, 2048, RT_THREAD_PRIORITY_MIDDLE, 10);
    if (t)
        rt_thread_startup(t);
}

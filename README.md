# Phone Bridge: Muse on a SiFli SF32LB52 board

The SF32LB52 DevKit shows the screen and reads the buttons. The Android phone
handles the internet, and it also connects to **Meta Muse** as a Muse gadget.
Muse can then put text on the board, show notifications, and ask Yes/No questions.

```
 SF32LB52 DevKit                 Android app (Kotlin)                 Internet
 ┌─────────────────┐   BLE    ┌──────────────────────────┐  wss/https ┌──────────────────┐
 │ LVGL screen     │◀────────▶│ BLE link + board protocol│◀──────────▶│ Muse (your VM)   │
 │ buttons, keys   │  JSON    │ Muse gadget client:      │  Noise XX  │ hatch.metaaivm   │
 │ Muse setup GATT │  lines   │  pairing v5, Noise XX,   │            │ api.muse.ai      │
 │ (relayed)       │          │  link-control, commands  │──────────▶ │ Open-Meteo       │
 └─────────────────┘          └──────────────────────────┘            └──────────────────┘
          ▲  BLE (pairing only, same phone)
          └── Muse app
```

## How Muse fits

- **Pairing:** the board advertises as `MuseGadgetXXXXXX` and exposes Meta's setup
  service. The Muse app pairs with the board, but the board does no cryptography.
  It relays each packet to our app, which runs the pairing protocol
  (P-256 ECDH, HKDF and AES-GCM, community mode `confirm_app`).
- **Connection:** our app gets device tokens from pairing. It looks up your Muse VM,
  opens `wss://hatch.metaaivm.com/v1/noise`, runs Noise XX and registers as a
  `homehub` device with these commands:

  | Command | What happens |
  |---|---|
  | `sifli.show_text` | Text in the board's message card (`caption`, `card` or `alert`) |
  | `sifli.notify` | Banner on the board for 5 s, and the LED lights |
  | `sifli.ask_confirm` | Yes/No dialog on the board (touch, or KEY1 = Yes and KEY2 = No), and the answer goes back to Muse |
  | `sifli.read_sensors` | Board connected, phone battery and charging state |
  | `device.health` | Bridge version, uptime |

- **Asking Muse:** press **Ask Muse** or **My day** on the board, use KEY1 or KEY2, or type
  in the app. The app sends the text to Muse as a chat message from the device.
  Muse's reply appears in the Muse chat, and Muse is asked to show it on the board with
  `sifli.show_text`.

## Talking to Muse (voice, captions, avatar)

- **Push-to-talk:** hold **KEY1** or the **Hold to talk** button on the board and speak (up to 15 s).
  The board records 16 kHz audio, compresses it to IMA ADPCM, and streams it over BLE. The phone
  sends it to Muse as a voice note, the same way the ESP32 SDK does
  (`POST /chat/stream` with a base64 WAV, while you talk).
- **Replies:** the phone keeps `POST /chat/subscribe` open. Muse's answer streams back as
  `delta.text_append` events and appears on the board as live captions.
- **Spoken replies:** Muse answers gadgets in text. The phone reads each finished answer with
  Android text-to-speech and streams it to the **board speaker** as ADPCM. Choose Board, Phone
  or Off under Settings.
- **Avatar:** the face on the board shows `idle`, `listening`, `thinking`, `speaking` or `error`.
- **My day** (on-screen button) and **KEY2** send the questions set in Settings. Typed questions
  in the app work the same way.

## Gmail and Google Calendar

The phone offers these to Muse as commands. It calls Google's APIs with your own token from
"Sign in with Google"; Muse only sees the results.

| Command | Confirm on board |
|---|---|
| `calendar.list_events`, `gmail.search`, `gmail.read`, `gmail.draft` | No |
| `calendar.create_event`, `gmail.send` | Yes (Yes/No dialog, KEY1 = Yes) |

Setup (once): a Google Cloud project with the Gmail API and Calendar API enabled. The OAuth consent screen
should be External, in Testing, with your account added as a test user. You also need an **Android** OAuth
client for package `com.phonebridge` with your signing SHA-1. Then tap **Sign in with Google** in the app.

## Everything Muse can use

You speak naturally ("add this to Asana and tell me when it's done", "email Cypher about X and
let me know when he replies", "schedule a meeting with Hamish tomorrow"). Muse works out the
steps and calls these commands. Each step appears on the board with a running, done or failed icon.
Integrations appear to Muse only once they're filled in under Settings.

| Area | Commands | Setup |
|---|---|---|
| Board | `sifli.show_text`, `sifli.notify`, `sifli.ask_confirm`, `sifli.read_sensors` | none |
| Gmail / Calendar | `gmail.search/read/draft/send`, `calendar.list_events/create_event` | Sign in with Google |
| Asana | `asana.create_task/list_tasks/complete_task/add_comment/list_projects` | personal access token |
| Follow-ups | `watch.email_reply/list/cancel` (checks every 2 min), `routine.create/list/delete` | none |
| Home | `home.states`, `home.call_service` (Home Assistant), `mqtt.publish` | HA URL + token, MQTT broker |
| Media | `media.play` (board or phone), `media.stop`, `media.cast_devices`, `media.cast`, `media.cast_stop` | none |
| Camera | `camera.read_text` (OCR), `camera.scan_code` (barcodes/QR), on-device ML Kit | camera permission |

Sending email, creating events, locks and alarms ask Yes/No on the board first.

**Avatar:** if `muse-gadget-sdk` sits next to `phone-bridge`, the firmware compiles the SDK's
pixel avatar (`esp32/avatar/muse_pixel.c`) from there. It is not copied into this project. That
avatar is Meta's character and its license doesn't grant rights to it. Fine on your own board,
but replace it with your own before you show it publicly: put a Muse-generated `muse_pixel.c` in
`firmware/avatar/` (see the SDK's AVATAR_RECIPE.md). Build with `MUSE_AVATAR=0` to use the plain drawn face.

To add your own capability, implement `CommandProvider` (`specs` + `run`) and add it to the
`providers` list in `Bridge.init`. See `google/GoogleCommands.kt`.

The Muse SDK token is read from `android_app/local.properties` (`muse.sdkToken=...`, not committed)
and can be overridden in Settings.

The protocol code is a Kotlin port of Meta's open-source Linux client
(`muse-gadget-sdk/linux`). The unit tests in `app/src/test` check it byte-for-byte
against that client: Noise handshake, encrypted frames and pairing vectors.

## Set up

1. **Flash the board.** In `firmware/project`, run `scons --board=sf32lb52-lcd_n16r8 -j8`, then
   `build_sf32lb52-lcd_n16r8_hcpu\uart_download.bat` (the board is on COM14 on this PC).
2. **Install the app.** In `android_app`, run `gradlew installDebug`, or press Run in Android Studio.
3. **Connect.** Open Phone Bridge and tap **Connect**. Allow Bluetooth and notifications.
   The board's top row shows a green "Phone" dot.
4. **Add the SDK token.** Get it at <https://gadgets.muse.ai/settings/sdk-tokens>, paste it
   under Settings, and tap Save. Read the [Gadget SDK Terms](https://gadgets.muse.ai/sdk-terms)
   first.
5. **Pair.** Tap **Pair with Muse**. Then, in the **Muse app**: Settings > Devices >
   **Developer mode** on > **Add Device (+)** > choose `MuseGadgetXXXXXX` > accept the
   community-device warning. When asked for Wi-Fi, pick "Use current connection". No
   password is needed.
6. The app shows **Muse: connected**, and the board shows a green Muse dot. Ask Muse
   something like "Show me a haiku on my desk display" or "Ask me on my desk display
   whether to turn off the lights".

Pairing is saved on the phone, so later you only tap Connect.

## Board protocol (BLE, one JSON object per line)

Bridge service: `6E400001/2/3-B5A3-F393-E0A9-E50E24DCCA9E` (Nordic UART UUIDs) for JSON lines,
plus `6E400004-…` for audio. Audio packets are `[kind][seq][predictor i16][index][ADPCM]`.
Kind 1 is the mic (notify) and kind 2 is the speaker (write without response).

Phone → board: `time`, `weather`, `avatar {state}`, `caption {title, text, append}`,
`msg {title, body, style}`, `notify {text}`, `confirm {id, question}`, `confirm_cancel {id}`,
`buttons {labels}`, `muse {state}`, `status {text}`, `pair {on}`, `mtx {d: base64}`,
`audio_start`, `audio_end`, `audio_stop`.

Board → phone: `hello {fw, name, addr}`, `talk {on}`, `btn {id}`, `key {id}`,
`confirm_answer {id, answer}`, `audio_done`, `mrx {d: base64}`, `mconn`, `mdisc`, `pair_adv {on, ok}`.

Muse setup service, using Meta's UUIDs: `7fdd3d1c-38ea-46cf-8b46-314ecf5f240c`, with RX `4d593029-…` and TX `d75dc4ca-…`.

## Files

- `firmware/src/ble_link.c`: both GATT services, advertising, setup relay, base64
- `firmware/src/ui.c`: avatar, captions, Muse status, banner, Yes/No dialog
- `firmware/src/audio.c`: mic and speaker, IMA ADPCM, push-to-talk
- `firmware/src/main.c`: event loop, message routing, keys
- `android_app/.../Bridge.kt`: board protocol, Muse commands, phone actions
- `android_app/.../BridgeService.kt`: foreground service, so it keeps working with the screen off
- `android_app/.../muse/`: Muse gadget client (`Pairing`, `SetupController`, `Noise`,
  `Envelope`, `LinkSession`, `MuseApi`, `MuseClient`, `Conversation`)
- `android_app/.../voice/`: `Adpcm` (matches the firmware) and `Speech` (text-to-speech to the board)
- `android_app/tools/gen_vectors.py`: regenerates the test vectors from Meta's Python client

## Known limits

- The pairing and live connection haven't been tested against Meta's servers yet. The protocol
  code matches the reference client, but the Muse app's behaviour with a board shared by
  two apps on the same phone has not been checked.
- Device tokens are kept in app-private storage, not encrypted at rest.
- The board fonts only include ASCII plus `°`, so the app strips other characters.
- Android only.

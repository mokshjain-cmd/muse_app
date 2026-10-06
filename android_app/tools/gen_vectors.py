"""Generates byte-exact test vectors from Meta's reference Python client
(muse-gadget-sdk/linux) so the Kotlin port can be checked against it.

    uv run --with cryptography python tools/gen_vectors.py

Writes app/src/test/resources/noise_vectors.json.
"""
import json
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SDK = HERE.parents[2] / "muse-gadget-sdk" / "linux" / "src"
sys.path.insert(0, str(SDK))

from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import x25519  # noqa: E402

from musegadget.noise import noise_xx, framing  # noqa: E402
from musegadget.noise.envelope import Header  # noqa: E402
from musegadget.noise.transport import NoiseTransport  # noqa: E402
from musegadget import link_client, ble_framing  # noqa: E402


def keypair(seed: int):
    priv = x25519.X25519PrivateKey.from_private_bytes(bytes([seed]) * 32)
    pub = priv.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    return noise_xx._X25519KeyPair(private_key=priv, public_key_bytes=pub)


# Fixed keys in order: initiator e, responder e, responder s, initiator s.
order = iter([keypair(0x11), keypair(0x22), keypair(0x33), keypair(0x44)])
noise_xx._generate_x25519_key_pair = lambda: next(order)
# Fixed chunk ids for framing.
chunk_ids = iter(range(1000, 2000))
framing._random_int64 = lambda: next(chunk_ids)

init = noise_xx.NoiseXXInitiator()
init.initialize()
resp = noise_xx.NoiseXXResponder(payload=b"server-hello")
resp.initialize()

msg1 = init.write_message1()
msg2 = resp.read_message1_and_write_message2(msg1)
payload2 = init.read_message2(msg2)
msg3 = init.write_message3()
resp.read_message3(msg3)
i_send, i_recv = init.split()
r_send, r_recv = resp.split()

client = NoiseTransport(i_send, i_recv)

# Device -> VM: open the control stream, send link.register, then a chat request.
ctrl = client.start_stream_request("POST", "/link-control")
register = link_client.encode_message({"type": "req", "id": "r1", "method": "link.register",
                                       "params": {"node_id": "homelink-abcdef"}})
body_frames = client.encrypt_body_chunk(ctrl.stream_id, register)
chat = client.encrypt_http_request(
    "POST", "/chat/stream", b'{"message":"hi"}',
    headers=[Header("Content-Type", "application/json"), Header("x-app-id", "musegadget")])

# VM -> device: a response frame on the control stream carrying an invoke.
from musegadget.noise.envelope import ApplicationResponse, BodyChunk, ServiceFrame  # noqa: E402
from musegadget.noise.transport import encode_response_envelope  # noqa: E402

invoke = link_client.encode_message({"method": "link.invoke", "id": "i1",
                                     "command": "sifli.show_text", "params": {"text": "Hello"}})
resp_plain = encode_response_envelope(ServiceFrame.response(
    ctrl.stream_id, ApplicationResponse(status=200, body=invoke, end_body=False)))
resp_frames = framing.encode_noise_frames(resp_plain, chunk_id=77)
resp_cipher = [r_send.encrypt_with_ad(b"", f) for f in resp_frames]
chunk_plain = encode_response_envelope(ServiceFrame.body_chunk(
    ctrl.stream_id, BodyChunk(data=b"more", end_body=True)))
chunk_cipher = [r_send.encrypt_with_ad(b"", f) for f in framing.encode_noise_frames(chunk_plain, chunk_id=78)]

h = lambda b: b.hex()  # noqa: E731
out = {
    "keys": {name: h(bytes([seed]) * 32) for name, seed in
             [("init_e", 0x11), ("resp_e", 0x22), ("resp_s", 0x33), ("init_s", 0x44)]},
    "msg1": h(msg1),
    "msg2": h(msg2),
    "msg2_payload": h(payload2),
    "msg3": h(msg3),
    "control_open_frames": [h(f) for f in ctrl.frames],
    "control_open_stream_id": ctrl.stream_id,
    "register_frames": [h(f) for f in body_frames],
    "chat_frames": [h(f) for f in chat.frames],
    "chat_stream_id": chat.stream_id,
    "chunk_ids_used": [1000, 1001, 1002],
    "server_response_frames": [h(f) for f in resp_cipher],
    "server_response_invoke_json": invoke[4:].decode(),
    "server_chunk_frames": [h(f) for f in chunk_cipher],
    "ble_chunks_mtu23": [h(p) for p in ble_framing.encode_chunks(b"x" * 50, 23)],
}
dest = HERE.parent / "app" / "src" / "test" / "resources" / "noise_vectors.json"
dest.parent.mkdir(parents=True, exist_ok=True)
dest.write_text(json.dumps(out, indent=1))
print("wrote", dest)

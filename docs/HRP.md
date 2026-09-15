# Halo Render Protocol v1

HRP is a compact payload protocol. It is transported using the official Brilliant BLE data-message framing; HRP does not replace BLE chunking or ACK handling.

## Envelope

Every complete HRP payload begins with:

| Bytes | Field |
|---:|---|
| 4 | ASCII `HRP1` |
| 1 | flags (reserved; must be zero in v1) |
| 2 | command count, big-endian |
| n | commands |

The outer Brilliant message code identifies the HRP application. The official outer message length remains the authoritative payload boundary.

## Commands

All integers are unsigned big-endian unless stated otherwise. Coordinates use the engine's 0-indexed scene coordinates; device adapters translate to the Halo Lua coordinate convention.

| Opcode | Payload | Meaning |
|---:|---|---|
| `0x01` | `R G B` | Clear display |
| `0x02` | `brightness:u8` | Set brightness |
| `0x03` | `x:u16 y:u16 color:u24` | Pixel |
| `0x04` | `x0:u16 y0:u16 x1:u16 y1:u16 color:u24` | Line |
| `0x05` | `x:u16 y:u16 w:u16 h:u16 color:u24 filled:u8` | Rectangle |
| `0x06` | `cx:u16 cy:u16 r:u16 color:u24 filled:u8` | Circle |
| `0x07` | `count:u8 points:(x:u16,y:u16)* color:u24` | Polygon |
| `0x08` | `font:u8 size:u8 scale:u8` | Set font |
| `0x09` | `x:u16 y:u16 color:u24 len:u16 utf8[len]` | Text |
| `0x0A` | `id:u16 w:u16 h:u16 compressed:u8 bpp:u8 colors:u8 palette[colors*3] pixels[remaining]` | Define/replace sprite resource |
| `0x0B` | `id:u16 x:u16 y:u16 offset:u8` | Draw retained sprite |
| `0x0C` | `id:u16` | Release sprite resource |
| `0x0D` | `x:u16 y:u16 w:u16 h:u16` | Begin dirty region (optimization hint) |
| `0x0E` | none | End frame |
| `0x0F` | `client_features:u32` | Capability/feature handshake |
| `0x10` | `id:u16 key_len:u8 key:utf8[key_len]` | Define sprite resource from the device file cache (`spr_<key>`); errors the frame on cache miss |

Payload lengths are derived from the opcode and fixed/variable fields. Unknown opcodes are rejected for the frame; malformed lengths reject the frame without drawing it.

## Sprite file cache

The runtime persists packed sprite assets as `spr_<key>` files (hex-encoded,
bounded at 32 entries) so repeat presentations can reference them with opcode
`0x10` instead of carrying the pixels. Two outer message codes support this:

| Code | Direction | Payload | Meaning |
|---:|---|---|---|
| `0x61` | host→dev | `key_len:u8 key:utf8[key_len] asset[remaining]` | `SPRITE_STORE` — persist the packed asset under `spr_<key>`; idempotent |
| `0x73` | dev→host | `key:utf8` | `SPRITE_STORED` — acknowledge a successful store |

Keys must match `[A-Za-z0-9_-]{1,64}`. A failed store or a cached-define miss
reports a device `ERROR` (0x71); hosts should ensure-store before sending a
frame that references a key, and fall back to opcode `0x0A` when the runtime
does not advertise the `spritecache` capability.

## Compatibility modes

### Stock-compatible

The host sends one or more HRP payloads through the official data-message framing and existing display APIs. The exact firmware reassembly helper is device/SDK supplied and is not vendored in this repository. No custom firmware is required for the stock-compatible path.

### Optimized runtime

The host uploads `lua/he_runtime.lua`, then sends the same HRP payload through the same outer message framing. The runtime parses commands directly. It may reduce Lua source parsing and command overhead, but it must implement only APIs available in the target firmware.

## Limits

- The host must reject HRP payloads above the active profile's `max_hrp_message_bytes` and the official 65,535-byte outer limit.
- A sprite resource must respect the active asset and retained-resource budgets.
- Polygon count must not exceed 64.
- Text is UTF-8 and must fit the declared uint16 length; host-side byte length, not character count, is used.
- No HRP command provides alpha, rotation, layers, double buffering, or arbitrary polygon fill.

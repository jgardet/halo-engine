"""Hardware-bounded Halo Render Protocol v1 encoder and decoder."""

from __future__ import annotations

import struct
from dataclasses import dataclass
from typing import Iterable

from .colors import parse_color
from .limits import STOCK_HALO, validate_hrp_size

MAGIC = b"HRP1"
VERSION_FLAGS = 0
HRP_CODE = 0x60
# host→dev: persist a packed sprite asset under `spr_<key>` for cached defines.
SPRITE_STORE = 0x61
# dev→host: acknowledges SPRITE_STORE; payload is the stored key.
SPRITE_STORED = 0x73


def sprite_store_payload(key: str, asset: bytes) -> bytes:
    """`SPRITE_STORE` message payload: `[key_len u8][key utf8][packed asset]`."""
    key_bytes = key.encode("utf-8")
    if not 1 <= len(key_bytes) <= 64:
        raise ValueError("sprite cache key must be 1..64 bytes")
    return bytes((len(key_bytes),)) + key_bytes + asset

CLEAR = 0x01
BRIGHTNESS = 0x02
PIXEL = 0x03
LINE = 0x04
RECT = 0x05
CIRCLE = 0x06
POLYGON = 0x07
SET_FONT = 0x08
TEXT = 0x09
SPRITE_DEFINE = 0x0A
SPRITE_DRAW = 0x0B
SPRITE_RELEASE = 0x0C
SPRITE_DEFINE_CACHED = 0x10
DIRTY_REGION = 0x0D
END_FRAME = 0x0E
FEATURES = 0x0F


def _u16(n: int) -> bytes:
    if not 0 <= n <= 0xFFFF:
        raise ValueError(f"u16 out of range: {n}")
    return struct.pack(">H", n)


def _color(color: str | int) -> bytes:
    return parse_color(color).to_bytes(3, "big")


def _command(opcode: int, payload: bytes) -> bytes:
    if not 0 <= opcode <= 255:
        raise ValueError("opcode must fit in one byte")
    if len(payload) > 0xFFFF:
        raise ValueError("command payload exceeds uint16 length")
    return bytes((opcode,)) + struct.pack(">H", len(payload)) + payload


@dataclass(frozen=True)
class HrpFrame:
    commands: tuple[bytes, ...]
    flags: int = VERSION_FLAGS

    def encode(self) -> bytes:
        if self.flags != VERSION_FLAGS:
            raise ValueError("unsupported HRP v1 flags")
        payload = MAGIC + bytes((self.flags,)) + _u16(len(self.commands)) + b"".join(self.commands)
        validate_hrp_size(len(payload), STOCK_HALO)
        return payload


class HrpBuilder:
    def __init__(self, max_bytes: int = STOCK_HALO.max_hrp_message_bytes):
        self._commands: list[bytes] = []
        self.max_bytes = max_bytes

    def add(self, opcode: int, payload: bytes) -> "HrpBuilder":
        self._commands.append(_command(opcode, payload))
        self._check_size()
        return self

    def clear(self, color: str | int = 0) -> "HrpBuilder":
        return self.add(CLEAR, _color(color))

    def brightness(self, value: int) -> "HrpBuilder":
        if not 0 <= value <= 100:
            raise ValueError("brightness must be 0..100")
        return self.add(BRIGHTNESS, bytes((value,)))

    def pixel(self, x: int, y: int, color: str | int) -> "HrpBuilder":
        return self.add(PIXEL, _u16(x) + _u16(y) + _color(color))

    def line(self, x0: int, y0: int, x1: int, y1: int, color: str | int) -> "HrpBuilder":
        return self.add(LINE, _u16(x0) + _u16(y0) + _u16(x1) + _u16(y1) + _color(color))

    def rect(self, x: int, y: int, w: int, h: int, color: str | int, filled: bool = False) -> "HrpBuilder":
        return self.add(RECT, _u16(x) + _u16(y) + _u16(w) + _u16(h) + _color(color) + bytes((filled,)))

    def circle(self, cx: int, cy: int, r: int, color: str | int, filled: bool = False) -> "HrpBuilder":
        return self.add(CIRCLE, _u16(cx) + _u16(cy) + _u16(r) + _color(color) + bytes((filled,)))

    def polygon(self, points: Iterable[tuple[int, int]], color: str | int) -> "HrpBuilder":
        points = tuple(points)
        if not 3 <= len(points) <= 64:
            raise ValueError("Halo supports 3..64 polygon points")
        payload = bytes((len(points),)) + b"".join(_u16(x) + _u16(y) for x, y in points) + _color(color)
        return self.add(POLYGON, payload)

    def set_font(self, font: int, size: int, scale: int = 1) -> "HrpBuilder":
        if font not in (0, 1) or size <= 0 or size > 255 or size % 8 or not 0 < scale <= 255:
            raise ValueError("invalid Halo font parameters")
        return self.add(SET_FONT, bytes((font, size, scale)))

    def text(self, x: int, y: int, text: str, color: str | int = 0xFFFFFF) -> "HrpBuilder":
        encoded = text.encode("utf-8")
        return self.add(TEXT, _u16(x) + _u16(y) + _color(color) + _u16(len(encoded)) + encoded)

    def sprite_define(self, sprite_id: int, asset: bytes) -> "HrpBuilder":
        if not 0 <= sprite_id <= 0xFFFF:
            raise ValueError("sprite id must fit in uint16")
        return self.add(SPRITE_DEFINE, _u16(sprite_id) + asset)

    def sprite_draw(self, sprite_id: int, x: int, y: int, offset: int = 0) -> "HrpBuilder":
        if not 0 <= offset <= 255:
            raise ValueError("palette offset must fit in uint8")
        return self.add(SPRITE_DRAW, _u16(sprite_id) + _u16(x) + _u16(y) + bytes((offset,)))

    def sprite_release(self, sprite_id: int) -> "HrpBuilder":
        return self.add(SPRITE_RELEASE, _u16(sprite_id))

    def sprite_define_cached(self, sprite_id: int, key: str) -> "HrpBuilder":
        """Define sprite `sprite_id` from a device-cached asset (`spr_<key>`
        written by a prior SPRITE_STORE message)."""
        key_bytes = key.encode("utf-8")
        if not 0 <= sprite_id <= 0xFFFF:
            raise ValueError("sprite id must fit in uint16")
        if not 1 <= len(key_bytes) <= 64:
            raise ValueError("sprite cache key must be 1..64 bytes")
        return self.add(SPRITE_DEFINE_CACHED, _u16(sprite_id) + bytes((len(key_bytes),)) + key_bytes)

    def dirty_region(self, x: int, y: int, w: int, h: int) -> "HrpBuilder":
        return self.add(DIRTY_REGION, _u16(x) + _u16(y) + _u16(w) + _u16(h))

    def end_frame(self) -> "HrpBuilder":
        return self.add(END_FRAME, b"")

    def features(self, value: int) -> "HrpBuilder":
        if not 0 <= value <= 0xFFFFFFFF:
            raise ValueError("features must fit in uint32")
        return self.add(FEATURES, struct.pack(">I", value))

    def build(self) -> bytes:
        return HrpFrame(tuple(self._commands)).encode()

    def _check_size(self) -> None:
        size = 7 + sum(len(c) for c in self._commands)
        if size > self.max_bytes:
            raise ValueError(f"HRP frame is {size} bytes; limit is {self.max_bytes}")


def decode_frame(data: bytes) -> list[tuple[int, bytes]]:
    """Validate and decode an HRP frame into opcode/payload pairs."""
    if len(data) < 7 or data[:4] != MAGIC or data[4] != VERSION_FLAGS:
        raise ValueError("invalid HRP v1 header")
    count = struct.unpack_from(">H", data, 5)[0]
    pos = 7
    commands: list[tuple[int, bytes]] = []
    for _ in range(count):
        if pos + 3 > len(data):
            raise ValueError("truncated HRP command header")
        opcode = data[pos]
        length = struct.unpack_from(">H", data, pos + 1)[0]
        pos += 3
        if pos + length > len(data):
            raise ValueError("truncated HRP command payload")
        commands.append((opcode, data[pos : pos + length]))
        pos += length
    if pos != len(data):
        raise ValueError("trailing bytes in HRP frame")
    return commands

"""Sprite packing for Halo indexed bitmaps."""

from __future__ import annotations

import base64
import io
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image


@dataclass
class SpriteAsset:
    width: int
    height: int
    bpp: int
    num_colors: int
    palette_data: bytes
    pixel_data: bytes

    @property
    def color_format(self) -> int:
        """Halo color_format value: 2, 4, or 16."""
        if self.bpp == 1:
            return 2
        if self.bpp == 2:
            return 4
        return 16

    def packed(self, compress: bool = False) -> bytes:
        """Pack into the on-wire TxSprite format."""
        return pack_sprite_asset(self, compress=compress)


def pack_sprite(
    src: str,
    width: int | None = None,
    height: int | None = None,
    bpp: int = 4,
) -> SpriteAsset:
    """Create a SpriteAsset from an image path or base64 data URI."""
    img = _load_image(src)

    # Convert RGBA to RGB on a black background before quantization
    if img.mode == "RGBA":
        bg = Image.new("RGB", img.size, (0, 0, 0))
        bg.paste(img, mask=img.split()[3])
        img = bg
    elif img.mode != "RGB":
        img = img.convert("RGB")

    if width is not None and height is not None:
        img = img.resize((width, height), Image.Resampling.LANCZOS)

    max_colors = 2 ** bpp
    if img.mode != "P" or img.getcolors() is None or len(img.getcolors()) > max_colors:
        img = img.quantize(colors=max_colors, method=Image.Quantize.MEDIANCUT)

    # Ensure palette is 3*max_colors bytes, RGB, with index 0 = black (transparent)
    raw_palette = list(img.getpalette()[: max_colors * 3])
    raw_palette.extend([0] * (max_colors * 3 - len(raw_palette)))

    # Pillow quantize tends to put darkest color at index 0, but we force index 0 to black
    # and shift the old index-0 to the end. This mirrors the SDK palette fix.
    if max_colors > 1:
        old_first = raw_palette[0:3]
        old_last = raw_palette[(max_colors - 1) * 3 : max_colors * 3]
        raw_palette[0:3] = old_last
        raw_palette[(max_colors - 1) * 3 : max_colors * 3] = old_first

    palette = bytes(raw_palette[: max_colors * 3])

    pixels = np.array(img, dtype=np.uint8)
    if max_colors > 1:
        # Apply the same index swap we did on the palette
        mask0 = pixels == 0
        mask15 = pixels == max_colors - 1
        pixels[mask0 & mask15] = 255  # not possible
        pixels[mask0] = max_colors - 1
        pixels[mask15] = 0

    # Force index 0 to black in the palette (transparent)
    palette_list = list(palette)
    palette_list[0:3] = [0, 0, 0]
    palette = bytes(palette_list)

    return SpriteAsset(
        width=img.width,
        height=img.height,
        bpp=bpp,
        num_colors=max_colors,
        palette_data=palette,
        pixel_data=pixels.tobytes(),
    )


def _load_image(src: str) -> Image.Image:
    """Load from a file path or a base64 data URI."""
    if src.startswith("data:"):
        match = re.match(r"data:image/\w+;base64,(.*)", src)
        if not match:
            raise ValueError("Only base64 data URIs are supported for sprite src")
        data = base64.b64decode(match.group(1))
        return Image.open(io.BytesIO(data))

    path = Path(src)
    if not path.exists():
        raise FileNotFoundError(f"Sprite source not found: {src}")
    return Image.open(path)


def pack_bits(pixels: bytes, bpp: int) -> bytes:
    """Pack a flat array of indices into the Halo bitmap byte format."""
    if bpp == 1:
        return _pack_1bit(pixels)
    if bpp == 2:
        return _pack_2bit(pixels)
    if bpp == 4:
        return _pack_4bit(pixels)
    raise ValueError(f"bpp must be 1, 2, or 4; got {bpp}")


def _pack_1bit(data: bytes) -> bytes:
    arr = np.frombuffer(data, dtype=np.uint8)
    return np.packbits(arr).tobytes()


def _pack_2bit(data: bytes) -> bytes:
    arr = np.frombuffer(data, dtype=np.uint8)
    out = np.zeros((len(arr) + 3) // 4, dtype=np.uint8)
    for i, v in enumerate(arr):
        byte_idx = i // 4
        bit_offset = (3 - (i % 4)) * 2
        out[byte_idx] |= (v & 0x03) << bit_offset
    return out.tobytes()


def _pack_4bit(data: bytes) -> bytes:
    arr = np.frombuffer(data, dtype=np.uint8)
    out = np.zeros((len(arr) + 1) // 2, dtype=np.uint8)
    for i, v in enumerate(arr):
        byte_idx = i // 2
        bit_offset = (1 - (i % 2)) * 4
        out[byte_idx] |= (v & 0x0F) << bit_offset
    return out.tobytes()


def pack_sprite_asset(sprite: SpriteAsset, compress: bool = False) -> bytes:
    """Pack a sprite into the wire format expected by the device-side sprite library."""
    import struct

    packed_pixels = pack_bits(sprite.pixel_data, sprite.bpp)
    pixels = packed_pixels
    compressed = 0
    if compress:
        import lz4.frame

        candidate = lz4.frame.compress(packed_pixels)
        if len(candidate) < len(packed_pixels):
            pixels = candidate
            compressed = 1
    header = struct.pack(
        ">HHBBB",
        sprite.width,
        sprite.height,
        compressed,
        sprite.bpp,
        sprite.num_colors,
    )
    return header + sprite.palette_data + pixels


def sprite_to_lua_args(sprite: SpriteAsset, palette_offset: int = 0) -> str:
    """Return the inline Lua arguments for a small sprite (for REPL mode)."""
    from .compiler import _hex_bytes

    pixel_str = _hex_bytes(pack_bits(sprite.pixel_data, sprite.bpp))
    palette_str = _hex_bytes(sprite.palette_data)
    fmt = sprite.color_format
    return (
        f"{sprite.width},{fmt},{palette_offset},"
        f'"{pixel_str}",{{palette_data="{palette_str}"}}'
    )


def _cli() -> None:
    import argparse
    import base64
    import sys

    parser = argparse.ArgumentParser(description="Pack an image into a Halo sprite")
    parser.add_argument("--src", required=True)
    parser.add_argument("--width", type=int, default=None)
    parser.add_argument("--height", type=int, default=None)
    parser.add_argument("--bpp", type=int, default=4)
    args = parser.parse_args()

    try:
        sprite = pack_sprite(args.src, args.width, args.height, args.bpp)
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"{sprite.width} {sprite.height} {sprite.bpp} {sprite.num_colors} "
          f"{base64.b64encode(sprite.palette_data).decode('ascii')} "
          f"{base64.b64encode(sprite.pixel_data).decode('ascii')}")


if __name__ == "__main__":
    _cli()

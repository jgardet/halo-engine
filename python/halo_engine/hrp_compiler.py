"""Compile HSD scenes into HRP v1 frames."""

from __future__ import annotations

from typing import Any
import zlib

from .compiler import _estimated_height, _estimated_width
from .hrp import HrpBuilder
from .hsd_validator import HsdValidator
from .limits import STOCK_HALO, validate_asset_size
from .sprite import pack_sprite


def compile_scene_hrp(scene: dict[str, Any], max_bytes: int = STOCK_HALO.max_hrp_message_bytes, lz4_sprites: bool = False) -> bytes:
    HsdValidator().validate(scene)
    root = scene.get("scene", {})
    builder = HrpBuilder(max_bytes=max_bytes)
    defined: set[int] = set()
    builder.clear(root.get("bg", "#000000"))
    if root.get("brightness") is not None:
        builder.brightness(int(root["brightness"]))
    for child in root.get("children", []):
        _element(child, builder, 0, 0, defined, lz4_sprites)
    builder.end_frame()
    return builder.build()


def _element(el: dict[str, Any], b: HrpBuilder, dx: int, dy: int, defined: set[int], lz4_sprites: bool) -> None:
    if not el.get("visible", True):
        return
    typ = el.get("type", "").lower()
    if typ in ("group", "row", "column"):
        ox, oy = dx + int(el.get("x", 0)), dy + int(el.get("y", 0))
        current = ox if typ == "row" else oy
        spacing = int(el.get("spacing", 0))
        for child in el.get("children", []):
            if typ == "row":
                _element(child, b, current, oy, defined, lz4_sprites)
                current += _estimated_width(child) + spacing
            elif typ == "column":
                _element(child, b, ox, current, defined, lz4_sprites)
                current += _estimated_height(child) + spacing
            else:
                _element(child, b, ox, oy, defined, lz4_sprites)
        return

    color = el.get("color", "#FFFFFF")
    if typ == "text":
        b.set_font(int(el.get("font", 0)), int(el.get("size", 8)), int(el.get("scale", 1)))
        b.text(int(el.get("x", 0)) + dx, int(el.get("y", 0)) + dy, str(el.get("text", "")), color)
    elif typ == "rect":
        b.rect(int(el.get("x", 0)) + dx, int(el.get("y", 0)) + dy, int(el.get("w", 0)), int(el.get("h", 0)), color, bool(el.get("filled", False)))
    elif typ == "circle":
        b.circle(int(el.get("cx", 0)) + dx, int(el.get("cy", 0)) + dy, int(el.get("r", 0)), color, bool(el.get("filled", False)))
    elif typ == "line":
        b.line(int(el.get("x0", 0)) + dx, int(el.get("y0", 0)) + dy, int(el.get("x1", 0)) + dx, int(el.get("y1", 0)) + dy, color)
    elif typ == "polygon":
        b.polygon(((int(x) + dx, int(y) + dy) for x, y in el.get("points", [])), color)
    elif typ in ("point", "pixel"):
        b.pixel(int(el.get("x", 0)) + dx, int(el.get("y", 0)) + dy, color)
    elif typ == "sprite":
        if int(el.get("scale_x", 1)) != 1 or int(el.get("scale_y", 1)) != 1:
            raise ValueError("HRP sprites do not support scaling")
        asset = pack_sprite(str(el["src"]), el.get("w"), el.get("h"), int(el.get("bpp", 4)))
        packed = asset.packed(compress=lz4_sprites)
        validate_asset_size(packed.__len__())
        sprite_id = int(el.get("resource_id", 1 + zlib.crc32(str(el["src"]).encode()) % 65534))
        if sprite_id not in defined:
            b.sprite_define(sprite_id, packed)
            defined.add(sprite_id)
        b.sprite_draw(sprite_id, int(el.get("x", 0)) + dx, int(el.get("y", 0)) + dy, int(el.get("palette_offset", 0)))
    else:
        raise ValueError(f"Unknown element type: {typ}")

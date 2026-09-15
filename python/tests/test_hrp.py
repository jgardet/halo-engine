"""HRP wire-format and hardware-limit tests."""

import pytest

from halo_engine.hrp import HrpBuilder, decode_frame
from halo_engine.hrp_compiler import compile_scene_hrp
from halo_engine.limits import HardwareLimitError, validate_lua_size


def test_hrp_round_trip():
    payload = HrpBuilder().clear("#000000").set_font(1, 16).text(10, 20, "Hi").end_frame().build()
    commands = decode_frame(payload)
    assert payload[:5] == b"HRP1\x00"
    assert [opcode for opcode, _ in commands] == [0x01, 0x08, 0x09, 0x0E]
    assert commands[2][1][-2:] == b"Hi"


def test_hrp_matches_big_endian_layout():
    payload = HrpBuilder().pixel(1, 2, "#123456").build()
    commands = decode_frame(payload)
    assert commands == [(0x03, b"\x00\x01\x00\x02\x12\x34\x56")]


def test_hrp_sprite_release_dirty_region_features_wire_format():
    payload = (
        HrpBuilder()
        .sprite_release(1)
        .dirty_region(4, 5, 6, 7)
        .features(0xAABBCCDD)
        .build()
    )
    commands = decode_frame(payload)
    assert commands[0] == (0x0C, b"\x00\x01")
    assert commands[1] == (0x0D, b"\x00\x04\x00\x05\x00\x06\x00\x07")
    assert commands[2] == (0x0F, b"\xaa\xbb\xcc\xdd")


def test_polygon_limit():
    with pytest.raises(ValueError, match="64"):
        HrpBuilder().polygon([(0, 0)] * 65, "#fff")


def test_lua_source_is_hardware_bounded():
    with pytest.raises(HardwareLimitError):
        validate_lua_size("x" * 4097)


def test_hrp_row_offsets_are_applied_once():
    scene = {"scene": {"children": [{"type": "row", "x": 10, "y": 20, "children": [{"type": "point", "x": 1, "y": 2}]}]}}
    commands = decode_frame(compile_scene_hrp(scene))
    assert (0x03, b"\x00\x0b\x00\x16\xff\xff\xff") in commands


def test_scene_compiles_to_binary_hrp():
    scene = {"scene": {"bg": "#000000", "children": [{"type": "rect", "x": 1, "y": 2, "w": 3, "h": 4, "filled": True}]}}
    payload = compile_scene_hrp(scene)
    assert decode_frame(payload)[-1][0] == 0x0E


def test_pack_sprite_asset_lz4_flag_and_roundtrip():
    import lz4.frame

    from halo_engine.sprite import SpriteAsset, pack_bits, pack_sprite_asset

    # Highly compressible pixels: a solid-color 4bpp sprite.
    sprite = SpriteAsset(
        width=64,
        height=64,
        bpp=4,
        num_colors=2,
        palette_data=bytes((0, 0, 0, 255, 255, 255)),
        pixel_data=bytes([1] * (64 * 64)),
    )
    plain = pack_sprite_asset(sprite)
    packed = pack_sprite_asset(sprite, compress=True)
    # compressed flag is asset byte 4: [w u16][h u16][flag][bpp][colors]
    assert plain[4] == 0
    assert packed[4] == 1
    assert len(packed) < len(plain)
    # Header + palette (7 + 6 bytes) are untouched except the flag byte;
    # the pixel tail is an LZ4 frame that decodes back to packed indices.
    assert plain[:4] == packed[:4]
    assert plain[5:13] == packed[5:13]
    assert lz4.frame.decompress(packed[13:]) == plain[13:] == pack_bits(sprite.pixel_data, sprite.bpp)


def test_pack_sprite_asset_compress_keeps_incompressible_pixels():
    from halo_engine.sprite import SpriteAsset, pack_sprite_asset

    # Incompressible pixel data falls back to the uncompressed tail.
    sprite = SpriteAsset(
        width=4,
        height=4,
        bpp=4,
        num_colors=2,
        palette_data=bytes((0, 0, 0, 255, 255, 255)),
        pixel_data=bytes(range(16)),
    )
    packed = pack_sprite_asset(sprite, compress=True)
    assert packed[4] == 0
    assert packed == pack_sprite_asset(sprite)


def test_compile_scene_hrp_lz4_sprites():
    import base64
    import io

    import lz4.frame
    from PIL import Image

    img = Image.new("RGB", (16, 16), (255, 255, 255))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    uri = "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()
    scene = {"scene": {"children": [{"type": "sprite", "src": uri, "x": 0, "y": 0, "bpp": 4}]}}
    commands = decode_frame(compile_scene_hrp(scene, lz4_sprites=True))
    define = next(p for op, p in commands if op == 0x0A)
    # spriteDefine payload: [id u16][asset]; flag sits at asset byte 4.
    assert define[6] == 1
    num_colors = define[8]
    lz4.frame.decompress(define[2 + 7 + num_colors * 3:])

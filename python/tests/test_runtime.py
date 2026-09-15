"""Execute the binary HRP runtime against the official Halo emulator."""

from pathlib import Path
import shutil
import time

from halo_emulator import HaloEmulator
from halo_engine.hrp import HrpBuilder, HRP_CODE

PROJECT_LUA = Path(__file__).parents[2] / "lua" / "he_runtime.lua"


def _message(code: int, payload: bytes) -> bytes:
    return bytes((code, len(payload) >> 8, len(payload) & 0xFF)) + payload


MICROPHONE_START = 0x30
MICROPHONE_STOP = 0x31
BATTERY_CODE = 0x72
AUDIO_CHUNK = 0x05
AUDIO_FINAL = 0x06
IMU_CODE = 0x0A
SOUND_PLAY = 0x50
SYSTEM = 0x51
SET_TIME = 0x52
IMU_READ = 0x53
TAP_CONFIG = 0x54
ERROR_CODE = 0x71
SPRITE_STORE = 0x61
SPRITE_STORED = 0x73


def test_runtime_executes_hrp(tmp_path):
    shutil.copy2(PROJECT_LUA, tmp_path / "main.lua")
    lines = []
    emu = HaloEmulator(sandbox_dir=tmp_path, print_handler=lines.append)
    emu.start("main.lua")
    try:
        time.sleep(0.1)
        payload = HrpBuilder().clear("#000000").pixel(10, 12, "#00FF00").text(20, 20, "OK").end_frame().build()
        emu.inject_bluetooth_data(_message(HRP_CODE, payload))
        time.sleep(0.1)
        pixel = emu.get_framebuffer().getpixel((10, 12))
        assert pixel[1] > pixel[0]
        sent = emu.get_bluetooth_sent()
        assert b"\x01\x00\x00" in sent
        assert any(item.startswith(b"\x70HRP1;") for item in sent)
        emu.inject_button_single()
        emu.inject_imu_tap("double")
        time.sleep(0.1)
        sent = emu.get_bluetooth_sent()
        assert b"\x0b\x01" in sent
        assert b"\x09\x02" in sent
        assert any("ready" in line for line in lines)
    finally:
        emu.stop()


def test_runtime_streams_microphone_and_reports_battery(tmp_path):
    shutil.copy2(PROJECT_LUA, tmp_path / "main.lua")
    emu = HaloEmulator(sandbox_dir=tmp_path)
    emu.start("main.lua")
    try:
        time.sleep(0.1)
        # gain=10 (0 dB), aec=1, voice=0
        emu.inject_bluetooth_data(_message(MICROPHONE_START, bytes((10, 1, 0))))
        time.sleep(0.05)
        assert any(item.startswith(bytes((AUDIO_CHUNK,))) for item in emu.get_bluetooth_sent()) is False

        emu.inject_microphone_data(b"\x01\x02\x03\x04\x05\x06")
        time.sleep(0.05)
        sent = emu.get_bluetooth_sent()
        assert any(item == bytes((AUDIO_CHUNK,)) + b"\x01\x02\x03\x04\x05\x06" for item in sent)

        emu.inject_bluetooth_data(_message(MICROPHONE_STOP, b""))
        time.sleep(0.05)
        sent = emu.get_bluetooth_sent()
        assert any(item.startswith(bytes((AUDIO_FINAL,))) for item in sent)

        emu.get_bluetooth_sent()  # refresh/clear observation helper if present
        emu.inject_bluetooth_data(_message(BATTERY_CODE, b""))
        time.sleep(0.05)
        sent = emu.get_bluetooth_sent()
        assert any(item.startswith(bytes((BATTERY_CODE,))) for item in sent)
    finally:
        emu.stop()


def _errors(sent: list[bytes]) -> list[bytes]:
    return [item for item in sent if item.startswith(bytes((ERROR_CODE,)))]


def test_runtime_sound_and_unknown_preset(tmp_path):
    shutil.copy2(PROJECT_LUA, tmp_path / "main.lua")
    emu = HaloEmulator(sandbox_dir=tmp_path)
    emu.start("main.lua")
    try:
        time.sleep(0.1)
        # flags=0 then preset name — valid presets must not error.
        emu.inject_bluetooth_data(_message(SOUND_PLAY, b"\x00blip"))
        time.sleep(0.05)
        assert not _errors(emu.get_bluetooth_sent())

        emu.inject_bluetooth_data(_message(SOUND_PLAY, b"\x00bogus"))
        time.sleep(0.05)
        assert any(b"unknown sound preset" in e for e in _errors(emu.get_bluetooth_sent()))
    finally:
        emu.stop()


def test_runtime_system_display_power_save(tmp_path):
    shutil.copy2(PROJECT_LUA, tmp_path / "main.lua")
    emu = HaloEmulator(sandbox_dir=tmp_path)
    emu.start("main.lua")
    try:
        time.sleep(0.1)
        # SYS_DISPLAY_SLEEP / SYS_DISPLAY_WAKE take no argument.
        emu.inject_bluetooth_data(_message(SYSTEM, bytes((0x00,))))
        time.sleep(0.05)
        emu.inject_bluetooth_data(_message(SYSTEM, bytes((0x01,))))
        time.sleep(0.05)
        # SYS_STAY_AWAKE takes a single flag byte.
        emu.inject_bluetooth_data(_message(SYSTEM, bytes((0x04, 0x01))))
        time.sleep(0.05)
        # An unknown subcommand reports a device error.
        emu.inject_bluetooth_data(_message(SYSTEM, bytes((0x7F,))))
        time.sleep(0.05)
        errors = _errors(emu.get_bluetooth_sent())
        assert len(errors) == 1
        assert b"unknown system subcommand" in errors[0]
    finally:
        emu.stop()


def test_runtime_set_time_and_imu(tmp_path):
    shutil.copy2(PROJECT_LUA, tmp_path / "main.lua")
    emu = HaloEmulator(sandbox_dir=tmp_path)
    emu.start("main.lua")
    try:
        time.sleep(0.1)
        # u32 epoch + optional "+HH:MM" zone.
        emu.inject_bluetooth_data(_message(SET_TIME, bytes((0x65, 0x53, 0x90, 0x00)) + b"+02:00"))
        time.sleep(0.05)
        # A short payload reports a device error.
        emu.inject_bluetooth_data(_message(SET_TIME, b"\x01\x02"))
        time.sleep(0.05)
        assert any(b"set_time payload too short" in e for e in _errors(emu.get_bluetooth_sent()))

        emu.set_imu_direction(1.5, -2.0, 0.0)
        emu.set_imu_raw((12.0, -3.0, 48.0), (1.0, -2.0, 1001.0))
        emu.inject_bluetooth_data(_message(IMU_READ, b""))
        time.sleep(0.05)
        imu_msgs = [m for m in emu.get_bluetooth_sent() if m.startswith(bytes((IMU_CODE,)))]
        assert imu_msgs, "expected an IMU response"
        fields = imu_msgs[-1][1:].decode().split(";")
        assert abs(float(fields[0]) - 1.5) < 0.01
        assert abs(float(fields[1]) + 2.0) < 0.01
        assert abs(float(fields[4]) - 48.0) < 0.1
        assert abs(float(fields[7]) - 1001.0) < 0.1
    finally:
        emu.stop()


def test_runtime_tap_config(tmp_path):
    shutil.copy2(PROJECT_LUA, tmp_path / "main.lua")
    emu = HaloEmulator(sandbox_dir=tmp_path)
    emu.start("main.lua")
    try:
        time.sleep(0.1)
        # flags=0x01 (mode) + mode index 0 (sensitive); then flags=0x04 + u16 threshold.
        emu.inject_bluetooth_data(_message(TAP_CONFIG, bytes((0x01, 0x00))))
        time.sleep(0.05)
        emu.inject_bluetooth_data(_message(TAP_CONFIG, bytes((0x04, 0x00, 0x0C))))
        time.sleep(0.05)
        assert not _errors(emu.get_bluetooth_sent())

        # A bad mode index reports a device error.
        emu.inject_bluetooth_data(_message(TAP_CONFIG, bytes((0x01, 0x09))))
        time.sleep(0.05)
        assert any(b"bad tap mode" in e for e in _errors(emu.get_bluetooth_sent()))
    finally:
        emu.stop()


def _hrp(*commands: tuple[int, bytes]) -> bytes:
    body = b"".join(
        bytes((opcode, len(payload) >> 8, len(payload) & 0xFF)) + payload
        for opcode, payload in commands
    )
    return b"HRP1" + b"\x00" + bytes((0, len(commands))) + body


def test_runtime_compressed_sprite(tmp_path):
    import lz4.frame

    shutil.copy2(PROJECT_LUA, tmp_path / "main.lua")
    emu = HaloEmulator(sandbox_dir=tmp_path)
    emu.start("main.lua")
    try:
        time.sleep(0.1)
        # The runtime should advertise the lz4 capability when
        # frame.compression exists.
        sent = emu.get_bluetooth_sent()
        assert any(item.startswith(b"\x70HRP1;") and b"lz4" in item for item in sent)

        # 8x8, 1bpp sprite, all pixels index 1 -> packed bits are 0xFF * 8.
        # Palette: index 0 black, index 1 white.
        packed_pixels = b"\xFF" * 8
        palette = bytes((0, 0, 0, 255, 255, 255))
        asset = (
            bytes((0, 8, 0, 8, 1, 1, 2)) + palette + lz4.frame.compress(packed_pixels)
        )
        define = bytes((0, 1)) + asset           # spriteDefine id=1
        draw = bytes((0, 1, 0, 10, 0, 10, 0))    # spriteDraw id=1 at (10,10)
        emu.inject_bluetooth_data(_message(HRP_CODE, _hrp((0x0A, define), (0x0B, draw))))
        time.sleep(0.1)
        assert not _errors(emu.get_bluetooth_sent())
        r, g, b = emu.get_framebuffer().getpixel((10, 10))[:3]
        assert (r, g, b) == (255, 255, 255)

        # Corrupt LZ4 data reports a device error.
        bad = bytes((0, 2)) + bytes((0, 8, 0, 8, 1, 1, 2)) + palette + b"not-lz4"
        emu.inject_bluetooth_data(_message(HRP_CODE, _hrp((0x0A, bad))))
        time.sleep(0.1)
        assert any(b"sprite decompress failed" in e for e in _errors(emu.get_bluetooth_sent()))
    finally:
        emu.stop()


def test_runtime_sprite_cache(tmp_path):
    """SPRITE_STORE persists a packed asset under spr_<key>; the cached
    define opcode (0x10) resolves it without carrying the pixels."""
    from halo_engine.hrp import sprite_store_payload

    shutil.copy2(PROJECT_LUA, tmp_path / "main.lua")
    emu = HaloEmulator(sandbox_dir=tmp_path)
    emu.start("main.lua")
    try:
        time.sleep(0.1)
        sent = emu.get_bluetooth_sent()
        assert any(b"spritecache" in item for item in sent)

        # 8x8, 1bpp, all pixels index 1 (white) — uncompressed.
        asset = (
            bytes((0, 8, 0, 8, 0, 1, 2))
            + bytes((0, 0, 0, 255, 255, 255))
            + b"\xFF" * 8
        )
        key = "testkey1"
        emu.inject_bluetooth_data(_message(SPRITE_STORE, sprite_store_payload(key, asset)))
        time.sleep(0.1)
        acks = [m for m in emu.get_bluetooth_sent() if m.startswith(bytes((SPRITE_STORED,)))]
        assert acks and acks[-1][1:] == key.encode()
        assert (tmp_path / f"spr_{key}").exists()

        # Cached define + draw renders without re-sending the pixels.
        cached = bytes((0, 5)) + bytes((len(key),)) + key.encode()
        draw = bytes((0, 5, 0, 20, 0, 20, 0))
        emu.inject_bluetooth_data(_message(HRP_CODE, _hrp((0x10, cached), (0x0B, draw))))
        time.sleep(0.1)
        assert not _errors(emu.get_bluetooth_sent())
        assert emu.get_framebuffer().getpixel((20, 20))[:3] == (255, 255, 255)

        # A cache miss reports a device error instead of drawing.
        miss = bytes((0, 6)) + bytes((4,)) + b"nope"
        emu.inject_bluetooth_data(_message(HRP_CODE, _hrp((0x10, miss))))
        time.sleep(0.1)
        assert any(b"cache miss" in e for e in _errors(emu.get_bluetooth_sent()))
    finally:
        emu.stop()


def test_runtime_status_query_replies_with_caps(tmp_path):
    """A host STATUS query must answer with the capability string so the
    connect probe can detect an already-running (autorun) runtime."""
    shutil.copy2(PROJECT_LUA, tmp_path / "main.lua")
    emu = HaloEmulator(sandbox_dir=tmp_path)
    emu.start("main.lua")
    try:
        time.sleep(0.1)
        boot = [m for m in emu.get_bluetooth_sent() if m.startswith(b"\x70HRP1;")]
        assert len(boot) == 1
        assert b";rt=" in boot[0]
        assert b";wake=" in boot[0]

        before = len(emu.get_bluetooth_sent())
        emu.inject_bluetooth_data(_message(0x70, b""))
        time.sleep(0.1)
        replies = [m for m in emu.get_bluetooth_sent()[before:] if m.startswith(b"\x70HRP1;")]
        assert len(replies) == 1
        assert b";rt=" in replies[0]
    finally:
        emu.stop()

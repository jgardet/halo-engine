"""Capture the nav HUD framebuffer from the emulator for visual review."""
import shutil
import struct
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "tests"))
from halo_emulator import HaloEmulator  # noqa: E402

PROJECT_LUA = Path(__file__).resolve().parent.parent / "lua" / "he_runtime.lua"
HUD_SET = 0x55


def _message(op: int, payload: bytes = b"") -> bytes:
    return bytes((op, len(payload) >> 8, len(payload) & 0xFF)) + payload


def _hud_set(bearing: int, distance: int, instruction: str) -> bytes:
    text = instruction.encode()
    return bytes((1,)) + struct.pack(">HH", bearing, distance) + text


def main() -> None:
    out_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd()
    out_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        shutil.copy2(PROJECT_LUA, tmp_path / "main.lua")
        emu = HaloEmulator(sandbox_dir=tmp_path)
        emu.start("main.lua")
        try:
            time.sleep(0.3)
            # Level device pointing north.
            emu.set_imu_direction(0.0, 0.0, 0.0)
            emu.set_imu_raw((50.0, 0.0, -40.0), (0.0, 0.0, 1000.0))
            time.sleep(1.0)

            # Maneuver due east, 350 m -> arrow points right.
            emu.inject_bluetooth_data(_message(HUD_SET, _hud_set(90, 350, "Turn right onto Rue de Lyon")))
            time.sleep(1.0)
            emu.get_framebuffer().save(out_dir / "nav_hud_turn_right.png")

            # User turns to face east -> arrow rotates up.
            emu.set_imu_raw((0.0, -50.0, -40.0), (0.0, 0.0, 1000.0))
            time.sleep(2.5)
            emu.get_framebuffer().save(out_dir / "nav_hud_facing_target.png")

            # New cue: arrive in 40 m behind -> tip down.
            emu.inject_bluetooth_data(_message(HUD_SET, _hud_set(270, 40, "Arrive at Hotel")))
            time.sleep(2.5)
            emu.get_framebuffer().save(out_dir / "nav_hud_arrive.png")
        finally:
            emu.stop()
    for p in sorted(out_dir.glob("nav_hud_*.png")):
        print(p)


if __name__ == "__main__":
    main()

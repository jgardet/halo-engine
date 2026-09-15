"""CLI for compiling HSD into binary HRP v1."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from .hrp_compiler import compile_scene_hrp


def main() -> None:
    parser = argparse.ArgumentParser(description="Compile HSD scene to hardware-bounded HRP")
    parser.add_argument("scene")
    parser.add_argument("--out", "-o", required=True)
    parser.add_argument("--lz4-sprites", action="store_true",
                        help="LZ4-compress sprite pixel data (runtime must advertise the lz4 capability)")
    args = parser.parse_args()
    with open(args.scene, encoding="utf-8") as f:
        payload = compile_scene_hrp(json.load(f), lz4_sprites=args.lz4_sprites)
    Path(args.out).write_bytes(payload)
    print(f"Compiled {args.scene} -> {args.out} ({len(payload)} bytes)")


if __name__ == "__main__":
    main()

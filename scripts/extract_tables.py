"""Extract the three static arrays from reference/linuxdoom-1.10/tables.c
into little-endian int32 binary files under src/main/resources/doom/render/.

Run once at project setup; regenerate only if tables.c changes (it won't —
it is a frozen reference file).
"""

from __future__ import annotations

import re
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "reference" / "linuxdoom-1.10" / "linuxdoom-1.10" / "tables.c"
OUT = ROOT / "src" / "main" / "resources" / "doom" / "render"
OUT.mkdir(parents=True, exist_ok=True)

TEXT = SRC.read_text()

# Each array is `<type> <name>[<size>] = { ... };`.
ARRAYS = [
    ("finetangent", 4096, "finetangent.dat"),
    ("finesine", 10240, "finesine.dat"),
    ("tantoangle", 2049, "tantoangle.dat"),
]


def extract(name: str) -> list[int]:
    # Grab everything between `<name>[...] = {` and the matching `};`.
    pattern = re.compile(rf"{name}\s*\[[^\]]*\]\s*=\s*\{{(.*?)\}}\s*;", re.DOTALL)
    m = pattern.search(TEXT)
    if not m:
        raise RuntimeError(f"array {name} not found in tables.c")
    body = m.group(1)
    # Split on commas, strip, drop empties.
    items = [tok.strip() for tok in body.split(",")]
    items = [tok for tok in items if tok]
    return [int(tok) for tok in items]


for name, expected, fname in ARRAYS:
    values = extract(name)
    assert len(values) == expected, f"{name}: got {len(values)}, want {expected}"
    # `angle_t` is unsigned; Python int is arbitrary — pack using signed layout
    # but accept values up to 2**32-1 by masking to 32 bits (bit-identical).
    packed = b"".join(struct.pack("<i", v if v < 0x80000000 else v - 0x100000000) for v in values)
    (OUT / fname).write_bytes(packed)
    print(f"{fname}: {len(values)} ints, {len(packed)} bytes")

print("done")

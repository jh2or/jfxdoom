"""Rebuild authored fixtures; does not import or run Doom renderer code."""
from pathlib import Path
import struct
import zlib

# -1 means transparent. Values are palette indices, not RGB colors.
PIXELS = [
    [-1, 17, 34, -1, 51],
    [68, 85, -1, 102, 119],
    [136, -1, -1, 153, 170],
    [-1, 187, 0, 204, -1],
    [221, -1, 238, -1, 255],
    [1, 2, 3, 4, 5],
    [-1, 6, -1, 7, -1],
]
ROOT = Path(__file__).parent
width, height = 5, 7
# Doom patch has signed origin offsets (2, 3).
header = struct.pack('<hhhh', width, height, 2, 3)
columns = []
for x in range(width):
    column = bytearray()
    y = 0
    while y < height:
        if PIXELS[y][x] == -1:
            y += 1
            continue
        top = y
        run = []
        while y < height and PIXELS[y][x] != -1:
            run.append(PIXELS[y][x])
            y += 1
        column.extend(bytes([top, len(run), 0, *run, 0]))
    columns.append(column + b'\xff')
offset = 8 + 4 * width
for column in columns:
    header += struct.pack('<I', offset)
    offset += len(column)
(ROOT / 'authored.patch').write_bytes(header + b''.join(columns))

# Independent reference composition in ordinary row-major pixels.
canvas = [[42] * 320 for _ in range(200)]
for left, top, flipped in [(0, 0, False), (2, 2, True), (315, 193, True)]:
    for y, row in enumerate(PIXELS):
        for x, index in enumerate(reversed(row) if flipped else row):
            if index != -1:
                canvas[top + y][left + x] = index

def chunk(tag, data):
    return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data))
# Truecolor grayscale encoding preserves exact palette-index values in RGB.
raw = b''.join(b'\0' + bytes(c for index in row for c in (index, index, index)) for row in canvas)
png = b'\x89PNG\r\n\x1a\n'
png += chunk(b'IHDR', struct.pack('>IIBBBBB', 320, 200, 8, 2, 0, 0, 0))
png += chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b'')
(ROOT / 'authored-patch-composite.png').write_bytes(png)

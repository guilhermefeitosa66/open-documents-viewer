"""A minimal Compound File (OLE2) writer for test fixtures: version 3,
512-byte sectors, every stream padded to 4096 bytes so no mini stream is
needed. Enough for olefile to read Word 97 and Excel 97 test files."""

import struct

SECTOR = 512
FREE, END, FATSECT, NOSTREAM = 0xFFFFFFFF, 0xFFFFFFFE, 0xFFFFFFFD, 0xFFFFFFFF


def _entry(name, kind, start, size, child=NOSTREAM, right=NOSTREAM):
    raw = (name + "\0").encode("utf-16-le")
    return (raw.ljust(64, b"\0") + struct.pack("<HBB", len(raw), kind, 1)
            + struct.pack("<III", NOSTREAM, right, child) + b"\0" * 16 + b"\0" * 4 + b"\0" * 16
            + struct.pack("<II", start, size) + b"\0" * 4)


def write(path, streams: dict[str, bytes]):
    names = list(streams)
    data = {n: streams[n].ljust(max(4096, len(streams[n])), b"\0") for n in names}
    sectors, fat, starts = [], [], {}
    for n in names:
        blob = data[n]
        count = (len(blob) + SECTOR - 1) // SECTOR
        starts[n] = len(sectors)
        for k in range(count):
            sectors.append(blob[k * SECTOR:(k + 1) * SECTOR].ljust(SECTOR, b"\0"))
            fat.append(len(sectors) if k < count - 1 else END)
    # Directory: the root, then the streams chained as right siblings.
    entries = [_entry("Root Entry", 5, END, 0, child=1)]
    for i, n in enumerate(names, 1):
        entries.append(_entry(n, 2, starts[n], len(streams[n]) if len(streams[n]) >= 4096 else len(data[n]),
                              right=i + 1 if i < len(names) else NOSTREAM))
    directory = b"".join(entries)
    directory = directory.ljust(((len(directory) + SECTOR - 1) // SECTOR) * SECTOR, b"\0")
    dir_start = len(sectors)
    dir_count = len(directory) // SECTOR
    for k in range(dir_count):
        sectors.append(directory[k * SECTOR:(k + 1) * SECTOR])
        fat.append(len(sectors) if k < dir_count - 1 else END)
    # FAT sectors (128 entries each) go last and mark themselves.
    fat_count = 1
    while (len(fat) + fat_count + 127) // 128 > fat_count:
        fat_count += 1
    fat_start = len(sectors)
    fat.extend([FATSECT] * fat_count)
    fat.extend([FREE] * (fat_count * 128 - len(fat)))
    fat_bytes = struct.pack(f"<{len(fat)}I", *fat)
    for k in range(fat_count):
        sectors.append(fat_bytes[k * SECTOR:(k + 1) * SECTOR])
    difat = [fat_start + k for k in range(fat_count)] + [FREE] * (109 - fat_count)
    header = (bytes.fromhex("D0CF11E0A1B11AE1") + b"\0" * 16 + struct.pack("<HHHHH", 0x3E, 3, 0xFFFE, 9, 6)
              + b"\0" * 6 + struct.pack("<IIIIIIIII", 0, fat_count, dir_start, 0, 4096, END, 0, END, 0)
              + struct.pack("<109I", *difat))
    with open(path, "wb") as f:
        f.write(header.ljust(SECTOR, b"\0"))
        for s in sectors:
            f.write(s)
    return path


def make_doc(path, text: str, encrypted=False):
    """A Word 97 file whose main text is ``text`` (one compressed piece)."""
    fib = bytearray(4096)
    struct.pack_into("<HH", fib, 0, 0xA5EC, 0x00C1)
    struct.pack_into("<H", fib, 0x0A, 0x0200 | (0x0100 if encrypted else 0))  # 1Table (+ encrypted)
    pos = 32
    struct.pack_into("<H", fib, pos, 14)
    pos += 2 + 28
    struct.pack_into("<H", fib, pos, 22)
    struct.pack_into("<i", fib, pos + 2 + 3 * 4, len(text))  # ccpText
    pos += 2 + 88
    struct.pack_into("<H", fib, pos, 0x5D)
    pos += 2
    raw = text.encode("cp1252")
    text_at = 2048
    fib[text_at:text_at + len(raw)] = raw
    plc = struct.pack("<II", 0, len(text)) + struct.pack("<HIH", 0, (text_at * 2) | 0x40000000, 0)
    clx = b"\x02" + struct.pack("<I", len(plc)) + plc
    struct.pack_into("<II", fib, pos + 33 * 8, 0, len(clx))
    return write(path, {"WordDocument": bytes(fib), "1Table": clx})

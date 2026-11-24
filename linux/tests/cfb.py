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


def _rec(rtype, data=b"", inst=0, ver=0):
    return struct.pack("<HHI", (inst << 4) | ver, rtype, len(data)) + data


def _box(anchor, content, shape_type=1):
    top, left, right, bottom = anchor
    return _rec(0xF004, _rec(0xF00A, b"\0" * 8, inst=shape_type, ver=2)
                + _rec(0xF010, struct.pack("<hhhh", top, left, right, bottom))
                + _rec(0xF00D, content, ver=0xF), ver=0xF)


def _drawing(*shapes):
    patriarch = _rec(0xF004, _rec(0xF009, b"\0" * 16, ver=1) + _rec(0xF00A, b"\0" * 8, ver=2), ver=0xF)
    return _rec(0x040C, _rec(0xF002, _rec(0xF003, patriarch + b"".join(shapes), ver=0xF), ver=0xF), ver=0xF)


def make_ppt(path, encrypted=False):
    """A PowerPoint 97 file: three slides (the third hidden) whose
    placeholder text lives in the slide list, a free text box, notes on
    the second slide and a group with a child anchor."""
    slides = [("Quarterly Review", "Hello & welcome", False, None),
              ("Results", "First point\rSecond point", False, "Mention the north."),
              ("Backup", "", True, None)]
    doc_atom = _rec(0x03E9, struct.pack("<ii", 5760, 4320) + b"\0" * 32, ver=1)
    slide_list = b""
    notes_list = b""
    containers = []  # (persist id, bytes)
    for k, (title, body, hidden, notes) in enumerate(slides):
        pid = 2 + 2 * k
        texts = _rec(0x0F9F, struct.pack("<I", 6 if k == 0 else 0)) + _rec(0x0FA0, title.encode("utf-16-le"))
        if body:
            texts += _rec(0x0F9F, struct.pack("<I", 5 if k == 0 else 1)) + _rec(0x0FA8, body.encode("cp1252"))
        slide_list += _rec(0x03F3, struct.pack("<IIiII", pid, 0, 2 if body else 1, 256 + k, 0)) + texts
        shapes = [_box((200, 300, 5460, 1100), _rec(0x0F9E, struct.pack("<i", 0)))]
        if body:
            shapes.append(_box((1300, 300, 3000, 3900), _rec(0x0F9E, struct.pack("<i", 1))))
        if k == 1:
            shapes.append(_box((1300, 3200, 5400, 2000), _rec(0x0F9F, struct.pack("<I", 4))
                               + _rec(0x0FA8, b"Free text"), shape_type=202))
            group_head = _rec(0xF004, _rec(0xF009, struct.pack("<iiii", 0, 0, 1000, 1000), ver=1)
                              + _rec(0xF00A, b"\0" * 8, ver=2)
                              + _rec(0xF010, struct.pack("<hhhh", 3000, 3200, 4200, 4000)), ver=0xF)
            child = _rec(0xF004, _rec(0xF00A, b"\0" * 8, inst=202, ver=2)
                         + _rec(0xF00F, struct.pack("<iiii", 0, 500, 1000, 1000))
                         + _rec(0xF00D, _rec(0x0F9F, struct.pack("<I", 4)) + _rec(0x0FA8, b"Grouped"), ver=0xF),
                         ver=0xF)
            shapes.append(_rec(0xF003, group_head + child, ver=0xF))
        info = _rec(0x03F9, struct.pack("<IIBBH", 0, 0, 0, 0, 0x4 if hidden else 0) + b"\0" * 4)
        containers.append((pid, _rec(0x03EE, _rec(0x03EF, b"\0" * 24, ver=2) + info + _drawing(*shapes), ver=0xF)))
        if notes:
            nid = pid + 1
            notes_list += _rec(0x03F3, struct.pack("<IIiII", nid, 0, 0, 512 + k, 0))
            ntext = _rec(0x0F9F, struct.pack("<I", 2)) + _rec(0x0FA0, notes.encode("utf-16-le"))
            containers.append((nid, _rec(0x03F0, _rec(0x03F1, struct.pack("<IHH", 256 + k, 0, 0), ver=1)
                                         + _drawing(_box((2000, 300, 5000, 4000), ntext)), ver=0xF)))
    document = _rec(0x03E8, doc_atom + _rec(0x0FF0, slide_list, inst=0, ver=0xF)
                    + _rec(0x0FF0, notes_list, inst=2, ver=0xF), ver=0xF)
    stream = bytearray(document)
    offsets = {1: 0}
    for pid, blob in containers:
        offsets[pid] = len(stream)
        stream += blob
    ids = sorted(offsets)
    directory = b"".join(struct.pack("<II", i | (1 << 20), offsets[i]) for i in ids)
    dir_offset = len(stream)
    stream += _rec(0x1772, directory)
    edit_offset = len(stream)
    stream += _rec(0x0FF5, struct.pack("<IHBBIIIIHH", 256, 0, 0, 3, 0, dir_offset, 1, max(ids) + 1, 1, 0))
    token = 0xF3D1C4DF if encrypted else 0xE391C05F
    user = _rec(0x0FF6, struct.pack("<III", 20, token, edit_offset) + b"\0" * 8)
    return write(path, {"PowerPoint Document": bytes(stream), "Current User": user})

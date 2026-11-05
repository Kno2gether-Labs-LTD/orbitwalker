#!/usr/bin/env python3
"""Print DT_NEEDED entries of a 64-bit little-endian ELF shared library."""
import struct
import sys


def needed(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise SystemExit(f"{path}: not a 64-bit little-endian ELF")
    e_phoff, = struct.unpack_from("<Q", data, 0x20)
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)
    loads, dynamic = [], None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, _flags, p_offset, p_vaddr, _paddr, p_filesz = struct.unpack_from("<IIQQQQ", data, off)
        if p_type == 1:
            loads.append((p_vaddr, p_offset, p_filesz))
        elif p_type == 2:
            dynamic = (p_offset, p_filesz)
    if dynamic is None:
        return []

    def vaddr_to_off(va):
        for v, o, sz in loads:
            if v <= va < v + sz:
                return o + (va - v)
        raise SystemExit(f"{path}: vaddr {va:#x} not in a LOAD segment")

    entries, strtab = [], None
    off, end = dynamic[0], dynamic[0] + dynamic[1]
    while off < end:
        tag, val = struct.unpack_from("<qQ", data, off)
        off += 16
        if tag == 0:
            break
        if tag == 1:
            entries.append(val)
        elif tag == 5:
            strtab = vaddr_to_off(val)
    names = []
    for n in entries:
        s = strtab + n
        names.append(data[s:data.index(b"\0", s)].decode())
    return names


if __name__ == "__main__":
    for name in needed(sys.argv[1]):
        print(name)

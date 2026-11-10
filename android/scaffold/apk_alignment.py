"""Android-only: static 16 KB page-size alignment of the APK and its native libraries.

Static inspection only; it never claims physical-device or native-runtime verification.
"""

import os
import struct
import subprocess
import zipfile
from pathlib import Path


def elf_load_alignment(data: bytes):
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] not in (1, 2) or data[5] not in (1, 2):
        raise ValueError("Malformed native ELF header")
    endian = "<" if data[5] == 1 else ">"
    if data[4] == 2:
        offset = struct.unpack_from(endian + "Q", data, 32)[0]
        size, count = struct.unpack_from(endian + "HH", data, 54)
        required, fmt = 56, endian + "IIQQQQQQ"
    else:
        offset = struct.unpack_from(endian + "I", data, 28)[0]
        size, count = struct.unpack_from(endian + "HH", data, 42)
        required, fmt = 32, endian + "IIIIIIII"
    if size < required or count < 1 or offset + size * count > len(data):
        raise ValueError("Malformed native ELF program headers")
    loads = []
    for index in range(count):
        header = struct.unpack_from(fmt, data, offset + size * index)
        if header[0] == 1:
            file_offset, address, alignment = (header[2], header[3], header[7]) if data[4] == 2 else (header[1], header[2], header[7])
            if alignment < 16384 or alignment & (alignment - 1) or file_offset % 16384 != address % 16384:
                raise ValueError("Native ELF PT_LOAD is not 16KB-page aligned")
            loads.append(alignment)
    if not loads:
        raise ValueError("Native ELF has no actual PT_LOAD segments")
    return loads


def inspect_alignment(apk: Path, sdk: Path):
    tool = sdk / "build-tools" / "37.0.0" / ("zipalign.exe" if os.name == "nt" else "zipalign")
    subprocess.run(
        [str(tool), "-c", "-P", "16", "4", str(apk)],
        check=True, capture_output=True, text=True, timeout=60,
    )
    libraries = {}
    with zipfile.ZipFile(apk) as archive:
        for member in archive.infolist():
            if member.filename.startswith("lib/") and member.filename.endswith(".so"):
                if member.file_size > 64 * 1024 * 1024:
                    raise ValueError("Oversized APK native library")
                libraries[member.filename] = elf_load_alignment(archive.read(member))
    return {
        "static_16kb_alignment_verified": True,
        "elf_pt_load_alignment": libraries,
        "physical_device_or_native_runtime_verified": False,
    }

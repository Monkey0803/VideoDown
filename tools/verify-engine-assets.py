#!/usr/bin/env python3
"""Verify pinned yt-dlp and the 16 KiB replacement libraries without running them."""
import hashlib
from pathlib import Path
import struct
import zipfile

root = Path(__file__).resolve().parents[1] / "app/src/main/assets/engine"
core = root / "yt-dlp"
expected = next(line.split()[1] for line in (root / "VERSION.txt").read_text().splitlines() if line.startswith("SHA256 "))
assert hashlib.sha256(core.read_bytes()).hexdigest() == expected, "yt-dlp checksum mismatch"
with zipfile.ZipFile(core) as archive:
    assert "yt_dlp_ejs/yt/solver/core.min.js" in archive.namelist(), "EJS missing"
checksums = (root / "native/SHA256SUMS").read_text().splitlines()
assert len(checksums) == 10, "Expected five libraries for each 64-bit ABI"
for line in checksums:
    checksum, relative = line.split()
    path = root / "native" / relative
    data = path.read_bytes()
    assert hashlib.sha256(data).hexdigest() == checksum, f"Checksum mismatch: {relative}"
    assert data[:6] == b"\x7fELF\x02\x01", f"Expected little endian ELF64: {relative}"
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    loads = [offset + i * size for i in range(count) if struct.unpack_from("<I", data, offset + i * size)[0] == 1]
    assert loads and all(struct.unpack_from("<Q", data, entry + 48)[0] >= 16384 for entry in loads), relative
    assert all((struct.unpack_from("<Q", data, entry + 8)[0] - struct.unpack_from("<Q", data, entry + 16)[0]) % 16384 == 0 for entry in loads), relative
print("Verified pinned yt-dlp + EJS and 10 native libraries with 16 KiB load alignment")

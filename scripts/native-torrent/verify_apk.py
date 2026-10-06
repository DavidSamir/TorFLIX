"""Release gate: both ARM engines, catalogue 7, and 16 KiB modern ELF alignment."""
from pathlib import Path
import hashlib
import json
import struct
import sys
import zipfile

LEGACY_SHA256 = {
    "armeabi-v7a": "bb9b6d66e9d56a268fae1543e403b267336bbfe42e08987d4f037b3e6fcdfd9d",
    "arm64-v8a": "aae39e515653f4dd42405a675579b74a7bfebd77702e730e5c23c3ae26837dd9",
}


def verify(apk: Path) -> None:
    binaries = json.loads(Path(__file__).with_name("binaries.json").read_text())["libraries"]
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        for abi in ("armeabi-v7a", "arm64-v8a"):
            modern = f"lib/{abi}/libtorrent4j-android11.so"
            assert modern in names, f"Missing Android 11 engine for {abi}"
            legacy = archive.read(f"lib/{abi}/libtorrent4j-1.2.3.0.so")
            assert hashlib.sha256(legacy).hexdigest() == LEGACY_SHA256[abi], \
                f"Original Fire Stick engine changed for {abi}"
            info = archive.getinfo(modern)
            # minSdk 22 retains legacy packaging: Android extracts libraries at install time.
            # If packaging ever switches to mmap from the APK, also check its ZIP data offset.
            if info.compress_type == zipfile.ZIP_STORED:
                archive.fp.seek(info.header_offset + 26)
                name_size, extra_size = struct.unpack("<HH", archive.fp.read(4))
                data_offset = info.header_offset + 30 + name_size + extra_size
                assert data_offset % 16384 == 0, f"{modern} is not 16 KiB aligned inside the APK"
            data = archive.read(modern)
            assert hashlib.sha256(data).hexdigest() == binaries[abi]["sha256"], \
                f"Compatibility engine differs from the reviewed binary for {abi}"
            assert data.count(b"Java_org_libtorrent4j_swig_libtorrent_1jni_") >= 3000, \
                f"{modern} is missing the expected JNI exports"
            assert data[:4] == b"\x7fELF" and data[5] == 1
            if data[4] == 2:
                offset = struct.unpack_from("<Q", data, 32)[0]
                size, count = struct.unpack_from("<HH", data, 54)
                fmt = "<IIQQQQQQ"
            else:
                offset = struct.unpack_from("<I", data, 28)[0]
                size, count = struct.unpack_from("<HH", data, 42)
                fmt = "<IIIIIIII"
            for index in range(count):
                header = struct.unpack_from(fmt, data, offset + index * size)
                if header[0] == 1:
                    assert header[-1] >= 16384, f"{modern} has a load segment smaller than 16 KiB alignment"
        catalog = archive.read("assets/catalog.json")
        manifest = json.loads(archive.read("assets/catalog-manifest.json"))
        assert manifest["catalogVersion"] == 7
        assert manifest["sha256"] == "b8c39fdef690bc2ab5f0735f1ec0f29757defa69c6dedcfb4d0fcc240e7e367e"
        assert manifest["sha256"] == hashlib.sha256(catalog).hexdigest()
        assert manifest["jsonBytes"] == len(catalog)
        entries = json.loads(catalog)
        assert len(entries) == manifest["titleCount"] == 2028
        assert sum(len(s["episodes"]) for e in entries for s in e.get("seasons", [])) == 3309
    print("APK verified: legacy + modern ARM libraries, 16 KiB ELF alignment, catalogue 7 (2028 titles / 3309 episodes)")


if __name__ == "__main__":
    verify(Path(sys.argv[1]))

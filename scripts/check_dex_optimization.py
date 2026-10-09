#!/usr/bin/env python3
"""Report release vs R8 benchmark DEX sizes; reject missing/ineffective shrinking.

APK byte sizes are a build artifact comparison, NOT a measured startup benefit.
For runtime validity install the R8-enabled benchmark APK on a spare phone.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import struct
import zipfile


def dex_metrics(apk: Path) -> dict[str, int]:
    with zipfile.ZipFile(apk) as archive:
        dex_entries = [
            info for info in archive.infolist()
            if info.filename.startswith("classes") and info.filename.endswith(".dex")
            and "/" not in info.filename
        ]
        if not dex_entries:
            raise RuntimeError(f"No DEX files were found in {apk}")
        classes = 0
        methods = 0
        for entry in dex_entries:
            with archive.open(entry) as source:
                header = source.read(0x70)
            if len(header) < 0x70 or header[:4] != b"dex\n":
                raise RuntimeError(f"Invalid DEX header in {apk}: {entry.filename}")
            methods += struct.unpack_from("<I", header, 0x58)[0]
            classes += struct.unpack_from("<I", header, 0x60)[0]
        return {
            "dex_files": len(dex_entries),
            "dex_uncompressed_bytes": sum(entry.file_size for entry in dex_entries),
            "dex_compressed_bytes": sum(entry.compress_size for entry in dex_entries),
            "class_definitions": classes,
            "method_ids": methods,
            "apk_bytes": apk.stat().st_size,
        }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--release", type=Path, required=True)
    parser.add_argument("--optimized", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    release = dex_metrics(args.release)
    optimized = dex_metrics(args.optimized)
    saved = release["dex_uncompressed_bytes"] - optimized["dex_uncompressed_bytes"]
    percent = 100 * saved / release["dex_uncompressed_bytes"]
    report = {
        "unoptimized_release": release,
        "r8_benchmark": optimized,
        "dex_bytes_saved": saved,
        "dex_size_reduction_percent": round(percent, 2),
    }
    print(json.dumps(report, indent=2))

    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    if saved <= 0:
        raise SystemExit("R8 benchmark DEX must be smaller than unoptimized release")


if __name__ == "__main__":
    main()

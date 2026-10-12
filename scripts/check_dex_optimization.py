#!/usr/bin/env python3
"""Compare unoptimized baseline and R8-optimized production release DEX sizes.

This detects missing/ineffective shrinking, not Google Play's distinct 25%
obfuscation/optimization/shrinking metrics or measured runtime improvements.
Use an actual release-like APK on a spare phone to verify startup and flows.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import struct
import zipfile


def dex_metrics(apk: Path) -> dict[str, int]:
    if not apk.is_file():
        raise FileNotFoundError(
            f"DEX comparison requires an APK file, but got {apk!s}. "
            "Build the baseline with :app:assembleUnoptimized and "
            "production APK with :app:assembleRelease."
        )
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
    parser.add_argument(
        "--baseline", "--release", dest="baseline", type=Path, required=True,
        help="Unoptimized comparison APK (--release is a deprecated alias)"
    )
    parser.add_argument("--optimized", type=Path, required=True,
                        help="Actual R8-enabled production release APK")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    baseline = dex_metrics(args.baseline)
    optimized = dex_metrics(args.optimized)
    saved = baseline["dex_uncompressed_bytes"] - optimized["dex_uncompressed_bytes"]
    percent = 100 * saved / baseline["dex_uncompressed_bytes"]
    report = {
        "unoptimized_baseline": baseline,
        "r8_production_release": optimized,
        "dex_bytes_saved": saved,
        "dex_size_reduction_percent": round(percent, 2),
    }
    print(json.dumps(report, indent=2))

    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    if saved <= 0:
        raise SystemExit("R8 production release DEX must be smaller than unoptimized baseline")


if __name__ == "__main__":
    main()

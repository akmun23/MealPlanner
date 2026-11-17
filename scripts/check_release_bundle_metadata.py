#!/usr/bin/env python3
"""Verify release App Bundle metadata before uploading it to Play Console.

AGP bundles R8's ProGuard mapping automatically. Native debug metadata is
best-effort: dependencies may ship pre-stripped .so files with no symbols.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import sys
import zipfile

MAP_PATH = "BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map"
NATIVE_PREFIX = "BUNDLE-METADATA/com.android.tools.build.debugsymbols/"


def check_bundle(path: Path) -> int:
    if not path.is_file():
        print(f"ERROR: App Bundle not found: {path}", file=sys.stderr)
        return 1

    try:
        with zipfile.ZipFile(path) as bundle:
            contents = set(bundle.namelist())
            mapping = bundle.getinfo(MAP_PATH) if MAP_PATH in contents else None
            if mapping is None or mapping.file_size == 0:
                print(
                    f"ERROR: {path.name} has no R8 mapping at {MAP_PATH}. "
                    "Do not upload this bundle: build :app:bundleRelease "
                    "with R8 enabled.",
                    file=sys.stderr,
                )
                return 1

            print(f"OK: R8 mapping embedded in {path.name} ({mapping.file_size} bytes)")
            r8_metadata = "BUNDLE-METADATA/com.android.tools/r8.json"
            if r8_metadata in contents:
                print("OK: R8 optimization metadata (r8.json) embedded")

            native_libs = sorted(
                name for name in contents
                if name.endswith(".so") and "/lib/" in name
                and not name.startswith("BUNDLE-METADATA/")
            )
            native_symbols = sorted(
                name for name in contents
                if name.startswith(NATIVE_PREFIX) and name.endswith(".dbg")
            )
            if native_libs:
                if native_symbols:
                    print(
                        f"OK: {len(native_symbols)} native symbol file(s) embedded; "
                        f"{len(native_libs)} native library file(s) bundled."
                    )
                else:
                    print(
                        "WARNING: The bundle includes "
                        f"{len(native_libs)} native .so libraries but no "
                        "native debug symbols. Ensure "
                        "release.ndk.debugSymbolLevel is FULL. Dependencies "
                        "may have been stripped before packaging, in which "
                        "case Gradle cannot recover their symbols.",
                        file=sys.stderr,
                    )
            else:
                print("OK: No native .so libraries detected in this bundle")
    except (OSError, zipfile.BadZipFile) as exc:
        print(f"ERROR: Could not inspect {path}: {exc}", file=sys.stderr)
        return 1
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle", type=Path, help="release .aab to inspect")
    return check_bundle(parser.parse_args().bundle)


if __name__ == "__main__":
    raise SystemExit(main())

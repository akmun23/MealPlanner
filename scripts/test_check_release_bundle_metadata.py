#!/usr/bin/env python3
"""Fast, offline tests for release App Bundle metadata verification."""
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
import zipfile

from check_release_bundle_metadata import MAP_PATH, NATIVE_PREFIX, check_bundle


class ReleaseBundleMetadataTest(unittest.TestCase):
    def make_bundle(self, path: Path, files: dict[str, bytes]) -> None:
        with zipfile.ZipFile(path, "w") as z:
            for name, content in files.items():
                z.writestr(name, content)

    def check(self, files: dict[str, bytes]) -> tuple[int, str, str]:
        with TemporaryDirectory() as temp:
            path = Path(temp) / "app-release.aab"
            self.make_bundle(path, files)
            out, err = StringIO(), StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                code = check_bundle(path)
            return code, out.getvalue(), err.getvalue()

    def test_mapping_is_required(self) -> None:
        code, _, err = self.check({"base/dex/classes.dex": b"data"})
        self.assertEqual(code, 1)
        self.assertIn("no R8 mapping", err)

    def test_mapping_must_not_be_empty(self) -> None:
        code, _, err = self.check({MAP_PATH: b""})
        self.assertEqual(code, 1)
        self.assertIn("no R8 mapping", err)

    def test_java_only_bundle_passes(self) -> None:
        code, out, err = self.check({MAP_PATH: b"# compiler: R8\n"})
        self.assertEqual(code, 0)
        self.assertIn("R8 mapping embedded", out)
        self.assertEqual(err, "")

    def test_native_without_debug_symbols_warns(self) -> None:
        code, _, err = self.check({
            MAP_PATH: b"# compiler: R8\n",
            "base/lib/arm64-v8a/libexample.so": b"ELF",
        })
        self.assertEqual(code, 0)
        self.assertIn("WARNING", err)

    def test_native_with_debug_symbols_passes(self) -> None:
        code, out, err = self.check({
            MAP_PATH: b"# compiler: R8\n",
            "base/lib/arm64-v8a/libexample.so": b"ELF",
            NATIVE_PREFIX + "arm64-v8a/libexample.so.dbg": b"ELF-SYMBOLS",
        })
        self.assertEqual(code, 0)
        self.assertIn("native symbol file(s) embedded", out)
        self.assertEqual(err, "")


if __name__ == "__main__":
    unittest.main()

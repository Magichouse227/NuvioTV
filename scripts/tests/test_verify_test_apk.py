import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


spec = importlib.util.spec_from_file_location(
    "verify_test_apk", Path(__file__).parents[1] / "verify-test-apk.py"
)
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class VerifyTestApkTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "app.apk"
        self.native = self.root / "lib"
        self.sha = "a" * 40

    def build(self, marker=None, missing=None, wrong_abi=None):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("classes.dex", (marker or self.sha).encode())
            for abi, machine in verifier.ABIS.items():
                if abi == missing:
                    continue
                elf = bytearray(64)
                elf[:4] = b"\x7fELF"
                elf[5] = 1
                elf[18:20] = (0 if abi == wrong_abi else machine).to_bytes(2, "little")
                archive.writestr(f"lib/{abi}/libnuvionntp.so", elf)
                path = self.native / abi / "libnuvionntp.so"
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(elf)

    def verify(self):
        return verifier.verify_contents(self.apk, self.sha, self.native)

    def test_all_abis_and_marker_verified(self):
        self.build()
        self.assertEqual(set(verifier.ABIS), set(self.verify()))

    def test_missing_abi_rejected(self):
        self.build(missing="armeabi-v7a")
        with self.assertRaisesRegex(ValueError, "all four ABIs"):
            self.verify()

    def test_wrong_architecture_rejected(self):
        self.build(wrong_abi="x86")
        with self.assertRaisesRegex(ValueError, "Wrong ELF architecture"):
            self.verify()

    def test_stale_build_marker_rejected(self):
        self.build(marker="b" * 40)
        with self.assertRaisesRegex(ValueError, "build marker"):
            self.verify()

    def test_stale_native_binary_rejected(self):
        self.build()
        (self.native / "arm64-v8a" / "libnuvionntp.so").write_bytes(b"old binary")
        with self.assertRaisesRegex(ValueError, "differs from build"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
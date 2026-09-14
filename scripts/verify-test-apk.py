#!/usr/bin/env python3
"""Fail closed before replacing the direct-download test APK (no credentials)."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile


TEST_CERTIFICATE = "c0a719c4c8072878574095e6f78d94487cff5e6239539868e2ff886e84c54911"
ABIS = {"armeabi-v7a": 40, "arm64-v8a": 183, "x86": 3, "x86_64": 62}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def verify_contents(apk, build_sha, native_dir):
    require(re.fullmatch(r"[0-9a-f]{40}", build_sha), "Expected a full Git build SHA")
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate APK entries")
        packaged_abis = {name.split("/")[1] for name in names if name.startswith("lib/")}
        require(packaged_abis == set(ABIS), "Universal APK must contain all four ABIs")
        dex_files = [name for name in names if re.fullmatch(r"classes\d*\.dex", name)]
        require(any(build_sha.encode() in archive.read(name) for name in dex_files),
                "APK does not contain the expected BuildConfig build marker")
        native_hashes = {}
        for abi, machine in ABIS.items():
            name = f"lib/{abi}/libnuvionntp.so"
            data = archive.read(name)
            require(data[:4] == b"\x7fELF", f"Invalid ELF: {abi}")
            require(data[5] == 1 and int.from_bytes(data[18:20], "little") == machine,
                    f"Wrong ELF architecture: {abi}")
            # Compare with this run's AGP-stripped output, never the checked-in binary.
            built = native_dir / abi / "libnuvionntp.so"
            require(data == built.read_bytes(), f"Packaged NNTP differs from build: {abi}")
            native_hashes[abi] = sha256(data)
        return native_hashes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--build-sha", required=True)
    parser.add_argument("--native-dir", type=Path, required=True)
    parser.add_argument("--aapt", default="aapt")
    parser.add_argument("--apksigner", default="apksigner")
    parser.add_argument("--minimum-version", type=int, default=1059)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    signature = subprocess.check_output(
        [args.apksigner, "verify", "--verbose", "--print-certs", str(args.apk)], text=True
    )
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", signature)
    require(certificates == [TEST_CERTIFICATE], "Permanent test signing certificate changed")
    badging = subprocess.check_output([args.aapt, "dump", "badging", str(args.apk)], text=True)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)'", badging)
    require(package is not None, "Missing package metadata")
    require(package[1] == "com.nuviodebug.com", "Test application identity changed")
    require(int(package[2]) >= args.minimum_version, "APK would downgrade the installed test app")
    require("application-debuggable" not in badging, "Delivered APK must be non-debuggable")
    native_hashes = verify_contents(args.apk, args.build_sha, args.native_dir)
    report = {
        "buildSha": args.build_sha,
        "applicationId": package[1],
        "versionCode": int(package[2]),
        "signerSha256": certificates[0],
        "apkSha256": sha256(args.apk.read_bytes()),
        "nntpAbis": native_hashes,
        "runtimeTested": False,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
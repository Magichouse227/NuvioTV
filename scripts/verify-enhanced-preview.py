#!/usr/bin/env python3
"""Validate the built preview's identity, ARM32 support, and JVM test results."""

import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


def verify() -> None:
    root = Path(__file__).resolve().parent.parent
    output = root / "app/build/outputs/apk/full/debug"
    apks = sorted(output.glob("*armeabi-v7a*.apk"))
    if len(apks) != 1:
        raise SystemExit(f"Expected one ARM32 preview APK, found {len(apks)}")

    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        raise SystemExit("Android SDK location is required")
    aapt = Path(sdk) / "build-tools/36.0.0/aapt"
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apks[0])], text=True)

    def value(pattern: str, field: str) -> str:
        match = re.search(pattern, badging, re.MULTILINE)
        if not match:
            raise SystemExit(f"APK is missing {field}")
        return match.group(1)

    package = value(r"^package: name='([^']+)'", "application ID")
    if package != "com.nuvio.tv.enhanced.preview":
        raise SystemExit(f"Wrong preview application ID: {package}")
    min_sdk = int(value(r"^sdkVersion:'(\d+)'", "minimum SDK"))
    if min_sdk > 28:
        raise SystemExit(f"Minimum SDK {min_sdk} excludes the Fire OS 7 target")
    abis = re.findall(r"'([^']+)'", value(r"^native-code: (.+)$", "native ABIs"))
    if abis != ["armeabi-v7a"]:
        raise SystemExit(f"Expected ARM32-only native libraries, found {abis}")

    reports = sorted((root / "app/build/test-results/testFullDebugUnitTest").glob("TEST-*.xml"))
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
    if totals["tests"] <= totals["skipped"] or totals["failures"] or totals["errors"]:
        raise SystemExit(f"Preview requires passing JVM tests: {totals}")

    summary = {
        "commit": os.environ.get("GITHUB_SHA"),
        "apk": apks[0].name,
        "application_id": package,
        "min_sdk": min_sdk,
        "abis": abis,
        "jvm_tests": totals,
    }
    (output / "preview-verification.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    verify()

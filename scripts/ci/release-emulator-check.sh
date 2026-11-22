#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$repo_root"

build_packages() {
    local test_apk=$1
    local report_dir=$2
    test -s "$test_apk"
    mkdir -p "$report_dir"

    # This key belongs only to this emulator run. Release publishing uses its own Secrets.
    export FLUE_SIGNING_STORE_FILE="${RUNNER_TEMP:?RUNNER_TEMP is required}/fflue-release-emulator.keystore"
    export FLUE_SIGNING_STORE_PASSWORD=android
    export FLUE_SIGNING_KEY_ALIAS=fflue-emulator
    export FLUE_SIGNING_KEY_PASSWORD=android
    trap 'rm -f -- "$FLUE_SIGNING_STORE_FILE"' EXIT
    umask 077
    keytool -genkeypair -noprompt -storetype PKCS12 \
        -keystore "$FLUE_SIGNING_STORE_FILE" \
        -storepass:env FLUE_SIGNING_STORE_PASSWORD \
        -keypass:env FLUE_SIGNING_KEY_PASSWORD \
        -alias "$FLUE_SIGNING_KEY_ALIAS" -keyalg RSA -keysize 2048 \
        -validity 2 -dname 'CN=FFlue Emulator Test'

    bash gradlew --no-daemon --max-workers=2 \
        -Pkotlin.compiler.execution.strategy=in-process \
        -Pflue.extraAbi=x86_64 -Pflue.disableReleaseMinify=false \
        :app:assembleRelease --console=plain

    local build_tools="${ANDROID_HOME:?ANDROID_HOME is required}/build-tools/35.0.0"
    cp app/build/outputs/apk/release/app-release.apk "$report_dir/app-release-under-test.apk"
    "$build_tools/apksigner" sign \
        --ks "$FLUE_SIGNING_STORE_FILE" --ks-key-alias "$FLUE_SIGNING_KEY_ALIAS" \
        --ks-pass env:FLUE_SIGNING_STORE_PASSWORD --key-pass env:FLUE_SIGNING_KEY_PASSWORD \
        --out "$report_dir/app-release-ui-test.apk" "$test_apk"

    "$build_tools/apksigner" verify --verbose --print-certs \
        "$report_dir/app-release-under-test.apk" > "$report_dir/app-certificate.txt"
    "$build_tools/apksigner" verify --verbose --print-certs \
        "$report_dir/app-release-ui-test.apk" > "$report_dir/test-certificate.txt"
    "$build_tools/aapt2" dump badging "$report_dir/app-release-under-test.apk" > "$report_dir/app-manifest.txt"
    test -s app/build/outputs/mapping/release/mapping.txt

    python3 - "$report_dir" <<'PY'
from pathlib import Path
import json
import re
import struct
import sys
import zipfile

root = Path(sys.argv[1])
def fingerprints(path):
    return re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", path.read_text())

app = fingerprints(root / "app-certificate.txt")
tests = fingerprints(root / "test-certificate.txt")
assert len(app) == 1 and app == tests, "App and instrumentation signatures differ"
manifest = (root / "app-manifest.txt").read_text()
assert "package: name='com.gaozai.surface'" in manifest, "Unexpected application ID"
assert "application-debuggable" not in manifest, "Release app must have debuggable disabled"

# Check class definitions, not references: a Debug androidTest APK can refer to
# runtime classes that it omits because its original target supplied them.
defined_classes = set()
with zipfile.ZipFile(root / "app-release-ui-test.apk") as apk:
    for name in apk.namelist():
        if not re.fullmatch(r"classes\d*\.dex", name):
            continue
        dex = apk.read(name)
        strings_offset = struct.unpack_from("<I", dex, 60)[0]
        types_offset = struct.unpack_from("<I", dex, 68)[0]
        class_count, classes_offset = struct.unpack_from("<II", dex, 96)
        for index in range(class_count):
            class_index = struct.unpack_from("<I", dex, classes_offset + index * 32)[0]
            string_index = struct.unpack_from("<I", dex, types_offset + class_index * 4)[0]
            offset = struct.unpack_from("<I", dex, strings_offset + string_index * 4)[0]
            while dex[offset] & 0x80:
                offset += 1
            offset += 1
            defined_classes.add(dex[offset:dex.index(b"\0", offset)].decode("utf-8"))
required = {
    "Lkotlin/jvm/internal/Intrinsics;",
    "Landroidx/tracing/Trace;",
    "Landroidx/test/runner/AndroidJUnitRunner;",
    "Lcom/flue/launcher/surface/LauncherSmokeTest;",
    "Lcom/flue/launcher/surface/ReleaseUiSmokeTest;",
}
assert required <= defined_classes, f"Standalone test runtime is incomplete: {required - defined_classes}"
assert "Lcom/flue/launcher/LauncherActivity;" not in defined_classes, "Test APK must not bundle app implementation"
(root / "build-summary.json").write_text(json.dumps({
    "application_id": "com.gaozai.surface",
    "certificate_sha256": app[0],
    "r8_mapping_bytes": Path("app/build/outputs/mapping/release/mapping.txt").stat().st_size,
    "signing_purpose": "temporary emulator test key",
    "standalone_test_runtime_verified": True,
}, indent=2))
print("R8 Release and instrumentation APK identities verified")
PY
}

collect_diagnostics() {
    local result=$?
    trap - EXIT
    adb logcat -d -v threadtime > "$report_dir/logcat.txt" 2>&1 || true
    adb exec-out screencap -p > "$report_dir/final-screen.png" 2>/dev/null || true
    adb shell uiautomator dump /sdcard/fflue-release-window.xml >/dev/null 2>&1 || true
    adb pull /sdcard/fflue-release-window.xml "$report_dir/window.xml" >/dev/null 2>&1 || true
    exit "$result"
}

run_instrumentation() {
    local label=$1
    local expected=$2
    local classes=$3
    shift 3
    adb shell am instrument -w -r -e class "$classes" "$@" \
        com.gaozai.surface.test/androidx.test.runner.AndroidJUnitRunner | tee "$report_dir/$label.txt"

    python3 - "$report_dir/$label.txt" "$expected" <<'PY'
from pathlib import Path
import json
import re
import sys

path = Path(sys.argv[1])
expected = int(sys.argv[2])
output = path.read_text()
success = re.search(r"^OK \((\d+) tests?\)\s*$", output, re.MULTILINE)
code = re.search(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", output, re.MULTILINE)
assert success and int(success.group(1)) == expected, "Expected completed instrumentation tests were not reported"
assert code and int(code.group(1)) == -1, "Instrumentation did not finish successfully"
assert "FAILURES!!!" not in output and "INSTRUMENTATION_FAILED" not in output
path.with_suffix(".json").write_text(json.dumps({"tests": expected, "failures": 0}, indent=2))
print(f"{path.stem}: {expected} tests passed")
PY
}

test_packages() {
    report_dir=$1
    mkdir -p "$report_dir"
    trap collect_diagnostics EXIT
    adb shell wm size 454x454
    adb shell wm density 320
    adb logcat -c
    adb install -r "$report_dir/app-release-under-test.apk"
    adb install -r -t "$report_dir/app-release-ui-test.apk"

    local launcher=com.flue.launcher.surface.LauncherSmokeTest
    local smoke=com.flue.launcher.surface.ReleaseUiSmokeTest
    run_instrumentation initial 3 "$launcher,$smoke"
    adb exec-out screencap -p > "$report_dir/initial-screen.png"
    adb shell am force-stop com.gaozai.surface
    if adb shell pidof com.gaozai.surface > "$report_dir/post-stop-pids.txt"; then
        echo 'Release app remained running after force-stop' >&2
        exit 1
    fi
    run_instrumentation cold-start 1 "$smoke" -e release_smoke_phase restore
}

case "${1:-}" in
    build)
        test "$#" -eq 3
        build_packages "$2" "$3"
        ;;
    test)
        test "$#" -eq 2
        test_packages "$2"
        ;;
    *)
        echo 'Usage: release-emulator-check.sh build <test-apk> <report-dir> | test <report-dir>' >&2
        exit 2
        ;;
esac

"""Validate only the installed APK's real OES background shader on owned offline emulator-5580."""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import re
import subprocess
import time
import uuid

REPO = Path(__file__).resolve().parents[1]
ADB = "C:/Users/TechTroniX/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL = "emulator-5580"
PACKAGE = "com.sarab.vision"


def adb(*args):
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, check=True, timeout=20).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("expected_sha256")
    parser.add_argument("--output", type=Path, default=REPO / "output/qa-navigation-background")
    args = parser.parse_args()
    expected = args.expected_sha256.upper()
    assert re.fullmatch(r"[A-F0-9]{64}", expected), "Final APK SHA256 required"
    hardware = adb("shell", "getprop", "ro.hardware").decode().strip()
    assert hardware in ("ranchu", "goldfish")
    offline_settings = {}
    for key in ("airplane_mode_on", "wifi_on", "mobile_data"):
        value = adb("shell", "settings", "get", "global", key).decode().strip()
        assert value == ("1" if key == "airplane_mode_on" else "0")
        offline_settings[key] = int(value)
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    apk = REPO / "output/app-debug.apk"
    assert hashlib.sha256(apk.read_bytes()).hexdigest().upper() == expected
    installed = adb("shell", "pm", "path", PACKAGE).decode().strip().removeprefix("package:")
    assert installed.startswith("/data/app/") and "\n" not in installed
    adb("pull", installed, str(out / "installed-base.apk"))
    installed_sha256 = hashlib.sha256((out / "installed-base.apk").read_bytes()).hexdigest().upper()
    assert installed_sha256 == expected
    run_id = uuid.uuid4().hex
    adb("logcat", "-c", "-b", "crash")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-n", f"{PACKAGE}/.NavigationBackgroundFixtureActivity",
        "--ez", "allow_fixture", "true", "--es", "run_id", run_id)
    report = None
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            current = json.loads(adb("exec-out", "run-as", PACKAGE, "cat", "files/navigation-background-fixture.json"))
            if current.get("runId") == run_id:
                report = current
                if current.get("result") in ("PASS", "FAIL"):
                    break
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            pass
        time.sleep(.2)
    crashes = adb("logcat", "-d", "-b", "crash").decode(errors="replace")
    (out / "crash.txt").write_text(crashes, encoding="utf-8")
    (out / "fixture.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    # Release only the application this runner launched; root retains the shared QA emulator.
    adb("shell", "am", "force-stop", PACKAGE)
    assert report and report["result"] == "PASS", report
    crash_count = len(re.findall(r"FATAL EXCEPTION|Fatal signal|Process: com\.sarab\.vision", crashes))
    assert crash_count == 0
    required = {
        "timestamp_zero_skips_undefined_texture", "first_frame_without_geometry_flag_has_four_regions",
        "recreated_gl_context_reinitializes_uv", "repeated_frame_preserves_texture_and_uv",
        "all_quarter_turns_keep_distinct_correctly_mapped_regions",
        "new_session_invalidation_refreshes_uv_without_geometry_flag", "degenerate_uv_rejected_then_recovers",
        "camera_attributes_isolated_from_virtual_geometry_vbo",
    }
    assert required.issubset(report["checks"])
    assert report["verificationKind"] == "offline-gles-navigation-background"
    assert report["networkRequests"] == 0 and not report["cameraAccess"] and not report["physicalArCoreTest"]
    # This file contains only a fixed four-color test pattern generated inside the fixture.
    (out / "synthetic-oes.png").write_bytes(adb("exec-out", "run-as", PACKAGE, "cat",
        "files/navigation-background-context-0-rotation-0.png"))
    limits = [
        "Offline emulator only. The producer is a fixed four-color SurfaceTexture, not a physical camera.",
        "Exercises the production GLES/OES background shader and UV lifecycle; does not verify physical ARCore acquisition, tracking, focus or destination UI.",
        "No Fold7 or XREAL result is inferred from this synthetic rendering check.",
    ]
    validation = {**report, "apkSha256": expected, "installedApkSha256": installed_sha256,
        "serial": SERIAL, "crashes": crash_count, "offlineSettings": offline_settings,
        "environment": {"serial": SERIAL, "hardware": hardware, "physicalDevice": False,
                        "offline": True, "cameraUsed": False, "offlineSettings": offline_settings},
        "physicalCameraTest": False, "syntheticPixelsOnly": True,
        "scope": "Production camera background GLES/OES shader with synthetic SurfaceTexture, UV initialization, quarter-turn mapping, GL context recreation and VBO isolation.",
        "limits": limits, "boundary": limits[1], "appStopped": True,
        "shutdown": False, "emulatorShutdownConfirmed": False,
        "completedAt": datetime.now(timezone.utc).isoformat()}
    (out / "validation.json").write_text(json.dumps(validation, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"result": "PASS", "checks": len(required), "output": str(out)}, ensure_ascii=False))


if __name__ == "__main__":
    main()

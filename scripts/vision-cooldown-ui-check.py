"""One actual HUD cooldown/draft flow on the already installed offline QA emulator only."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[1]
ADB = "C:/Users/TechTroniX/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL = "emulator-5580"
ACTIVITY = "com.sarab.vision/.VisionImportFixtureActivity"


def adb(*args):
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, check=True, timeout=20).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("expected_sha256")
    parser.add_argument("--output", type=Path, default=REPO / "output/qa-timeout-cooldown")
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    expected = args.expected_sha256.upper()
    assert re.fullmatch("[0-9A-F]{64}", expected), "Expected final APK SHA256 required"
    assert adb("shell", "getprop", "ro.hardware").strip() in (b"ranchu", b"goldfish")
    for key, value in (("airplane_mode_on", b"1"), ("wifi_on", b"0"), ("mobile_data", b"0")):
        assert adb("shell", "settings", "get", "global", key).strip() == value, "Fixture must already be offline"
    apk = REPO / "output/app-debug.apk"
    assert hashlib.sha256(apk.read_bytes()).hexdigest().upper() == expected
    installed = adb("shell", "pm", "path", "com.sarab.vision").decode().strip().removeprefix("package:")
    adb("pull", installed, str(out / "installed-base.apk"))
    assert hashlib.sha256((out / "installed-base.apk").read_bytes()).hexdigest().upper() == expected
    run_id = uuid.uuid4().hex
    adb("logcat", "-c", "-b", "crash")
    adb("shell", "am", "force-stop", "com.sarab.vision")
    adb("shell", "am", "start", "-n", ACTIVITY, "--ez", "allow_fixture", "true",
        "--es", "scenario", "photo_cooldown_ui", "--es", "run_id", run_id)
    last_report = None

    def read_report():
        nonlocal last_report
        try:
            data = json.loads(adb("exec-out", "run-as", "com.sarab.vision", "cat", "files/vision-import-photo_cooldown_ui.json"))
            if data.get("runId") == run_id:
                last_report = data
                (out / "controller.json").write_text(json.dumps(data, indent=2), encoding="utf-8")
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            pass
        if last_report and last_report.get("result") == "FAIL":
            raise AssertionError(last_report.get("failure"))
        return last_report

    def wait_phase(phase, timeout=12):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            current = read_report()
            if current and current.get("phase") == phase:
                return current
            time.sleep(.08)
        raise AssertionError(f"No phase {phase}: {last_report}")

    def nodes(name=None):
        adb("shell", "uiautomator", "dump", "/sdcard/timeout-cooldown.xml")
        raw = adb("exec-out", "cat", "/sdcard/timeout-cooldown.xml")
        if name:
            (out / f"{name}.xml").write_bytes(raw)
        return list(ET.fromstring(raw).iter("node"))

    def tag(tree, value):
        matches = [n for n in tree if n.get("resource-id", "").endswith(value)]
        assert len(matches) == 1, f"Expected unique tag {value}; found {len(matches)}"
        return matches[0]

    def tap(node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        assert x2 > x1 and y2 > y1
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def picture(name):
        (out / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p"))

    def command(value):
        adb("shell", "am", "start", "-n", ACTIVITY, "--ez", "allow_fixture", "true",
            "--es", "run_id", run_id, "--es", "fixture_command", value)

    wait_phase("ready_for_draft")
    write = [n for n in nodes() if n.get("text") == "اكتب"]
    assert len(write) == 1, "Actual HUD Write action missing"
    tap(write[0])
    tap(tag(nodes(), "vision-live-question"))
    adb("shell", "input", "text", "LOCAL_DRAFT")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    before = nodes("draft-before-timeout")
    assert tag(before, "vision-live-question").get("text") == "LOCAL_DRAFT"
    command("fail_once")
    active = wait_phase("cooldown_visible")
    assert active["requestsStarted"] == 1 and active["retryRemainingMs"] > 0
    required = {"actual_hud_countdown_visible", "draft_retained_during_wait", "send_disabled_during_wait"}
    assert required.issubset(active["checks"]), "Actual Compose semantics did not verify cooldown controls"
    # Do not use uiautomator's idle wait during this3500ms countdown. The debug fixture
    # already read real Compose tags/text/Disabled semantics while the timer was visible.
    picture("cooldown-visible")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    wait_phase("await_resume")
    adb("shell", "am", "start", "-n", ACTIVITY)
    expired = wait_phase("ready_manual_retry")
    assert expired["requestsStarted"] == 1, "Expiry must not send a request"
    after = nodes("draft-after-expiry")
    field = tag(after, "vision-live-question")
    send = tag(after, "vision-live-send")
    assert field.get("text") == "LOCAL_DRAFT", "Draft changed during cooldown/background"
    assert send.get("enabled") == "true", "Manual send did not re-enable after expiry"
    assert not any(n.get("resource-id", "").endswith("vision-retry-wait") for n in after)
    picture("draft-after-expiry")
    assert read_report()["requestsStarted"] == 1
    tap(send)
    answered = wait_phase("await_live_return")
    assert answered["requestsStarted"] == 2 and answered["uiDraftMatched"]
    picture("manual-reply")
    command("return_live")
    final = wait_phase("complete")
    assert final["result"] == "PASS" and final["actualActivityStop"] and not final["imported"]
    cleared = nodes("source-cleared")
    assert not any(n.get("resource-id", "").endswith(("vision-retry-wait", "vision-live-question", "vision-conversation-panel")) for n in cleared)
    picture("source-cleared")
    crashes = adb("logcat", "-d", "-b", "crash", "-v", "brief").decode(errors="replace")
    (out / "crash-log.txt").write_text(crashes, encoding="utf-8")
    assert not re.search(r"FATAL EXCEPTION|Fatal signal|Process: com\.sarab\.vision", crashes)
    assert hashlib.sha256(apk.read_bytes()).hexdigest().upper() == expected
    result = {"result": "PASS", "apkSha256": expected, "installedApkSha256": expected,
              "serial": SERIAL, "cases": ["actual_hud_cooldown_and_draft"], "controller": final,
              "crashes": 0, "realNetworkRequests": 0, "realSpeechRecognition": False,
              "emulatorShutdownConfirmed": False,
              "scope": "Actual production HUD/controller with synthetic photo and3500ms UPLOAD_TIMEOUT advice. Real Compose semantics verify active countdown/disabled Send/retained draft; HOME/resume, manual Send and source cleanup use ADB. No provider, camera or microphone quality claim."}
    (out / "validation.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({"result": "PASS", "apkSha256": expected, "requests": final["requestsStarted"], "checks": len(final["checks"])}))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Read-only UI smoke for an installed non-debuggable release.

Explicit device serial/output required. Never taps claim/connect/logout/resume.
Screens/XML can contain account display names: keep output outside public source.
Use recovery_smoke.py separately for an isolated active-task interruption.
"""
import argparse
from datetime import datetime, timezone
import uuid
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "cn.run.freepocket"
PREFIX = PACKAGE + ":id/"


class Device:
    def __init__(self, adb, serial, output, timeout):
        self.command = [adb, "-s", serial]
        self.output = output
        self.timeout = timeout

    def run(self, *args):
        return subprocess.run(self.command + list(args), check=True, capture_output=True, timeout=30).stdout

    def tree(self):
        self.run("shell", "uiautomator", "dump", "/sdcard/runa-release-smoke.xml")
        return ET.fromstring(self.run("shell", "cat", "/sdcard/runa-release-smoke.xml"))

    @staticmethod
    def node(root, name):
        return next((node for node in root.iter("node") if node.get("resource-id") == PREFIX + name), None)

    def ready(self):
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            root = self.tree()
            button = self.node(root, "claim_all")
            if button is not None and button.get("enabled") == "true" and not any(
                    node.get("class") == "android.webkit.WebView" for node in root.iter("node")):
                return root
            time.sleep(1)
        raise TimeoutError("Catalogue is not idle; official login/network may require user action")

    def tap(self, root, name):
        # Keep the allow-list independent of any server or UI text.
        if name not in {"tab_pc", "tab_android", "tab_fab", "account", "history"}:
            raise ValueError("This smoke test may only navigate read-only screens")
        node = self.node(root, name)
        if node is None or node.get("enabled") != "true":
            raise AssertionError(name + " missing or disabled")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        if x2 <= x1 or y2 <= y1:
            raise AssertionError(name + " is not visible")
        self.run("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def capture(self, name, root):
        self.output.joinpath(name + ".xml").write_bytes(ET.tostring(root, encoding="utf-8"))
        self.run("shell", "screencap", "-p", "/sdcard/runa-release-smoke.png")
        self.run("pull", "/sdcard/runa-release-smoke.png", str(self.output / (name + ".png")))

    def artwork(self):
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            root = self.tree()
            if self.node(root, "image") is not None and self.node(root, "image_status") is None:
                return root
            time.sleep(1)
        raise TimeoutError("Visible official artwork did not finish loading")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--timeout", type=int, default=90)
    parser.add_argument("--expected-version", default="1.1.0-native-preview.2")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    result = {"passed": False, "runId": uuid.uuid4().hex,
              "startedAt": datetime.now(timezone.utc).isoformat(), "ordersRequested": 0}
    args.output.joinpath("summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    try:
        result.update(run_checks(args))
        result["passed"] = True
    except Exception as error:
        result["error"] = str(error)
        raise
    finally:
        args.output.joinpath("summary.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result))


def run_checks(args):
    device = Device(args.adb, args.serial, args.output, args.timeout)
    info = device.run("shell", "dumpsys", "package", PACKAGE).decode("utf-8", "replace")
    flags = next((line.strip() for line in info.splitlines() if line.strip().startswith("flags=[")), None)
    if flags is None or "DEBUGGABLE" in flags:
        raise AssertionError("Requires the installed non-debuggable release payload")
    if "versionName=" + args.expected_version not in info:
        raise AssertionError("Unexpected app version")
    device.run("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
    root = device.ready()
    # Idle cold-start only: never interrupt real in-flight order handling.
    device.run("shell", "am", "force-stop", PACKAGE)
    device.run("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
    root = device.ready()
    device.capture("cold-restart", root)
    result = {"nonDebuggable": True, "idleColdRestart": True, "ordersRequested": 0, "categories": {}}
    for category in ("pc", "android", "fab"):
        device.tap(root, "tab_" + category)
        root = device.ready()
        device.capture(category + "-home", root)
        view = device.node(root, "catalog")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", view.get("bounds")))
        device.run("shell", "input", "swipe", str((x1 + x2) // 2), str(y2 - 20),
                   str((x1 + x2) // 2), str(y1 + 20), "400")
        root = device.artwork()
        device.capture(category + "-artwork", root)
        result["categories"][category] = {"readCompleted": True, "visibleArtworkLoaded": True}
    for panel, title in (("account", "账号与设置"), ("history", "领取记录")):
        device.tap(root, panel)
        root = device.tree()
        if not any(node.get("text") == title for node in root.iter("node")):
            raise AssertionError(panel + " native panel missing")
        if any(node.get("class") == "android.webkit.WebView" for node in root.iter("node")):
            raise AssertionError(panel + " panel unexpectedly contains a WebView")
        device.capture(panel, root)
        device.run("shell", "input", "keyevent", "4")
        root = device.ready()
    return result


if __name__ == "__main__":
    main()

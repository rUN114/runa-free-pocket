#!/usr/bin/env python3
"""Force-stop only after the isolated fixture's durable-permit checkpoint.

Install diagnostic app + test APK first. No app data, cookies or production prefs
are cleared. Example: python recovery_smoke.py --serial emulator-5554 --adb adb
--output outputs/recovery.json
"""
import argparse
import json
import queue
import subprocess
import threading
import time
from pathlib import Path

PACKAGE = "cn.run.freepocket"
RUNNER = PACKAGE + ".test/cn.run.freepocket.tests.NativeVerification"


class Instrumentation:
    def __init__(self, adb, serial, mode):
        self.process = subprocess.Popen(
            [adb, "-s", serial, "shell", "am", "instrument", "-w", "-r", "-e", "mode", mode, RUNNER],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            encoding="utf-8", errors="replace", bufsize=1)
        self.lines = []
        self.pending = queue.Queue()
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        try:
            for line in self.process.stdout:
                self.pending.put(line.rstrip("\r\n"))
        finally:
            self.pending.put(None)

    def next_line(self, deadline):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Instrumentation output deadline exceeded")
        try:
            line = self.pending.get(timeout=remaining)
        except queue.Empty as error:
            raise TimeoutError("Instrumentation output deadline exceeded") from error
        if line is not None:
            self.lines.append(line)
        return line

    def finish(self, deadline):
        while self.next_line(deadline) is not None:
            pass
        self.process.wait(timeout=max(0.1, deadline - time.monotonic()))
        self.reader.join(timeout=2)
        return "\n".join(self.lines)

    def close(self):
        if self.process.poll() is None:
            self.process.kill()
            self.process.wait(timeout=5)
        self.reader.join(timeout=2)
        self.process.stdout.close()


def results(lines):
    prefix = "INSTRUMENTATION_STATUS: testresult="
    return [json.loads(line[len(prefix):]) for line in lines if line.startswith(prefix)]


def require_success(run, mode):
    text = "\n".join(run.lines)
    if run.process.returncode != 0 or "INSTRUMENTATION_CODE: -1" not in text:
        raise RuntimeError(mode + " did not finish successfully")
    if "verification=passed: " + mode not in text:
        raise RuntimeError(mode + " verification result missing or failed")
    summaries = results(run.lines)
    if len(summaries) != 1 or summaries[0].get("runCompleted") is not True:
        raise RuntimeError(mode + " must provide exactly one completed testresult")
    return summaries[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit isolated Android device serial")
    parser.add_argument("--output", required=True, type=Path, help="Summary JSON output file")
    parser.add_argument("--adb", required=True, help="ADB executable path")
    args = parser.parse_args()
    summary = {"fixture": "native-recovery-fixture", "serial": args.serial,
               "passed": False, "expectedInterrupted": False, "runCompleted": False,
               "noNewPermits": False, "realOrders": 0}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    runs = []
    try:
        state = subprocess.run([args.adb, "-s", args.serial, "get-state"],
                               capture_output=True, text=True, timeout=15, check=True)
        if state.stdout.strip() != "device":
            raise RuntimeError("Selected device is not ready")
        seed = Instrumentation(args.adb, args.serial, "recoverySeed")
        runs.append(("seed", seed))
        deadline = time.monotonic() + 60
        checkpoint = False
        # sendStatus emits one status bundle; wait for its terminating status code.
        while True:
            line = seed.next_line(deadline)
            if line is None:
                raise RuntimeError("Seed ended before force-stop checkpoint")
            if line == "INSTRUMENTATION_STATUS: checkpoint=native-recovery-ready":
                checkpoint = True
            if checkpoint and line.startswith("INSTRUMENTATION_STATUS_CODE:"):
                values = results(seed.lines)
                if len(values) != 1 or values[0].get("expectedInterrupted") is not True or values[0].get("permits") != 2:
                    raise RuntimeError("Seed checkpoint lacks durable two-permit testresult")
                if seed.process.poll() is not None:
                    raise RuntimeError("Seed exited before external force-stop")
                break
        subprocess.run([args.adb, "-s", args.serial, "shell", "am", "force-stop", PACKAGE],
                       capture_output=True, text=True, timeout=15, check=True)
        seed.finish(time.monotonic() + 20)
        seed_text = "\n".join(seed.lines)
        # A crash is expected only for this externally interrupted seed, after checkpoint.
        if "INSTRUMENTATION_CODE: -1" in seed_text or "verification=failed:" in seed_text:
            raise RuntimeError("Seed completed/failed instead of remaining blocked until force-stop")
        summary["expectedInterrupted"] = True
        summary["seed"] = values[0]
        for mode, key in [("recoveryResume", "recovery"), ("commitPerformance", "performance")]:
            run = Instrumentation(args.adb, args.serial, mode)
            runs.append((key, run))
            run.finish(time.monotonic() + 90)
            summary[key] = require_success(run, mode)
        recovery = summary["recovery"]
        if recovery.get("noNewPermits") is not True or recovery.get("newPermits") != 0:
            raise RuntimeError("Recovery granted a new action permit")
        if recovery.get("status") != "uncertain" or recovery.get("reservations") != 1:
            raise RuntimeError("Recovery lost the unresolved reservation")
        if recovery.get("productAuthorized") is not True or recovery.get("submitAuthorized") is not True:
            raise RuntimeError("Recovery lost the original durable stage flags")
        performance = summary["performance"]
        if performance.get("samples") != 40 or performance.get("saveFailures") != 0 or performance.get("mainThread") is not True:
            raise RuntimeError("Commit measurement did not complete 40 main-thread successful saves")
        summary.update(passed=True, runCompleted=True, noNewPermits=True)
    except Exception as error:
        summary["error"] = str(error)
    finally:
        for name, run in runs:
            run.close()
            args.output.with_name(args.output.stem + "-" + name + ".txt").write_text(
                "\n".join(run.lines) + "\n", encoding="utf-8")
        args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import native_release_smoke as smoke


class ReleaseSmokeReportTest(unittest.TestCase):
    def test_failed_rerun_replaces_old_success_summary(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            summary = output / "summary.json"
            summary.write_text(json.dumps({"passed": True, "categories": {"old": True}}))
            arguments = ["native_release_smoke.py", "--adb", "adb", "--serial", "fixture",
                         "--output", str(output)]
            with patch("sys.argv", arguments), patch.object(smoke.Device, "run",
                    side_effect=RuntimeError("device disconnected")):
                with self.assertRaisesRegex(RuntimeError, "device disconnected"):
                    smoke.main()
            result = json.loads(summary.read_text())
            self.assertFalse(result["passed"])
            self.assertEqual("device disconnected", result["error"])
            self.assertNotIn("old", result.get("categories", {}))

    def test_running_rerun_invalidates_old_success_before_device_checks(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            summary = output / "summary.json"
            summary.write_text(json.dumps({"passed": True}))
            arguments = ["native_release_smoke.py", "--adb", "adb", "--serial", "fixture",
                         "--output", str(output)]
            def check_report(_):
                self.assertFalse(json.loads(summary.read_text())["passed"])
                return {"ordersRequested": 0}
            with patch("sys.argv", arguments), patch.object(smoke, "run_checks", side_effect=check_report), patch("builtins.print"):
                smoke.main()
            self.assertTrue(json.loads(summary.read_text())["passed"])


if __name__ == "__main__":
    unittest.main()

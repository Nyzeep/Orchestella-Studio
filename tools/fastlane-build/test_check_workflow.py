import copy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(HERE))

import check_zero_device_workflow as workflow  # noqa: E402

WORKFLOW = REPO / ".github/workflows/fastlane-zero-device.yml"


@unittest.skipIf(workflow.yaml is None, "PyYAML is not installed; CI installs it")
class WorkflowCheckTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document = workflow.load_workflow(WORKFLOW)

    def mutate(self):
        return copy.deepcopy(self.document)

    def assert_problem(self, document, text):
        problems = workflow.check_workflow(document)
        self.assertTrue(any(text in problem for problem in problems), problems)

    def test_checked_in_workflow_is_valid(self):
        self.assertEqual([], workflow.check_workflow(self.document))

    def test_continue_on_error_is_rejected(self):
        document = self.mutate()
        document["jobs"]["toolbox-and-licenses"]["steps"][0]["continue-on-error"] = True
        self.assert_problem(document, "continue-on-error")

    def test_adb_is_rejected(self):
        document = self.mutate()
        document["jobs"]["jvm-distcc"]["steps"].append(
            {"name": "device", "run": "adb devices"}
        )
        self.assert_problem(document, "adb/device command")

    def test_missing_required_job_is_rejected(self):
        document = self.mutate()
        del document["jobs"]["apk-bin-gate"]
        self.assert_problem(document, "missing jobs")

    def test_write_permission_is_rejected(self):
        document = self.mutate()
        document["permissions"]["contents"] = "write"
        self.assert_problem(document, "permissions.contents")

    def test_secret_reference_is_rejected(self):
        document = self.mutate()
        document["jobs"]["jvm-distcc"]["steps"].append(
            {"name": "token", "run": "echo ${{ secrets.TOKEN }}"}
        )
        self.assert_problem(document, "secret reference")

    def test_missing_apk_gate_step_is_rejected(self):
        document = self.mutate()
        for step in document["jobs"]["apk-bin-gate"]["steps"]:
            if "run" in step and "check-apk-16kb.sh" in step["run"]:
                step["run"] = "echo skipped"
        self.assert_problem(document, "check-apk-16kb.sh")

    def test_missing_reproducibility_step_is_rejected(self):
        document = self.mutate()
        for step in document["jobs"]["toolbox-and-licenses"]["steps"]:
            if "run" in step and "reproduce.py" in step["run"]:
                step["run"] = "echo skipped"
        self.assert_problem(document, "reproduce.py")

    def test_release_build_is_rejected(self):
        document = self.mutate()
        document["jobs"]["apk-bin-gate"]["steps"].append(
            {"name": "release", "run": "./gradlew :app:assembleRelease"}
        )
        self.assert_problem(document, "release build")

    def test_self_hosted_runner_is_rejected(self):
        document = self.mutate()
        document["jobs"]["jvm-distcc"]["runs-on"] = "self-hosted"
        self.assert_problem(document, "self-hosted runner")

    def test_invalid_yaml_is_rejected(self):
        path = Path(self.temp_dir()) / "bad.yml"
        path.write_text("jobs: [\n", encoding="utf-8")
        with self.assertRaises(ValueError):
            workflow.load_workflow(path)

    def temp_dir(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        return self.temp.name

    def test_cli_returns_zero(self):
        result = subprocess.run(
            [sys.executable, str(HERE / "check_zero_device_workflow.py")],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("WORKFLOW_OK", result.stdout)


if __name__ == "__main__":
    unittest.main()

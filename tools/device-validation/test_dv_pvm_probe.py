"""Tests for the DV-PVM-01 probe host driver (parsing/aggregation seam)."""

import json
import unittest

import dv_pvm_probe


class ParseProbeLinesTest(unittest.TestCase):
    def test_parses_logcat_prefixed_lines(self):
        text = (
            "10-24 19:00:00.123  1234  1234 I PVM_PROBE(1234): "
            '{"stage":"compile","status":"OK","iter":1,"compile_ms":12,'
            '"ru_maxrss_kb":4096,"obj_bytes":512,"llvm_version":"tcc",'
            '"vm_cpu_topology":1}\n'
            "10-24 19:00:00.200  1234  1234 I libc    : unrelated line\n"
            "PVM_PROBE "
            '{"stage":"done","status":"OK","iter":0,"compile_ms":30,'
            '"ru_maxrss_kb":4096,"obj_bytes":0,"llvm_version":"tcc",'
            '"vm_cpu_topology":1}\n'
        )
        lines = dv_pvm_probe.parse_probe_lines(text)
        self.assertEqual(2, len(lines))
        self.assertEqual("compile", lines[0]["stage"])
        self.assertEqual(1, lines[0]["iter"])
        self.assertEqual("done", lines[1]["stage"])

    def test_skips_malformed_payload_lines(self):
        text = (
            "PVM_PROBE {not json}\n"
            "PVM_PROBE "
            '{"stage":"compile","status":"OK","iter":2,"compile_ms":9,'
            '"ru_maxrss_kb":4096,"obj_bytes":512,"llvm_version":"tcc",'
            '"vm_cpu_topology":1}\n'
        )
        lines = dv_pvm_probe.parse_probe_lines(text)
        self.assertEqual(1, len(lines))
        self.assertEqual(2, lines[0]["iter"])

    def test_keeps_required_fields_only(self):
        text = (
            "PVM_PROBE "
            '{"stage":"compile","status":"OK","iter":1,"compile_ms":12,'
            '"ru_maxrss_kb":4096,"obj_bytes":512,"llvm_version":"v",'
            '"vm_cpu_topology":1,"extra":"dropped"}\n'
        )
        lines = dv_pvm_probe.parse_probe_lines(text)
        self.assertEqual(1, len(lines))
        self.assertEqual({"stage", "status", "iter", "compile_ms",
                          "ru_maxrss_kb", "obj_bytes", "llvm_version",
                          "vm_cpu_topology"}, set(lines[0]))


class AggregateIterationsTest(unittest.TestCase):
    @staticmethod
    def compile_line(iter_, ms, rss):
        return {"stage": "compile", "status": "OK", "iter": iter_, "compile_ms": ms,
                "ru_maxrss_kb": rss, "obj_bytes": 512, "llvm_version": "v",
                "vm_cpu_topology": 1}

    def test_aggregates_ten_iterations(self):
        lines = [self.compile_line(i + 1, 10 + i, 4096) for i in range(10)]
        stats = dv_pvm_probe.aggregate_iterations(lines, expected_cycles=10)
        self.assertTrue(stats["all_ok"])
        self.assertEqual(10, stats["iterations"])
        self.assertEqual(14, stats["p50_compile_ms"])
        self.assertEqual(19, stats["p95_compile_ms"])
        self.assertEqual(4096, stats["rss_first_kb"])
        self.assertEqual(4096, stats["rss_last_kb"])
        self.assertEqual(0.0, stats["rss_growth_pct"])

    def test_more_iterations_than_cycles_still_pass(self):
        lines = [dict({"stage": "compile", "status": "OK", "iter": i,
                       "compile_ms": 5, "ru_maxrss_kb": 100,
                       "obj_bytes": 10, "llvm_version": "tcc",
                       "vm_cpu_topology": 1})
                 for i in range(1, 4)]
        stats = dv_pvm_probe.aggregate_iterations(lines, expected_cycles=1)
        self.assertTrue(stats["all_ok"])

    def test_rss_growth_over_limit_is_reported(self):
        lines = [self.compile_line(1, 10, 1000), self.compile_line(2, 10, 1500)]
        stats = dv_pvm_probe.aggregate_iterations(lines, expected_cycles=2)
        self.assertAlmostEqual(50.0, stats["rss_growth_pct"])
        self.assertFalse(stats["within_rss_budget"])

    def test_missing_iterations_fail(self):
        lines = [self.compile_line(1, 10, 1000), self.compile_line(2, 10, 1000)]
        stats = dv_pvm_probe.aggregate_iterations(lines, expected_cycles=10)
        self.assertFalse(stats["all_ok"])

    def test_failed_status_is_not_ok(self):
        lines = [self.compile_line(1, 10, 1000),
                 dict(self.compile_line(2, 10, 1000), status="COMPILE_ERROR")]
        stats = dv_pvm_probe.aggregate_iterations(lines, expected_cycles=2)
        self.assertFalse(stats["all_ok"])
        self.assertEqual("COMPILE_ERROR", stats["first_failure_status"])

    def test_empty_is_all_ok_false(self):
        stats = dv_pvm_probe.aggregate_iterations([], expected_cycles=10)
        self.assertFalse(stats["all_ok"])
        self.assertEqual(0, stats["iterations"])


class SummarizeTest(unittest.TestCase):
    def evidence(self, **overrides):
        base = {
            "kind": "pvm_probe",
            "payload_name": "libpvm_probe.so",
            "memory_mb": 2048,
            "run_token_sha256": "a" * 64,
            "status_passed": True,
            "failure_code": None,
            "exit_code": 0,
            "stop_confirmed": True,
            "deleted": True,
            "payload_ready_ms": 1234,
            "device": {"sdk_int": 36, "abi": "arm64-v8a", "capabilities_raw": 1},
            "notes": None,
        }
        base.update(overrides)
        return base

    def compile_lines(self, count=10, rss=4096):
        return [{"stage": "compile", "status": "OK", "iter": i + 1,
                 "compile_ms": 10, "ru_maxrss_kb": rss, "obj_bytes": 512,
                 "llvm_version": "v", "vm_cpu_topology": 1}
                for i in range(count)]

    def test_pass_summary(self):
        summary = dv_pvm_probe.summarize(self.evidence(), self.compile_lines(), 10)
        self.assertEqual("PASS", summary["verdict"])
        self.assertEqual("libpvm_probe.so", summary["payload_name"])
        self.assertTrue(summary["stop_confirmed"])

    def test_failure_code_becomes_verdict(self):
        evidence = self.evidence(status_passed=False, failure_code="QA_CREATE_FAILED")
        summary = dv_pvm_probe.summarize(evidence, [], 10)
        self.assertEqual("FAIL", summary["verdict"])
        self.assertEqual("QA_CREATE_FAILED", summary["failure_code"])

    def test_rss_growth_fails_passing_run(self):
        lines = self.compile_lines(rss=2000)
        lines[9]["ru_maxrss_kb"] = 3000
        summary = dv_pvm_probe.summarize(self.evidence(), lines, 10)
        self.assertEqual("FAIL", summary["verdict"])
        self.assertEqual("RSS_GROWTH", summary["failure_code"])

    def test_summary_is_redacted(self):
        summary = dv_pvm_probe.summarize(self.evidence(), self.compile_lines(), 10)
        flat = json.dumps(summary)
        # The raw run token never leaves the evidence flow; only its SHA-256
        # is carried forward (same convention as avf_device_mode.py).
        self.assertNotIn("qaRunToken", flat)
        self.assertEqual("a" * 64, summary["run_token_sha256"])


class SummarizeLoadTest(unittest.TestCase):
    def load_lines(self, ok=True):
        status = "OK" if ok else "PAYLOAD_LOAD_FAILED"
        loads = [{"stage": "load", "status": status, "iter": i + 1,
                  "compile_ms": 3, "ru_maxrss_kb": 1024, "obj_bytes": 0,
                  "llvm_version": "libLLVM.so: <p>", "vm_cpu_topology": 1}
                 for i in range(3)]
        done = {"stage": "done", "status": "OK" if ok else "PAYLOAD_LOAD_FAILED",
                "iter": 0, "compile_ms": 10, "ru_maxrss_kb": 1024,
                "obj_bytes": 0, "llvm_version": "dlopen probe",
                "vm_cpu_topology": 1}
        return loads + [done]

    def evidence(self, **overrides):
        base = {
            "kind": "pvm_probe",
            "payload_name": "libpvm_probe_clangload.so",
            "memory_mb": 2048,
            "run_token_sha256": "b" * 64,
            "status_passed": True,
            "failure_code": None,
            "exit_code": 0,
            "stop_confirmed": True,
            "deleted": True,
            "payload_ready_ms": 900,
            "device": {"sdk_int": 36, "abi": "arm64-v8a", "capabilities_raw": 1},
            "notes": None,
        }
        base.update(overrides)
        return base

    def test_pass(self):
        summary = dv_pvm_probe.summarize_load(self.evidence(), self.load_lines())
        self.assertEqual("PASS", summary["verdict"])
        self.assertEqual(3, len(summary["loads"]))

    def test_dlload_failure_becomes_verdict(self):
        lines = self.load_lines(ok=False)
        summary = dv_pvm_probe.summarize_load(self.evidence(), lines)
        self.assertEqual("FAIL", summary["verdict"])
        self.assertEqual("PAYLOAD_LOAD_FAILED", summary["failure_code"])

    def test_missing_load_stage(self):
        lines = [line for line in self.load_lines() if line.get("stage") != "load"
                 or line.get("iter") != 2]
        summary = dv_pvm_probe.summarize_load(self.evidence(), lines)
        self.assertEqual("FAIL", summary["verdict"])
        self.assertEqual("LOAD_STAGES_INCOMPLETE", summary["failure_code"])



class ParseDeviceListTest(unittest.TestCase):
    def test_single_device(self):
        text = (
            "* daemon not running; starting now at tcp:5037\n"
            "List of devices attached\n"
            "abc123\tdevice usb:1-1 product:x model:y\n"
        )
        self.assertEqual(["abc123"], dv_pvm_probe.parse_device_list(text))

    def test_unauthorized_and_offline_excluded(self):
        text = (
            "List of devices attached\n"
            "abc\tdevice\n"
            "def\tunauthorized\n"
            "ghi\toffline\n"
        )
        self.assertEqual(["abc"], dv_pvm_probe.parse_device_list(text))


class MergeProbeLinesTest(unittest.TestCase):
    def test_vsock_lines_are_parsed_when_logcat_is_empty(self):
        evidence = {"probe_lines": [
            'PVM_PROBE {"stage":"compile","status":"OK","iter":1,'
            '"compile_ms":5,"ru_maxrss_kb":100,"obj_bytes":10,'
            '"llvm_version":"tcc","vm_cpu_topology":1}']}
        merged = dv_pvm_probe.merge_probe_lines([], evidence)
        self.assertEqual(len(merged), 1)
        self.assertEqual(merged[0]["stage"], "compile")

    def test_duplicates_across_sources_are_dropped(self):
        line = {"stage": "compile", "status": "OK", "iter": 1,
                "compile_ms": 5, "ru_maxrss_kb": 100, "obj_bytes": 10,
                "llvm_version": "tcc", "vm_cpu_topology": 1}
        evidence = {"probe_lines": [
            'PVM_PROBE {"stage":"compile","status":"OK","iter":1,'
            '"compile_ms":5,"ru_maxrss_kb":100,"obj_bytes":10,'
            '"llvm_version":"tcc","vm_cpu_topology":1}']}
        merged = dv_pvm_probe.merge_probe_lines([line], evidence)
        self.assertEqual(len(merged), 1)

    def test_non_list_probe_lines_are_ignored(self):
        self.assertEqual(dv_pvm_probe.merge_probe_lines([], {}), [])
        self.assertEqual(
            dv_pvm_probe.merge_probe_lines([], {"probe_lines": "bad"}), [])


if __name__ == "__main__":
    unittest.main()

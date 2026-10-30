#!/usr/bin/env python3
"""Unit tests for check_readme_assets.py (no network, no real git repo needed)."""

from __future__ import annotations

import sys
import subprocess
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_readme_assets as gate  # noqa: E402


class CollectLocalImagesTest(unittest.TestCase):
    def test_markdown_table_of_screenshots_keeps_order_and_drops_remotes(self) -> None:
        text = (
            "| a | b |\n| :---: | :---: |\n"
            "| ![x](docs/assets/screenshots/a.jpg) | ![y](docs/assets/screenshots/b.png) |\n"
            "![badge](https://img.shields.io/badge/x-y-z.svg)\n"
        )
        self.assertEqual(
            ["docs/assets/screenshots/a.jpg", "docs/assets/screenshots/b.png"],
            gate.collect_local_images(text),
        )

    def test_html_img_and_duplicates_and_fragments(self) -> None:
        text = (
            '<img src="docs/assets/branding/mascot.png" width="128" />\n'
            "![again](./docs/assets/branding/mascot.png)\n"
            '![title](docs/assets/screenshots/c.jpg "caption")\n'
        )
        self.assertEqual(
            ["docs/assets/branding/mascot.png", "docs/assets/screenshots/c.jpg"],
            gate.collect_local_images(text),
        )

    def test_no_images_is_empty(self) -> None:
        self.assertEqual([], gate.collect_local_images("# 标题\n正文没有图片。\n"))


class ResolveMissingTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        (self.root / "docs" / "assets").mkdir(parents=True)
        (self.root / "docs" / "assets" / "present.png").write_bytes(b"x")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_present_and_tracked_is_clean(self) -> None:
        self.assertEqual([], gate.resolve_missing(["docs/assets/present.png"], self.root, {"docs/assets/present.png"}))

    def test_present_but_ignored_is_the_session_26_bug(self) -> None:
        problems = gate.resolve_missing(["docs/assets/present.png"], self.root, set())
        self.assertEqual(1, len(problems))
        self.assertIn("not tracked by git", problems[0][1])

    def test_absent_file_is_reported_separately(self) -> None:
        problems = gate.resolve_missing(["docs/assets/gone.png"], self.root, set())
        self.assertEqual([("docs/assets/gone.png", "missing from the working tree")], problems)


class CommandLineTest(unittest.TestCase):
    """End-to-end on a throwaway repo: the gate must fail on a 404-in-waiting."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _stage(self, *paths: str) -> None:
        subprocess.run(["git", "add", "--", *paths], cwd=self.root, check=True)

    def test_missing_image_fails_and_tracked_image_passes(self) -> None:
        (self.root / "docs" / "assets").mkdir(parents=True)
        (self.root / "docs" / "assets" / "ok.png").write_bytes(b"x")
        (self.root / "README.md").write_text(
            "![ok](docs/assets/ok.png)\n![gone](docs/assets/gone.png)\n", encoding="utf-8"
        )
        self._stage("README.md", "docs/assets/ok.png")
        self.assertEqual(1, gate.main(["--repo-root", str(self.root), "--readmes", "README.md"]))

        (self.root / "README.md").write_text("![ok](docs/assets/ok.png)\n", encoding="utf-8")
        self._stage("README.md")
        self.assertEqual(0, gate.main(["--repo-root", str(self.root), "--readmes", "README.md"]))

    def test_file_present_but_never_staged_fails(self) -> None:
        (self.root / "docs" / "assets").mkdir(parents=True)
        (self.root / "docs" / "assets" / "ignored.png").write_bytes(b"x")
        (self.root / "README.md").write_text("![x](docs/assets/ignored.png)\n", encoding="utf-8")
        self._stage("README.md")
        self.assertEqual(1, gate.main(["--repo-root", str(self.root), "--readmes", "README.md"]))


if __name__ == "__main__":
    unittest.main()

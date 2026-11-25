import importlib.util
import os
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "sync_private_overlay.py"


def load_script():
    spec = importlib.util.spec_from_file_location("sync_private_overlay", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SyncPrivateOverlayTest(unittest.TestCase):
    def setUp(self):
        self.module = load_script()
        self.module.SHADOWED_JAVA = [("visualizer/e51", "dir")]
        self.module.SHADOWED_ASSETS = [("e52_highway", "dir")]
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.src_java = root / "source" / "java"
        self.src_assets = root / "source" / "assets"
        self.ov_java = root / "overlay" / "java"
        self.ov_assets = root / "overlay" / "assets"
        for path in (self.src_java, self.src_assets, self.ov_java, self.ov_assets):
            path.mkdir(parents=True)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, root, rel, text):
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def compare(self):
        return self.module.compare_overlay(
            str(self.src_java), str(self.src_assets), str(self.ov_java), str(self.ov_assets)
        )

    def test_identical_trees_have_no_drift(self):
        self.write(self.src_java, "visualizer/e51/Engine.kt", "engine")
        self.write(self.ov_java, "visualizer/e51/Engine.kt", "engine")
        self.write(self.src_assets, "e52_highway/sky.png", "sky")
        self.write(self.ov_assets, "e52_highway/sky.png", "sky")

        self.assertEqual([], self.compare())

    def test_missing_required_source_is_drift_even_without_destination(self):
        drift = self.compare()

        self.assertIn("missing-source:visualizer/e51", drift)
        self.assertIn("missing-source:assets/e52_highway", drift)

    def test_stale_destination_only_file_is_drift(self):
        self.write(self.src_java, "visualizer/e51/Engine.kt", "engine")
        self.write(self.ov_java, "visualizer/e51/Engine.kt", "engine")
        self.write(self.src_assets, "e52_highway/sky.png", "sky")
        self.write(self.ov_assets, "e52_highway/sky.png", "sky")
        self.write(self.ov_java, "visualizer/removed/Old.kt", "old")

        self.assertIn("extra:visualizer/removed/Old.kt", self.compare())


if __name__ == "__main__":
    unittest.main()

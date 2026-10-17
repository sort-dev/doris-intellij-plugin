#!/usr/bin/env python3
import io
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("verify-release-zip.py")
PLUGIN_ID = "dev.sort.doris-intellij-plugin"
NAME = "doris-intellij-plugin"
DEV = "1.6.1-dev.202610160000+123456abcdef"


def plugin_zip(path: Path, version: str, descriptor_version: str | None = None, plugin_id: str = PLUGIN_ID):
    jar = io.BytesIO()
    with zipfile.ZipFile(jar, "w") as inner:
        inner.writestr("META-INF/plugin.xml",
                       f"<idea-plugin>\n  <id>{plugin_id}</id>\n  <version>{descriptor_version or version}</version>\n</idea-plugin>")
    with zipfile.ZipFile(path, "w") as outer:
        outer.writestr(f"{NAME}/lib/{NAME}-{version}.jar", jar.getvalue())
        outer.writestr(f"{NAME}/lib/antlr4-runtime-4.13.1.jar", b"")


class VerifyReleaseZipTest(unittest.TestCase):
    def setUp(self):
        self.dist = Path(self.enterContext(tempfile.TemporaryDirectory()))

    def run_script(self, version="1.6.1", plugin_id=PLUGIN_ID):
        return subprocess.run([sys.executable, SCRIPT, self.dist, plugin_id, version], capture_output=True, text=True)

    def assertRejected(self, message, **kwargs):
        result = self.run_script(**kwargs)
        self.assertNotEqual(0, result.returncode)
        self.assertIn(message, result.stderr)

    def test_stable_release_zip_passes_and_prints_its_path(self):
        plugin_zip(self.dist / f"{NAME}-1.6.1.zip", "1.6.1")
        result = self.run_script()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(str(self.dist / f"{NAME}-1.6.1.zip"), result.stdout.strip())

    def test_dev_zip_renamed_to_the_release_name_is_rejected(self):
        plugin_zip(self.dist / f"{NAME}-1.6.1.zip", DEV)
        self.assertRejected(f"expected main jar {NAME}/lib/{NAME}-1.6.1.jar")

    def test_descriptor_version_and_id_must_match(self):
        plugin_zip(self.dist / f"{NAME}-1.6.1.zip", "1.6.1", descriptor_version=DEV)
        self.assertRejected("plugin.xml declares id")
        (self.dist / f"{NAME}-1.6.1.zip").unlink()
        plugin_zip(self.dist / f"{NAME}-1.6.1.zip", "1.6.1", plugin_id="dev.sort.other-plugin")
        self.assertRejected("plugin.xml declares id 'dev.sort.other-plugin'")

    def test_lookalike_versions_do_not_match(self):
        plugin_zip(self.dist / f"{NAME}-1x6x1.zip", "1x6x1")
        self.assertRejected(f"expected exactly {NAME}-1.6.1.zip")

    def test_dev_and_extra_artifacts_are_rejected(self):
        plugin_zip(self.dist / f"{NAME}-{DEV}.zip", DEV)
        self.assertRejected("found")
        plugin_zip(self.dist / f"{NAME}-1.6.1.zip", "1.6.1")
        self.assertRejected("expected exactly")
        self.assertRejected("is not a release version", version=DEV)

    def test_missing_artifact_is_rejected(self):
        self.assertRejected("found []")


if __name__ == "__main__":
    unittest.main()

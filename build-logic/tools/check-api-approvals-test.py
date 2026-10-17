import contextlib
from collections import Counter
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('audit', Path(__file__).with_name('check-api-approvals.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)

KNOWN = 'Internal method platform.Factory.isSupported(Version version) : boolean is overridden in class plugin.Gate.'
NEW = 'Internal method platform.Factory.isSupported(Version version) : boolean is overridden in class shared.Gate.'


class ApprovalTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir='/tmp/opencode' if Path('/tmp/opencode').is_dir() else None)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.report = self.root / 'IU-263' / 'plugins' / 'plugin' / '1.0'
        self.report.mkdir(parents=True)
        (self.report / 'verification-verdict.txt').write_text('Compatible')

    def check(self, rows, approved):
        (self.report / 'internal-api-usages.txt').write_text('\n'.join(row + ' This method is internal.' for row in rows))
        with contextlib.redirect_stdout(io.StringIO()):
            return audit.audit(self.root, 'plugin', '1.0', ['IU-263'], Counter(approved))

    def test_exact_approved_call_site_passes_without_removing_report(self):
        self.assertEqual(0, self.check([KNOWN], [KNOWN]))
        self.assertIn(KNOWN, (self.report / 'internal-api-usages.txt').read_text())

    def test_same_api_in_a_different_class_is_not_approved(self):
        self.assertEqual(1, self.check([NEW], [KNOWN]))

    def test_extra_usage_is_not_covered_by_one_approval(self):
        self.assertEqual(1, self.check([KNOWN, KNOWN], [KNOWN]))

    def test_missing_target_is_not_a_clean_report(self):
        with self.assertRaises(ValueError):
            audit.audit(self.root, 'plugin', '1.0', ['IU-262'], Counter())

    def test_unknown_report_format_fails_closed(self):
        path = self.report / 'internal-api-usages.txt'
        path.write_text('Unexpected report format')
        with self.assertRaises(ValueError):
            audit.internal_usages(path)

    def test_own_experimental_markers_block_even_when_internals_are_approved(self):
        (self.report / 'experimental-api-usages.txt').write_text(
            'Experimental API interface dev.brikk.house.sql.intellij.pipe.PipeDialect is referenced in plugin.Dialect. This interface may change.\n')
        self.assertEqual(1, self.check([KNOWN], [KNOWN]))

    def test_platform_experimental_findings_remain_a_separate_review_item(self):
        (self.report / 'experimental-api-usages.txt').write_text(
            'Experimental API interface com.intellij.database.dataSource.DatabaseConnectionInterceptor is referenced in plugin.Interceptor. This interface may change.\n')
        self.assertEqual(0, self.check([KNOWN], [KNOWN]))


if __name__ == '__main__':
    unittest.main()

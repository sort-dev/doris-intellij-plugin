import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('importer', Path(__file__).parents[1] / 'import_doris_function_docs.py')
importer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(importer)

class ImportDocsTest(unittest.TestCase):
    def test_frontmatter_title_aliases_and_body(self):
        title, body = importer.parse_page('---\n{"title":"BITMAP_AND_NOT,BITMAP_ANDNOT"}\n---\n<!-- license -->\n## 描述\n按位运算')
        self.assertEqual(title, 'BITMAP_AND_NOT,BITMAP_ANDNOT')
        self.assertNotIn('license', body)
        self.assertEqual(importer.function_names(title, 'bitmap-and-not'), ['BITMAP_AND_NOT', 'BITMAP_ANDNOT'])
        self.assertEqual(importer.function_names('LCASE/LOWER', 'lcase'), ['LCASE', 'LOWER'])
        self.assertEqual(importer.function_names('S3 | Table Valued Functions', 's3'), ['S3'])
        self.assertEqual(importer.function_names('概述', 'overview'), [])

    def test_aliases_only_from_alias_and_syntax_sections(self):
        body = "## 别名\n- POWER\n- `DPOW`\n## 语法\n```sql\nPOW(<a>, <b>)\n```\n## 示例\nSELECT ABS(POW(2,3));"
        self.assertEqual(importer.documented_names(body), ['POWER', 'DPOW', 'POW'])
        self.assertNotIn('ABS', importer.documented_names(body))

    def test_nested_examples_are_not_function_aliases(self):
        body = "## 语法\n```sql\nARRAY_APPLY(<array>, <operator>, <value>)\n```\n### 示例\n```sql\nPROPERTIES (\"replication_num\"=\"1\")\n```"
        self.assertEqual(importer.documented_names(body), ['ARRAY_APPLY'])

    def test_sibling_and_parent_links_use_source_file_location(self):
        base = 'https://doris.apache.org/zh-CN/docs/4.x/sql-manual/sql-functions/group/f/'
        self.assertEqual(importer.normalize_link('./other.md', base), base.replace('f/', 'other/'))
        self.assertEqual(importer.normalize_link('../other.md', base), 'https://doris.apache.org/zh-CN/docs/4.x/sql-manual/sql-functions/other/')
        self.assertEqual(importer.normalize_link('#参数', base), base + '#参数')

    def test_stale_current_conversion_link_stays_in_selected_version(self):
        base = 'https://doris.apache.org/zh-CN/docs/4.x/sql-manual/sql-functions/scalar-functions/date-time-functions/date-trunc/'
        link = '../../../../../current/sql-manual/basic-element/sql-data-types/conversion/datetime-conversion'
        expected = 'https://doris.apache.org/zh-CN/docs/4.x/sql-manual/basic-element/sql-data-types/conversion/datetime-conversion'
        self.assertEqual(importer.normalize_link(link, base), expected)

    def test_english_section_labels_are_localized(self):
        rendered = importer.render_page('LCM', '## Parameters\n\n| Parameter | Description |\n|---|---|\n| a | 第一个整数 |\n\n## Return Value\n返回整数', 'https://doris.apache.org/')
        self.assertIn('参数', rendered)
        self.assertIn('返回值', rendered)
        self.assertNotIn('>Parameters<', rendered)

    def test_render_tables_code_and_safe_links(self):
        body = '## 参数\n\n| 参数 | 说明 |\n|---|---|\n| x | 中文参数 |\n\n```sql\nSELECT a < b;\n```\n\n[相关](./other.md)\n\n<script>alert(1)</script>\n\n![图](https://evil.invalid/pixel)\n'
        html = importer.render_page('F', body, 'https://doris.apache.org/zh-CN/docs/4.x/sql-manual/sql-functions/group/f/')
        self.assertIn('<table>', html)
        self.assertIn('SELECT a &lt; b;', html)
        self.assertIn('https://doris.apache.org/zh-CN/docs/4.x/sql-manual/sql-functions/group/other/', html)
        self.assertNotIn('<script', html)
        self.assertNotIn('<img', html)
        self.assertIn('官方中文文档', html)

    def test_generic_type_placeholders_remain_visible(self):
        rendered = importer.render_page('F', '参数类型为 ARRAY<T>，返回 Nullable<JSON>。', 'https://doris.apache.org/')
        self.assertIn('ARRAY&lt;T&gt;', rendered)
        self.assertIn('Nullable&lt;JSON&gt;', rendered)

    def test_unsafe_href_is_not_rendered(self):
        html = importer.render_page('F', '<a href="javascript:alert(1)" onclick="bad()">恶意</a>', 'https://doris.apache.org/zh-CN/docs/4.x/f/')
        self.assertNotIn('href="javascript:', html)
        self.assertNotIn('onclick=', html)

if __name__ == '__main__':
    unittest.main()

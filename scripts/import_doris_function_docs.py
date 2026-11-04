#!/usr/bin/env python3
"""Import licensed Apache Doris 4.x Chinese function pages into offline IDE resources.

Requires markdown-it-py==4.2.0 (generation only, never a plugin runtime dependency).
"""
import argparse
import html
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import urljoin, urlsplit, urlunsplit
from markdown_it import MarkdownIt

SOURCE_DIR = 'i18n/zh-CN/docusaurus-plugin-content-docs/version-4.x/sql-manual/sql-functions'
BASE_URL = 'https://doris.apache.org/zh-CN/docs/4.x/sql-manual/sql-functions/'
LOCALE_ROOT = 'i18n/zh-CN/docusaurus-plugin-content-docs/version-4.x'
EXTRA_PAGES = {'CAST': 'sql-manual/basic-element/sql-data-types/conversion/cast-expr.md'}
NAME = re.compile(r'^[A-Z][A-Z0-9_]*$')
MARKDOWN = MarkdownIt('commonmark', {'html': True}).enable('table')


def parse_page(text):
    title = ''
    match = re.match(r'^---\s*\n(.*?)\n---\s*\n', text, re.S)
    if match:
        metadata = match[1]
        try:
            title = json.loads(metadata).get('title', '')
        except ValueError:
            field = re.search(r'^title:\s*[\'\"]?(.*?)[\'\"]?\s*$', metadata, re.M)
            if field:
                title = field[1]
        text = text[match.end():]
    text = re.sub(r'<!--.*?-->', '', text, flags=re.S).strip()
    if not title:
        heading = re.search(r'^#\s+(.+)', text, re.M)
        title = heading[1] if heading else ''
    return title, text


def function_names(title, stem):
    if stem.lower() in {'overview', 'index'}:
        return []
    primary = title.split('|', 1)[0].strip().upper().replace('-', '_')
    names = [x.strip() for x in re.split(r'[,/，]', primary)]
    names = [x for x in names if NAME.fullmatch(x)]
    filename = stem.upper().replace('-', '_')
    if NAME.fullmatch(filename):
        names.append(filename)
    return list(dict.fromkeys(names))


def documented_names(body):
    names = []
    for heading, section in re.findall(r'^#{2,6} ([^\n]+)\n(.*?)(?=^#{1,6} |\Z)', body, re.M | re.S):
        if heading.strip().lower() in {'别名', 'aliases', 'alias'}:
            for line in section.splitlines():
                candidate = re.sub(r'^[-*]\s*', '', line.strip()).strip('` ').upper()
                if NAME.fullmatch(candidate):
                    names.append(candidate)
        elif heading.strip().lower() in {'语法', 'syntax'}:
            # Only documented invocation names, not unrelated functions in examples.
            names.extend(re.findall(r'^\s*([A-Za-z][A-Za-z0-9_]*)\s*\(', section, re.M))
    return list(dict.fromkeys(n.upper() for n in names))


def localize_labels(body):
    labels = {'description': '描述', 'syntax': '语法', 'parameters': '参数', 'parameter': '参数',
              'return value': '返回值', 'return values': '返回值', 'example': '示例',
              'examples': '示例', 'keywords': '关键词', 'aliases': '别名', 'alias': '别名'}
    body = re.sub(r'^(#{1,6}) (.+)$', lambda m: m[1] + ' ' + labels.get(m[2].strip().lower(), m[2]), body, flags=re.M)
    body = body.replace('| Parameter | Description |', '| 参数 | 说明 |')
    translations = {
        'Returns the length of a string or binary value in bits.': '返回字符串或二进制值的位数。',
        'The string value for which the length is returned.': '需要计算位数的字符串值。',
        'Returns the number of bits occupied by `<str>` in the binary representation, including all 0 and 1.':
            '返回 `<str>` 的二进制表示所占的位数，包括其中的所有 0 和 1。',
    }
    for source, translated in translations.items():
        body = body.replace(source, translated)
    # Docusaurus admonition markers have no meaning in the IDE renderer.
    body = re.sub(r'^:::(?:tip|note|warning|caution|info|danger)?[^\n]*$', '', body, flags=re.M)
    return body


def normalize_link(link, url):
    # Docusaurus resolves relative Markdown links from the source FILE, not the
    # pretty URL directory. Fragment-only links keep the current pretty URL.
    reference = url if link.startswith('#') else url.rstrip('/') + '.md'
    absolute = urljoin(reference, link)
    parts = urlsplit(absolute)
    if parts.scheme != 'https' or not parts.netloc:
        return None
    path = re.sub(r'\.mdx?$', '/', parts.path)
    # Some version-4.x sources retain repository-relative 'current' conversion links,
    # which are not public Docusaurus routes. Keep these in the imported 4.x version.
    if not urlsplit(link).scheme and parts.netloc == 'doris.apache.org' and '/sql-manual/basic-element/sql-data-types/conversion/' in path:
        path = path.replace('/docs/current/', '/docs/4.x/').replace('/docs/4.x/current/', '/docs/4.x/')
    return urlunsplit((parts.scheme, parts.netloc, path, parts.query, parts.fragment))


class SafeHtml(HTMLParser):
    ALLOWED = {'p', 'br', 'pre', 'code', 'strong', 'b', 'em', 'i', 'ul', 'ol', 'li',
               'table', 'thead', 'tbody', 'tr', 'th', 'td', 'h1', 'h2', 'h3', 'h4',
               'h5', 'h6', 'blockquote', 'hr', 'a', 'del', 'sup', 'sub', 'div', 'span'}
    VOID = {'br', 'hr'}
    DROP = {'script', 'style', 'iframe', 'object', 'svg', 'form'}

    def __init__(self, url):
        super().__init__(convert_charrefs=True)
        self.url = url
        self.output = []
        self.drop_depth = 0

    def handle_starttag(self, tag, attrs):
        if tag in self.DROP:
            self.drop_depth += 1
        if self.drop_depth:
            return
        if tag == 'img':
            alt = dict(attrs).get('alt', '')
            self.output.append(html.escape(alt))
            return
        if tag not in self.ALLOWED:
            self.output.append(html.escape(self.get_starttag_text(), quote=False))
            return
        safe = ''
        if tag == 'a':
            url = normalize_link(dict(attrs).get('href', ''), self.url)
            if url:
                safe = f' href="{html.escape(url, quote=True)}"'
        if tag in {'td', 'th'}:
            for key in ['colspan', 'rowspan']:
                value = dict(attrs).get(key, '')
                if value.isdigit():
                    safe += f' {key}="{value}"'
        self.output.append(f'<{tag}{safe}>')

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in self.VOID and tag != 'img':
            self.handle_endtag(tag)

    def handle_endtag(self, tag):
        if tag in self.DROP and self.drop_depth:
            self.drop_depth -= 1
            return
        if not self.drop_depth and tag in self.ALLOWED and tag not in self.VOID:
            self.output.append(f'</{tag}>')

    def handle_data(self, data):
        if not self.drop_depth:
            self.output.append(html.escape(data, quote=False))


def render_page(title, body, url):
    parser = SafeHtml(url)
    parser.feed(MARKDOWN.render(localize_labels(body)))
    parser.close()
    return (f'<div class="definition"><pre>{html.escape(title)}</pre></div>\n'
            '<div class="content">\n' + ''.join(parser.output) +
            f'<p><a href="{html.escape(url, quote=True)}">Apache Doris 4.x 官方中文文档</a></p>\n'
            '<p>文档来源：Apache Software Foundation；Apache License 2.0。离线排版已作适配。</p>\n</div>\n')


def generate(checkout, output, catalog_path):
    commit = subprocess.check_output(['git', '-C', str(checkout), 'rev-parse', 'HEAD'], text=True).strip()
    source = checkout / SOURCE_DIR
    index, candidates, pages, quality, urls = {}, {}, [], {}, {}
    sources = [(path, None) for path in sorted(source.rglob('*.md'))]
    sources.extend((checkout / LOCALE_ROOT / relative, name) for name, relative in EXTRA_PAGES.items())
    for path, extra_name in sources:
        raw = path.read_text()
        if re.search(r'"draft"\s*:\s*true', raw.split('---', 2)[1] if raw.startswith('---') else ''):
            continue
        title, body = parse_page(raw)
        names = [extra_name] if extra_name else function_names(title, path.stem)
        if names:
            names = list(dict.fromkeys(names + documented_names(body)))
        # Short alias/variant pages delegate details to an official sibling page.
        if not re.search(r'^## (?:语法|Syntax)', body, re.M):
            reference = re.search(r'函数\s*\[?`[A-Za-z0-9_]+`\]?\((\./[^)]+\.md)\).*?(?:别名|变种)', body)
            if reference:
                sibling = (path.parent / reference[1]).resolve()
                if sibling.is_relative_to(source.resolve()) and sibling.is_file():
                    _, details = parse_page(sibling.read_text())
                    body += '\n\n## 相关函数的参数与用法\n\n' + details
        if not names:
            continue
        relative = path.relative_to(checkout / LOCALE_ROOT).with_suffix('').as_posix()
        key = ('special__' + extra_name.lower()) if extra_name else relative.removeprefix('sql-manual/sql-functions/').replace('/', '__')
        url = 'https://doris.apache.org/zh-CN/docs/4.x/' + relative + '/'
        urls[key] = url
        target = output / 'pages' / f'{key}.html'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(render_page(title, body, url))
        quality[key] = not bool(re.search(r'^## (?:语法|Syntax)', body, re.M))
        pages.append({'page': key, 'names': names, 'url': url, 'source': f'{LOCALE_ROOT}/{relative}.md'})
        for name in names:
            priority = (not bool(re.search(r'^## (?:语法|Syntax)', body, re.M)), name != path.stem.upper().replace('-', '_'), relative)
            if name not in candidates or priority < candidates[name]:
                candidates[name] = priority
                index[name] = key
    catalog = json.loads(catalog_path.read_text())
    for function in catalog:
        names = [function['name'], *function.get('aliases', [])]
        page = min((index[n.upper()] for n in names if n.upper() in index), key=lambda k: (quality[k], k), default=None)
        if page:
            for name in names:
                index.setdefault(name.upper(), page)
    catalog_names = {n.upper() for f in catalog for n in [f['name'], *f.get('aliases', [])]}
    missing = [f for f in catalog if f['name'].upper() not in index]
    for filename in (output / 'pages').glob('*.html'):
        if filename.stem not in {p['page'] for p in pages}:
            filename.unlink()
    (output / 'index.tsv').write_text(''.join(f'{n}\t{index[n]}\t{urls[index[n]]}\n' for n in sorted(index)))
    manifest = {'source_repository': 'https://github.com/apache/doris-website', 'source_commit': commit,
                'version': '4.x', 'locale': 'zh-CN', 'license': 'Apache-2.0',
                'page_count': len(pages), 'reachable_pages': len(set(index.values())), 'indexed_names': len(index), 'catalog_definitions': len(catalog),
                'covered_catalog_definitions': len(catalog) - len(missing),
                'covered_catalog_names': len(catalog_names.intersection(index)),
                'metadata_only': missing, 'pages': pages}
    (output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    (output / 'APACHE_DORIS_WEBSITE_LICENSE.txt').write_text((checkout / 'LICENSE').read_text())
    print(json.dumps({k: v for k, v in manifest.items() if k not in {'pages', 'metadata_only'}}, ensure_ascii=False, indent=2))
    print('Metadata-only:', ', '.join(f['name'] for f in missing))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('checkout', type=Path)
    parser.add_argument('--catalog', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('src/main/resources/docs/functions/zh-CN'))
    args = parser.parse_args()
    generate(args.checkout, args.output, args.catalog)

#!/usr/bin/env python3
"""Report every API finding; fail on internal usages outside exact owner-approved call sites."""
import argparse
from collections import Counter
from pathlib import Path
import re


def internal_usages(path):
    if not path.exists():
        return Counter()
    rows = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        if not line.startswith('Internal ') or '. This ' not in line:
            raise ValueError(f'Unrecognized verifier finding in {path}: {line}')
        rows.append(line.split('. This ', 1)[0] + '.')
    return Counter(rows)


def approvals(path):
    if not path.exists():
        return Counter()
    rows = [line.strip() for line in path.read_text().splitlines()
            if line.strip() and not line.lstrip().startswith('#')]
    if any(not row.startswith('Internal ') or not row.endswith('.') for row in rows):
        raise ValueError(f'Invalid exact-call-site approval in {path}')
    return Counter(rows)


def audit(root, plugin_id, version, targets, approved):
    failures = 0
    for target in targets:
        report = root / target / 'plugins' / plugin_id / version
        verdict = report / 'verification-verdict.txt'
        if not verdict.is_file():
            raise ValueError(f'Missing verifier report: {verdict}')
        uses = internal_usages(report / 'internal-api-usages.txt')
        unapproved = uses - approved
        known = uses & approved
        experimental = report / 'experimental-api-usages.txt'
        experimental_rows = [line.strip() for line in experimental.read_text().splitlines() if line.strip()] if experimental.exists() else []
        own_experimental = [line for line in experimental_rows if re.match(
            r'Experimental API \S+ (?:dev\.brikk\.|dev\.sort\.)', line)]
        print(f'{target}: {sum(uses.values())} internal, {sum(known.values())} approved, '
              f'{sum(unapproved.values())} unapproved; {len(experimental_rows)} experimental '
              f'({len(own_experimental)} on our APIs)')
        for finding, count in sorted(uses.items()):
            print(f'  {count}x {finding}')
        for finding, count in sorted(unapproved.items()):
            print(f'  UNAPPROVED {count}x {finding}')
        for finding in own_experimental:
            print(f'  OWN EXPERIMENTAL API: {finding}')
        failures += sum(unapproved.values()) + len(own_experimental)
    return failures


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('reports', type=Path)
    parser.add_argument('plugin_id')
    parser.add_argument('version')
    parser.add_argument('targets', nargs='+')
    parser.add_argument('--approvals', type=Path, default=Path('api-usage-approvals.txt'))
    args = parser.parse_args()
    try:
        failures = audit(args.reports, args.plugin_id, args.version, args.targets, approvals(args.approvals))
    except ValueError as error:
        parser.exit(1, f'{error}\n')
    if failures:
        parser.exit(1, f'{failures} unapproved internal or own-experimental API findings block release. Full reports are unchanged.\n')

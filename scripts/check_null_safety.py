#!/usr/bin/env python3
"""Check Kotlin syntax for nullable types and null-producing escape hatches.

Scans comments and strings lexically (including nested comments and executable
string templates). This is a source-policy check, not Kotlin type inference.
No inline suppressions, baseline or per-file allowlist are supported.
"""
import argparse
from pathlib import Path


def violations(source):
    found = []
    n = len(source)

    def report(index, reason):
        found.append((source.count('\n', 0, index) + 1, reason))

    def quoted(i, quote):
        raw = quote == '"' and source.startswith('"""', i)
        delimiter = '"""' if raw else quote
        i += len(delimiter)
        while i < n:
            if source.startswith(delimiter, i):
                return i + len(delimiter)
            if not raw and source[i] == '\\':
                i += 2
            elif quote == '"' and source.startswith('${', i):
                i = code(i + 2, True)
            else:
                i += 1
        return i

    def code(i, template=False):
        depth = 0
        while i < n:
            if source.startswith('//', i):
                end = source.find('\n', i)
                i = n if end < 0 else end
            elif source.startswith('/*', i):
                level = 1
                i += 2
                while i < n and level:
                    if source.startswith('/*', i):
                        level += 1
                        i += 2
                    elif source.startswith('*/', i):
                        level -= 1
                        i += 2
                    else:
                        i += 1
            elif source[i] in ('"', "'"):
                i = quoted(i, source[i])
            elif source[i] == '`':
                end = source.find('`', i + 1)
                word = source[i + 1:end]
                if word.endswith('OrNull') or word in ('requireNotNull', 'checkNotNull'):
                    report(i, 'nullable producer/assertion: ' + word)
                i = n if end < 0 else end + 1
            elif source[i] == '{':
                depth += 1
                i += 1
            elif source[i] == '}':
                if template and depth == 0:
                    return i + 1
                depth -= 1
                i += 1
            elif source[i] == '?':
                report(i, 'nullable type, safe cast/call or Elvis operator')
                i += 1
            elif source.startswith('!!', i):
                report(i, 'non-null assertion')
                i += 2
            elif source[i].isalpha() or source[i] == '_':
                start = i
                i += 1
                while i < n and (source[i].isalnum() or source[i] == '_'):
                    i += 1
                word = source[start:i]
                if word == 'null':
                    report(start, 'null literal')
                elif word.endswith('OrNull') or word in ('requireNotNull', 'checkNotNull'):
                    report(start, 'nullable producer/assertion: ' + word)
            else:
                i += 1
        return i

    code(0)
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', nargs='?', type=Path, default=Path(__file__).resolve().parents[1])
    root = parser.parse_args().root.resolve()
    ignored = {'.git', '.gradle', '.kotlin', 'build', 'node_modules'}
    failures = []
    scanned = 0
    for path in sorted(root.rglob('*')):
        if path.suffix not in {'.kt', '.kts'} or ignored.intersection(path.relative_to(root).parts):
            continue
        scanned += 1
        for line, reason in violations(path.read_text(encoding='utf-8')):
            failures.append(f'{path.relative_to(root)}:{line}: {reason}')
    for failure in failures:
        print(failure)
    print(f'null-safety: {scanned} Kotlin files, {len(failures)} violations')
    return 1 if failures or scanned == 0 else 0


if __name__ == '__main__':
    raise SystemExit(main())

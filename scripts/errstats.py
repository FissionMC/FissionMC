#!/usr/bin/env python3
"""Summarize javac errors from a Gradle log: unique count, categories, NMS owners.
  errstats.py <log> [--owners] [--list <substring>] [--owner <SimpleName>[,<SimpleName>...]]
--owner prints the errors whose `location:` is exactly that class (simple name, as javac prints it)."""
import re, sys, collections
t = open(sys.argv[1]).read().split('* What went wrong')[0]
blocks = re.split(r'\n(?=/\S+\.java:\d+: error:)', t)
errs = [b for b in blocks if ': error:' in b]
print('unique errors', len(errs))
cat = collections.Counter(); loc = collections.Counter(); files = collections.Counter()
for b in errs:
    msg = re.search(r'error: (.*)', b).group(1)
    k = re.sub(r"\b\w+ has (private|protected) access", "X has \\1 access", msg)
    k = re.sub(r'symbol: .*', '', k)
    cat[k[:70]] += 1
    l = re.search(r'location: (?:class|interface|variable \S+ of type|@interface) (\S+)', b)
    if l: loc[l.group(1).split('<')[0]] += 1
    files[re.match(r'(\S+\.java)', b).group(1).split('/java/')[-1]] += 1
for k, v in cat.most_common(20): print(v, k)
if '--owners' in sys.argv:
    print('--- locations'); [print(v, k) for k, v in loc.most_common(60)]
    print('--- files'); [print(v, k) for k, v in files.most_common(30)]
if '--list' in sys.argv:
    sub = sys.argv[sys.argv.index('--list') + 1]
    for b in errs:
        if sub in b: print(b.strip()[:600]); print('---')

if '--owner' in sys.argv:
    want = set(sys.argv[sys.argv.index('--owner') + 1].split(','))
    n = 0
    for b in errs:
        l = re.search(r'location: (?:class|interface|variable \S+ of type|@interface) (\S+)', b)
        if l and l.group(1).split('<')[0] in want:
            print(b.strip()[:700]); print('---'); n += 1
    print(n, 'errors for', ','.join(sorted(want)))
